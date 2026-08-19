package dev.svod.engine.index

/**
 * The golden corpus for [RetrievalEval]: a small, hand-labelled stand-in for a real personal vault.
 *
 * Two properties matter and both are deliberate:
 *
 * 1. **It is bilingual.** Roughly half the notes are Bulgarian, half English, and a whole query
 *    subset asks in one language for an answer written in the other. That is the single capability
 *    a multilingual embedder is chosen for, and no other test in the suite exercises it.
 * 2. **It is full of distractors.** Several pairs of notes share nearly all of their vocabulary
 *    while answering different questions, and several notes answer a question using none of the
 *    words the question is phrased in. A corpus without those scores every ranker the same and
 *    measures nothing.
 *
 * Labels are assigned from the text — what a reader would call the right answer — and never from
 * what search happens to return today. A golden set derived from the current implementation can
 * only ever agree with it.
 */
object GoldenCorpus {

    /** path -> markdown content, seeded into a temp vault. */
    val notes: List<Pair<String, String>> = listOf(

        // ---------------------------------------------------------------- English: ops

        "ops/postgres-restore.md" to """
            ---
            title: Restoring a Postgres dump into staging
            tags: [ops, postgres, runbook]
            created: 2024-02-11
            ---

            ## Why staging gets reloaded
            Staging drifts away from production after a week of manual poking, so once a sprint I
            drop it and load the newest dump. Ten minutes of downtime nobody notices.

            ## The commands
            Stop the application container first, otherwise open connections block the drop. Then
            pg_restore --clean --if-exists --no-owner -d svod_staging svod-latest.dump. The
            --no-owner flag matters: staging has one role and production has four, and without it
            every GRANT fails and the run exits non-zero halfway through.

            ## What always breaks
            Extensions. pgcrypto lives in the production schema and is not carried in the dump, so
            create it by hand before loading. There is no transaction wrapped around the whole
            thing, so a run that stops in the middle leaves a half populated database — start again
            from the drop, never from where it stopped.
        """.trimIndent(),

        "ops/postgres-backup-policy.md" to """
            ---
            title: Postgres backup schedule and retention
            tags: [ops, postgres, backup]
            created: 2024-01-08
            ---

            ## Schedule
            A cron job on the database host runs pg_dump at 03:15 every night. Custom format,
            compression level six, one file per database. The whole pass finishes in under four
            minutes at the current size.

            ## Retention
            Seven nightly dumps, four weekly, twelve monthly. A small shell script prunes anything
            older, and it counts files rather than reading dates, because a clock skew once deleted
            an entire week in one run.

            ## Offsite copy
            Every morning at 05:00 the dumps are pushed to object storage with rclone, encrypted
            before they leave the host. The key lives in the password manager and nowhere on the
            server — a copy an attacker can read is not a backup.

            ## Untested is unproven
            A restore drill goes on the calendar every quarter. Two of the first three drills
            failed, both for boring reasons that only a drill would have found.
        """.trimIndent(),

        "ops/docker-log-rotation.md" to """
            ---
            title: Docker containers filled the disk
            tags: [ops, docker, incident]
            created: 2024-03-02
            ---

            ## Symptom
            The host hit one hundred percent on the root filesystem at 02:00 and everything went
            read-only. Disk usage pointed straight at the docker containers directory: a single
            chatty service had written forty-one gigabytes of JSON logs since the last rebuild.

            ## Cause
            The default json-file logging driver keeps everything forever. Nothing rotates it, and
            docker compose down leaves the files behind unless the container itself is removed.

            ## Fix
            Set the limit globally in the docker daemon configuration with max-size 50m and
            max-file 3, then restart the daemon. Setting it per service in the compose file works
            as well, but only newly created containers pick it up, so recreate them afterwards.

            ## Follow up
            A disk alert at eighty percent now exists. The incident was not that logs grow large;
            it was that nobody was told before the machine stopped serving.
        """.trimIndent(),

        "ops/docker-restart-policy.md" to """
            ---
            title: Containers did not come back after the host reboot
            tags: [ops, docker, incident]
            created: 2024-03-19
            ---

            ## What happened
            The provider rebooted the host for a hypervisor patch at 04:00. The host came back,
            the docker daemon came back, and not one of the seven containers started. Forty
            minutes of downtime passed before anyone looked.

            ## Two separate causes
            The compose file carried no restart key at all, so the default of "no" applied. On top
            of that the docker service was not enabled at boot on this machine — it had only ever
            been started by hand during the original setup, which nobody remembered.

            ## Fix
            A restart policy of unless-stopped on every service in the compose file, the daemon
            enabled at boot, and then a real test reboot rather than trust in the configuration.
            The "always" policy was rejected because it fights a deliberate manual stop.

            ## Note
            unless-stopped still brings the container up at boot; it only remembers a stop that a
            person asked for.
        """.trimIndent(),

        "ops/oom-killer.md" to """
            ---
            title: The service died in the night with no trace
            tags: [ops, linux, jvm, incident]
            created: 2024-04-05
            ---

            ## Nothing was written
            Three mornings in a row the process was simply gone. No stack trace, no shutdown
            message, no exit banner — the last line the service produced was an ordinary request
            being served at a perfectly ordinary time.

            ## Where the answer was
            The kernel ring buffer. "Out of memory: Killed process 3121 (java)". The kernel had
            picked the largest resident process and terminated it with signal nine, which is
            exactly why the runtime never got the chance to write anything about it.

            ## Why it grew
            The heap ceiling was set to six gigabytes, copied over from a machine with sixteen
            gigabytes of RAM. This one has four. Under the nightly report job the heap expanded
            toward its ceiling and the machine ran out of physical memory long before the runtime
            thought it was anywhere near a limit.

            ## Fix
            Ceiling dropped to two gigabytes, a swap file added as a shock absorber, and an alert
            wired up on used memory.
        """.trimIndent(),

        // ---------------------------------------------------------------- English: dev

        "dev/kotlin-coroutine-cancellation.md" to """
            ---
            title: Coroutine cancellation is cooperative
            tags: [dev, kotlin, coroutines]
            created: 2024-02-27
            ---

            ## The rule
            Cancelling a job does not stop code. It sets a flag and throws at the next suspension
            point. A tight loop that never suspends runs all the way to its end, cancelled or not,
            and whoever awaits it waits for the whole thing.

            ## Making a loop stoppable
            Call ensureActive() inside the loop, or yield() when the work can be interleaved.
            Checking isActive by hand works too, but it swallows the reason silently, so a job
            that was cancelled looks exactly like one that finished cleanly.

            ## Cleanup that must run
            A finally block inside a cancelled coroutine cannot suspend — the first suspending call
            inside it throws immediately. Wrap that cleanup in withContext(NonCancellable) when it
            has to reach a database or close a remote session.

            ## Structured concurrency
            One child failing takes down its siblings through the parent scope. That is the
            feature, not a defect: supervisorScope is the opt out and should be a deliberate choice
            rather than a reflex.
        """.trimIndent(),

        "dev/kotlin-coroutines-scratch.md" to """
            ---
            title: Coroutines - scratch notes
            tags: [dev, kotlin, coroutines, scratch]
            created: 2023-11-03
            ---

            ## Half remembered
            Something about cancellation not being immediate. There is a flag involved and you have
            to check it yourself inside a loop, I think. Look all of this up properly before the
            talk instead of trusting the version in my head.

            ## Todo
            Read the structured concurrency article again. Find out what the non cancellable thing
            is actually called. Also whether a finally block runs at all when a job is cancelled — I
            got a different answer in two places and never resolved which one was right.
        """.trimIndent(),

        "dev/lucene-analyzer-notes.md" to """
            ---
            title: Notes on Lucene analyzers
            tags: [dev, search, lucene]
            created: 2024-01-22
            ---

            ## Tokenizer, then filters
            An analyzer is a tokenizer plus a chain of filters. The standard tokenizer splits on
            Unicode word boundaries; lowercasing, stop word removal and stemming are all filters
            sitting after it. Index and query must run the same chain or nothing matches.

            ## Stemming is language bound
            The English stemmer turns running into run and leaves Cyrillic entirely alone. For a
            mixed vault a per field analyzer is not enough, because a single field holds both
            languages in different documents and there is nothing to switch on.

            ## What surprised me
            Analyzers run at query time as well as at index time. Changing one without reindexing
            produces a search that still works for old documents and quietly fails for new ones.
            The mismatch raises no error at all, it only returns fewer results.
        """.trimIndent(),

        // ---------------------------------------------------------------- English: life

        "reading/thinking-fast-and-slow.md" to """
            ---
            title: Reading notes - Thinking, Fast and Slow
            tags: [reading, psychology]
            created: 2023-09-14
            ---

            ## Two systems
            The fast system answers before it is asked and is almost never uncertain. The slow one
            is lazy, expensive, and easily crowded out by tiredness or hunger. Most of the book is
            a catalogue of what the fast one gets confidently wrong.

            ## Anchoring
            An arbitrary number seen minutes earlier moves an unrelated estimate. It works on
            people who know that it is happening, which is the uncomfortable part.

            ## Loss aversion
            Losing a hundred hurts about twice as much as gaining a hundred pleases. That explains
            more about how I hold a falling position than any market theory has managed to.

            ## What I took from it
            Slow down for decisions that are hard to reverse, and write the reasoning down
            beforehand. The written version is the only defence against later remembering that I
            knew all along.
        """.trimIndent(),

        "health/sleep-log-march.md" to """
            ---
            title: March notes on the 3am waking
            tags: [health, log]
            created: 2024-03-31
            ---

            ## The pattern
            Nine nights out of fourteen I was awake at 03:10, heart going, running the release
            checklist in my head. Getting up and reading in the other room worked better than lying
            there arguing with myself about it.

            ## What I changed
            No espresso after 14:00, which is far earlier than it sounds when the last one used to
            be at 17:00. Radiator off in the bedroom, window open, the room down to about eighteen
            degrees. Phone left charging in the hall, so the first thing at 03:10 is not a screen.

            ## Where it landed
            By the last week of the month the waking was down to two nights, and both of those
            followed a late deploy. The cold room did the most work; the phone rule did more than
            expected for how small a change it is.
        """.trimIndent(),

        "finance/index-fund-drift.md" to """
            ---
            title: Quarterly review of the fund holdings
            tags: [finance, investing]
            created: 2024-04-02
            ---

            ## Where things stand
            The world equity fund has grown to seventy-one percent of the account against a written
            plan of sixty, and the bond side has shrunk to twenty-two. Nobody decided this; it is
            simply what two good quarters do on their own.

            ## The correction
            Sell enough of the overgrown side to bring both back to the written plan, once a
            quarter, on a fixed date, without an opinion about where the market is heading. New
            contributions go to whichever side is behind, which does most of the work with no sale
            at all.

            ## Tax
            Selling inside this account is not a taxable event here, so the cost of the correction
            is a spread and nothing else. Outside it I would let new money do all of it instead.

            ## Rule
            Act only when a side is more than five points away from its written plan. Below that
            the trading cost outweighs whatever is being corrected.
        """.trimIndent(),

        "travel/vienna-three-days.md" to """
            ---
            title: Vienna, three days in November
            tags: [travel, notes]
            created: 2023-11-20
            ---

            ## Getting around
            The forty-eight hour transit pass paid for itself by the second afternoon. Trams beat
            the underground for anything inside the ring, because the walk between platforms eats
            whatever the extra speed saves.

            ## Where the time went
            The Kunsthistorisches took an entire morning and deserved it. The Belvedere is crowded
            by ten and pleasant at four. The Christmas market at Rathausplatz is a food stop rather
            than an attraction, and the smaller one behind Karlskirche is better.

            ## Practical
            Cash is still wanted at the smaller coffee houses. Reserve anything with a name a week
            ahead; walking in worked exactly once out of five attempts. The November light is gone
            by half past four, so put the outdoor half of the day first.
        """.trimIndent(),

        "meetings/2024-05-14-vendor-call.md" to """
            ---
            title: Call with the object storage vendor
            tags: [meeting, procurement]
            created: 2024-05-14
            ---

            ## Present
            Their account manager and a solutions engineer, me and Ivan. Forty minutes, second call
            with them.

            ## What they offered
            Ten terabytes at a flat monthly rate with no egress charge, which is the only number
            that matters for us — two thirds of the current bill is egress. Regional endpoint in
            Frankfurt, S3 compatible API, no minimum term after the first year.

            ## Open questions
            They could not say whether object lock is available on the flat plan, and immutable
            backups are the entire reason for the move. Their engineer promised an answer in
            writing by Friday.

            ## Decision
            Nothing signed. If object lock is included we move the backup target next month and
            keep the current provider for one full retention cycle in parallel.
        """.trimIndent(),

        // ---------------------------------------------------------------- Bulgarian: ops

        "ops/certbot-podnovyavane.md" to """
            ---
            title: Подновяване на сертификата с certbot
            tags: [ops, tls, certbot]
            created: 2024-02-19
            ---

            ## Как е нагласено
            Таймер пуска certbot renew два пъти дневно. Подновява само ако остават под тридесет
            дни, така че реално се задейства веднъж на два месеца и записът от останалите пускания
            е скучен.

            ## Кукичката след подновяване
            Новият файл сам по себе си не върши работа — nginx държи стария в паметта. В
            конфигурацията за подновяване стои deploy-hook, който презарежда nginx в контейнера.
            Без него сайтът продължава със стария сертификат до следващото рестартиране и никой не
            забелязва, докато не изтече.

            ## Проверка, че наистина работи
            certbot renew --dry-run веднъж на тримесечие. Веднъж сухото пускане мина успешно, а
            истинското подновяване се провали, защото при --dry-run кукичката не се изпълнява. Сега
            гледам и датата в самия сертификат от външна машина.
        """.trimIndent(),

        "ops/certbot-parvo-izdavane.md" to """
            ---
            title: Първоначално издаване на сертификат
            tags: [ops, tls, certbot]
            created: 2023-10-07
            ---

            ## Преди да пуснеш certbot
            Записът в зоната трябва вече да сочи към машината и да се е разпространил. Ако не е,
            заявката се проваля и след пет неуспешни опита домейнът влиза в ограничението на Let's
            Encrypt за цял час и не остава какво да се направи, освен да се чака.

            ## Webroot или standalone
            При работещ уебсървър — webroot, с път до директорията, която сървърът реално отдава.
            При гола машина — standalone, но той вдига собствен слушател на порт осемдесет и се
            сблъсква с nginx, така че първо се спира контейнерът.

            ## Правата на ключа
            privkey.pem остава 640 и собственик root с група docker. Никога 644. Ключ, който всеки
            локален потребител може да прочете, е компрометиран ключ, а не дребен пропуск в правата.
        """.trimIndent(),

        "ops/wifi-router-problem.md" to """
            ---
            title: Прекъсванията на домашната мрежа вечер
            tags: [ops, мрежа, дом]
            created: 2024-04-22
            ---

            ## Кога се случва
            Само между осем и единадесет вечерта и само на горния етаж. През деня не се
            възпроизвежда по никакъв начин, което ме заблуждаваше цели три седмици.

            ## Какво се оказа
            Съседските точки за достъп. Скенерът показа единадесет мрежи на канал шест в диапазона
            два и четири гигахерца, всичките включени точно вечер. Рутерът беше нагласен на
            автоматичен избор на канал и си стоеше кротко там, в най-натоварения.

            ## Какво направих
            Фиксиран канал единадесет за долния диапазон и отделно име за петте гигахерца, за да не
            се лепят устройствата за по-слабия сигнал. Лаптопът и телевизорът минаха на новото име
            ръчно.

            ## Резултат
            Вечерните прекъсвания изчезнаха напълно. Едно устройство остана на старата мрежа,
            защото е стар модел и просто не вижда петте гигахерца.
        """.trimIndent(),

        // ---------------------------------------------------------------- Bulgarian: dev

        "dev/git-rebase-belezhki.md" to """
            ---
            title: Бележки за rebase и конфликти
            tags: [dev, git]
            created: 2023-12-11
            ---

            ## Кога rebase и кога merge
            Rebase само върху клон, който е още мой и никой друг не е издърпал. Върху споделен клон
            пренаписването на историята кара всички останали да оправят нещо, което не са счупили.

            ## Конфликтите при rebase
            Идват по един принос наведнъж и точно затова са объркващи — оправяш едно и също място
            три пъти, защото три последователни приноса са го пипали. rerere запомня решението и го
            прилага само при следващото появяване.

            ## Спасителният изход
            reflog. След объркана операция старият връх на клона още е там и се връща с reset --hard
            към записа отпреди нея. Досега не съм губил работа по този начин, само време.
        """.trimIndent(),

        "dev/regex-lookahead.md" to """
            ---
            title: Проверка напред в регулярните изрази
            tags: [dev, regex]
            created: 2024-02-02
            ---

            ## Какво прави
            Условие, което се проверява, но не изяжда текст. Отрицателната форма казва "тук да не
            следва това" и позицията остава същата, така че съвпадението продължава оттам, откъдето
            е започнало.

            ## Типичната ми употреба
            Да намеря всички редове с дадена дума, освен когато е част от определено съкращение. С
            отрицателна проверка напред става на един израз, вместо да се филтрира на втора стъпка
            извън израза.

            ## Капанът
            Проверката назад в много реализации иска фиксирана дължина. Изразът се компилира при
            едната реализация и гърми при другата, а съобщението за грешка не казва защо. Затова
            държа тестове за самия израз, а не само за кода около него.
        """.trimIndent(),

        // ---------------------------------------------------------------- Bulgarian: кухня

        "kuhnia/hlyab-s-kvas.md" to """
            ---
            title: Хляб с квас
            tags: [кухня, рецепта, хляб]
            created: 2024-01-13
            ---

            ## Закваската
            Изваждам буркана от хладилника вечерта, храня го с равни части брашно и вода и го
            оставям на плота. На сутринта е удвоен и мирише на кисело мляко, а не на ацетон. Ако
            мирише на ацетон, е бил гладен дълго и иска още едно хранене, преди да върши работа.

            ## Замесване и почивка
            Петстотин грама брашно, триста и петдесет вода, сто закваска, десет сол. Смесвам без
            солта, чакам час, чак тогава я добавям. Три сгъвания през половин час, после шест часа
            на плота при двадесет и два градуса.

            ## Печене
            Нощ в хладилника, а на сутринта право от студа във вече нагорещения чугунен съд.
            Двадесет минути с капак, двадесет без. Кората пука, докато изстива, и това е
            единственият честен признак, че е станал.
        """.trimIndent(),

        "kuhnia/hlyab-s-maya.md" to """
            ---
            title: Бърз хляб с мая
            tags: [кухня, рецепта, хляб]
            created: 2024-01-20
            ---

            ## Кога го правя
            Когато съм се сетил за хляб чак в шест вечерта. От брашно до нарязан хляб минават малко
            над два часа и нищо не трябва да е започнато от предния ден.

            ## Пропорции
            Петстотин грама брашно, седем грама суха мая, триста и двадесет вода, десет сол, лъжица
            зехтин. Маята се разтваря във вода на около тридесет и пет градуса — по-гореща я убива и
            тестото после не помръдва, колкото и да се чака.

            ## Втасване и печене
            Час на топло до удвояване, оформяне, още четиридесет минути в тавата. Фурна на двеста и
            двадесет с тавичка вода на дъното за първите десет минути. Става добър хляб, но на
            втория ден е сух, докато другият изкарва четири дни.
        """.trimIndent(),

        "kuhnia/lyutenitsa.md" to """
            ---
            title: Лютеница за зимнина
            tags: [кухня, рецепта, зимнина]
            created: 2023-09-24
            ---

            ## Количества
            Десет килограма червени чушки капия, три килограма домати, килограм патладжан. От това
            излизат около осемнадесет буркана по триста грама, което стига за годината и остава и за
            подаръци.

            ## Печене и чистене
            Чушките на скара, докато почернеят на петна, после в найлонов чувал да се изпотят —
            иначе обелването отнема двойно повече време. Патладжанът също на скара, а не варен,
            защото цялата разлика във вкуса идва точно оттам.

            ## Варене
            Смила се едро, не на пюре. Вари се бавно около три часа с бъркане, докато лъжицата не
            остави следа, която да не се затваря веднага. Сол, малко захар, олиото накрая.
            Стерилизация двадесет минути и обръщане на бурканите с капачките надолу.
        """.trimIndent(),

        // ---------------------------------------------------------------- Bulgarian: живот

        "zdrave/krastno-nalyagane.md" to """
            ---
            title: Измервания на кръвното
            tags: [здраве, дневник]
            created: 2024-04-18
            ---

            ## Защо започнах
            На профилактичния преглед излезе сто четиридесет и пет на деветдесет и лекарката поиска
            две седмици измервания вкъщи, преди да реши каквото и да било.

            ## Как се мери правилно
            Сутрин преди кафе и вечер преди лягане, седнал и облегнат, пет минути на спокойствие
            преди самото измерване, ръката на височината на сърцето. Първото измерване почти винаги
            излиза по-високо от следващите две, затова записвам средното от второто и третото.

            ## Какво се получи
            Средно сто тридесет и две на осемдесет и четири за четиринадесет дни. По-високо в дните
            след лоша нощ и след солено ядене. Лекарката каза да намаля солта и да меря пак след три
            месеца, без нищо друго засега.
        """.trimIndent(),

        "finansi/danaci-svobodna-profesiya.md" to """
            ---
            title: Данъци на свободна практика
            tags: [финанси, данъци]
            created: 2024-01-30
            ---

            ## Нормативно признати разходи
            От брутния доход се приспадат двадесет и пет процента разходи, без да се доказват с
            нищо. Върху остатъка се дължат осигуровки, а данъкът се смята чак след като се извадят
            и те.

            ## Авансови вноски
            До края на месеца след всяко тримесечие. За четвъртото тримесечие авансова вноска не се
            дължи — сумата се плаща с годишната декларация, което всяка година ме изненадва, защото
            е и най-голямата.

            ## Декларацията
            Подава се до тридесети април за предходната година. Платените осигуровки влизат в
            приложението, а не в основната част, и ако са пропуснати там, данъкът излиза по-висок,
            но декларацията минава без никаква грешка. Проверявам два пъти точно това поле.
        """.trimIndent(),

        "sreshti/2024-06-03-schetovoditel.md" to """
            ---
            title: Среща със счетоводителя, 3 юни
            tags: [среща, финанси]
            created: 2024-06-03
            ---

            ## Присъстващи и повод
            Аз и Мария от кантората, четиридесет минути в офиса. Поводът е преминаването към
            фактуриране на чуждестранни клиенти.

            ## Регистрация по ДДС
            При услуги към фирма в друга държава от Съюза регистрацията по член деветдесет и седем
            "а" е задължителна преди първата фактура, а не след нея. Тя не дава право на данъчен
            кредит за покупките и не превръща фирмата в регистрирана по общия ред.

            ## Валута и курс
            Фактурите могат да са в евро, но в дневника влизат по курса за деня на данъчното
            събитие. Курсът се записва в самата фактура, за да не се търси после при проверка.

            ## Какво поех
            Да подам заявлението тази седмица и да пратя два примерни договора за преглед до петък.
        """.trimIndent(),

        "sreshti/schetovoditel-nabarzo.md" to """
            ---
            title: Набързо след разговора със счетоводителя
            tags: [среща, чернова]
            created: 2023-05-29
            ---

            ## Записано в асансьора
            Нещо за ДДС при чуждестранни клиенти — имало специална регистрация, различна от
            нормалната. Не помня номера на члена, нито дали е преди или след първата фактура, а
            точно това беше важното. Май ставаше дума и за данъчен кредит, но не съм сигурен в коя
            посока.

            ## Да питам
            Дали важи и за клиенти извън Съюза. И какво става с курса на валутата — по кой ден се
            взима и къде се записва. Този път да си запиша всичко на място, а не после по стълбите
            по памет, защото очевидно не става.
        """.trimIndent(),

        "chetene/atomic-habits-belezhki.md" to """
            ---
            title: Бележки по "Атомни навици"
            tags: [четене, навици]
            created: 2023-08-19
            ---

            ## Системата, не целта
            Целта определя посоката, но резултатът идва от системата. Двама души с една и съща цел
            се различават единствено по това какво правят в делничния вторник.

            ## Средата бие волята
            По-лесно е да преместиш чинията с бонбони, отколкото да ѝ устоиш двадесет пъти на ден.
            Всичко, което искам да правя, стои на видно място, а всичко останало иска две
            допълнителни действия, преди да стигна до него.

            ## Правилото за две минути
            Новият навик започва във версия, която отнема под две минути. В началото целта е
            присъствието, а не обемът — обемът идва сам, когато присъствието е сигурно.

            ## Какво остана
            Не пропускам два пъти подред. Един пропуснат ден е случайност, два са вече ново
            поведение.
        """.trimIndent(),

        "patuvane/rodopi-mai.md" to """
            ---
            title: Родопите през май
            tags: [пътуване, планина]
            created: 2024-05-27
            ---

            ## Пътят
            До Смолян по магистралата и после два часа по завои, които на картата изглеждат много
            по-кратки. Тръгването преди шест спестява цял час преди Пловдив.

            ## Къде спахме
            Малка къща над Широка лъка, с печка, която вечер е необходима дори в края на май.
            Стопанинът готви само по уговорка от предния ден, което разбрахме твърде късно и вечеряхме
            в селото.

            ## Пътеките
            До водопада е час и половина в едната посока, лесно и с деца. Билото над селото е друга
            работа — мъглата пада за двадесет минути и маркировката изчезва напълно. Взимаме офлайн
            карта и връхна дреха дори за два часа навън.
        """.trimIndent(),
    )

    /** English query, English answer. */
    val englishQueries: List<GoldenQuery> = listOf(
        GoldenQuery(
            "load last night's database dump into staging",
            mapOf("ops/postgres-restore.md" to 3, "ops/postgres-backup-policy.md" to 1),
            "distractor pair: both notes are dense in postgres/dump/backup, only one is the runbook",
        ),
        GoldenQuery(
            "how many nightly database backups do we keep",
            mapOf("ops/postgres-backup-policy.md" to 3, "ops/postgres-restore.md" to 1),
            "same distractor pair, opposite direction — retention lives in the policy note",
        ),
        GoldenQuery(
            "docker filled up the disk with logs",
            mapOf("ops/docker-log-rotation.md" to 3),
            "easy lexical match, with a sibling docker incident note as the distractor",
        ),
        GoldenQuery(
            "containers do not start again after the machine reboots",
            mapOf("ops/docker-restart-policy.md" to 3),
            "distractor pair: the other docker incident note shares compose/daemon/container wording",
        ),
        GoldenQuery(
            "insomnia remedies that actually worked",
            mapOf("health/sleep-log-march.md" to 3),
            "no lexical overlap at all — the note never says insomnia, remedy or even sleep",
        ),
        GoldenQuery(
            "why did the process get killed with no error message",
            mapOf("ops/oom-killer.md" to 3),
            "paraphrase with partial overlap; the note explains the kernel, not the application",
        ),
        GoldenQuery(
            "cleanup that must still run when a coroutine is cancelled",
            mapOf("dev/kotlin-coroutine-cancellation.md" to 3, "dev/kotlin-coroutines-scratch.md" to 1),
            "near-duplicate pair: the scratch note asks this exact question and never answers it",
        ),
        GoldenQuery(
            "what happens to a tight loop when I cancel the job",
            mapOf("dev/kotlin-coroutine-cancellation.md" to 3, "dev/kotlin-coroutines-scratch.md" to 1),
            "same near-duplicate pair; the scratch note carries the vocabulary without the answer",
        ),
        GoldenQuery(
            "why do results change after I edit the analyzer without reindexing",
            mapOf("dev/lucene-analyzer-notes.md" to 3),
            "easy term match, guards against the search notes being crowded out by the dev notes",
        ),
        GoldenQuery(
            "anchoring and loss aversion",
            mapOf("reading/thinking-fast-and-slow.md" to 3),
            "easy exact-term baseline; both terms appear as headings",
        ),
        GoldenQuery(
            "what did the storage vendor say about immutable backups",
            mapOf("meetings/2024-05-14-vendor-call.md" to 3, "ops/postgres-backup-policy.md" to 1),
            "distractor shares the whole backup/object-storage vocabulary with the ops policy note",
        ),
        GoldenQuery(
            "one holding grew far past its plan, sell or leave it",
            mapOf("finance/index-fund-drift.md" to 3),
            "paraphrase — the note never says rebalance, portfolio or allocation",
        ),
        GoldenQuery(
            "what is worth reserving ahead in Vienna",
            mapOf("travel/vienna-three-days.md" to 3),
            "easy, single obvious answer; a sanity check that ordinary queries still work",
        ),
    )

    /** Bulgarian query, Bulgarian answer. */
    val bulgarianQueries: List<GoldenQuery> = listOf(
        GoldenQuery(
            "как да презаредя nginx след като сертификатът се поднови",
            mapOf("ops/certbot-podnovyavane.md" to 3, "ops/certbot-parvo-izdavane.md" to 1),
            "distractor pair: both notes are certbot/nginx/сертификат throughout",
        ),
        GoldenQuery(
            "какви права трябва да има privkey.pem",
            mapOf("ops/certbot-parvo-izdavane.md" to 3),
            "same certbot pair, opposite direction — only the issuance note covers key permissions",
        ),
        GoldenQuery(
            "certbot спира заради ограничение след неуспешни опити",
            mapOf("ops/certbot-parvo-izdavane.md" to 3, "ops/certbot-podnovyavane.md" to 1),
            "rate limiting is described only in the issuance note; the renewal note shares every term",
        ),
        GoldenQuery(
            "закваската мирише на ацетон",
            mapOf("kuhnia/hlyab-s-kvas.md" to 3),
            "distractor pair: the yeast bread note shares flour/water/salt/oven and answers nothing here",
        ),
        GoldenQuery(
            "искам да опека хляб днес, без подготовка от предния ден",
            mapOf("kuhnia/hlyab-s-maya.md" to 3, "kuhnia/hlyab-s-kvas.md" to 1),
            "same bread pair, opposite direction — the sourdough note is the wrong answer, not an unrelated one",
        ),
        GoldenQuery(
            "колко буркана излизат от десет килограма чушки",
            mapOf("kuhnia/lyutenitsa.md" to 3),
            "easy numeric lookup inside a recipe note",
        ),
        GoldenQuery(
            "как се мери правилно кръвно налягане вкъщи",
            mapOf("zdrave/krastno-nalyagane.md" to 3),
            "easy term match in Bulgarian",
        ),
        GoldenQuery(
            "плаща ли се авансов данък за последното тримесечие",
            mapOf("finansi/danaci-svobodna-profesiya.md" to 3),
            "paraphrase — the note says четвъртото тримесечие, the query says последното",
        ),
        GoldenQuery(
            "трябва ли регистрация по ДДС преди първата фактура към фирма в чужбина",
            mapOf("sreshti/2024-06-03-schetovoditel.md" to 3, "sreshti/schetovoditel-nabarzo.md" to 1),
            "near-duplicate pair: the scratch note poses the question and admits it does not know",
        ),
        GoldenQuery(
            "по кой курс влиза фактура в евро",
            mapOf("sreshti/2024-06-03-schetovoditel.md" to 3, "sreshti/schetovoditel-nabarzo.md" to 1),
            "same near-duplicate pair; the scratch note lists this as an open question",
        ),
        GoldenQuery(
            "как да върна клона след объркан rebase",
            mapOf("dev/git-rebase-belezhki.md" to 3),
            "easy, mixed-script query (Cyrillic plus a Latin technical term)",
        ),
        GoldenQuery(
            "интернетът се разпада само вечер",
            mapOf("ops/wifi-router-problem.md" to 3),
            "paraphrase — the note says прекъсвания на домашната мрежа, never интернет",
        ),
        GoldenQuery(
            "какво да взема за преход, ако падне мъгла по билото",
            mapOf("patuvane/rodopi-mai.md" to 3),
            "answer sits in the last section of a travel note that is mostly about something else",
        ),
        GoldenQuery(
            "правилото за две минути при нов навик",
            mapOf("chetene/atomic-habits-belezhki.md" to 3),
            "easy exact-term baseline in Bulgarian",
        ),
    )

    /**
     * Cross-lingual controls. Every hard cross-lingual query above is ALSO a paraphrase or carries
     * a distractor, so a failure there cannot tell "the languages do not align" apart from "the
     * query was hard". These four are direct translations of wording the note actually uses, which
     * isolates the one variable: does the embedder put Bulgarian and English near each other?
     */
    val crossLingualControlQueries: List<GoldenQuery> = listOf(
        GoldenQuery(
            "ядрото уби процеса заради липса на памет",
            mapOf("ops/oom-killer.md" to 3),
            "control: literal translation of the note's own words, ZERO shared tokens — pure language alignment",
        ),
        GoldenQuery(
            "тримесечен преглед на дяловете във фонда",
            mapOf("finance/index-fund-drift.md" to 3),
            "control: literal translation of the note title, ZERO shared tokens — pure language alignment",
        ),
        GoldenQuery(
            "ротация на docker логовете с max-size",
            mapOf("ops/docker-log-rotation.md" to 3),
            "control: cross-lingual but with shared Latin tokens (docker, max-size) — the keyword leg can help",
        ),
        GoldenQuery(
            "renew the certificate with certbot and reload nginx",
            mapOf("ops/certbot-podnovyavane.md" to 3),
            "control: EN into a BG note, shared tokens (certbot, nginx) — reverse direction with lexical support",
        ),
    )

    /**
     * Query in one language, answer written in the other — the capability a multilingual embedder
     * is chosen for. Every one of these is unreachable by the lexical leg on its own.
     */
    val crossLingualQueries: List<GoldenQuery> = listOf(
        GoldenQuery(
            "процесът изчезва през нощта без грешка и без следа",
            mapOf("ops/oom-killer.md" to 3),
            "BG query, EN note, and no shared token even after transliteration — semantic leg only",
        ),
        GoldenQuery(
            "как да върна инвестициите към зададените дялове",
            mapOf("finance/index-fund-drift.md" to 3),
            "BG query, EN note, and a paraphrase on top: the note never says rebalance",
        ),
        GoldenQuery(
            "докер контейнерите напълниха диска",
            mapOf("ops/docker-log-rotation.md" to 3),
            "BG query, EN note; докер is transliterated so even a lexical fallback cannot match",
        ),
        GoldenQuery(
            "отмяната на корутина не спира цикъла веднага",
            mapOf("dev/kotlin-coroutine-cancellation.md" to 3, "dev/kotlin-coroutines-scratch.md" to 1),
            "BG query into the EN near-duplicate pair — cross-lingual and distractor at once",
        ),
        GoldenQuery(
            "match a word except when a certain abbreviation follows it",
            mapOf("dev/regex-lookahead.md" to 3),
            "reverse direction: EN query, BG note, described entirely in Bulgarian terms",
        ),
        GoldenQuery(
            "video calls freeze every evening but are fine in the morning",
            mapOf("ops/wifi-router-problem.md" to 3),
            "reverse direction, plus the note describes the cause (channel overlap) not the symptom",
        ),
    ) + crossLingualControlQueries

    /**
     * The full golden set. Declared last on purpose: an object initialises its properties in
     * declaration order, so referencing the three subsets from above would read them as null.
     */
    val queries: List<GoldenQuery> = englishQueries + bulgarianQueries + crossLingualQueries
}
