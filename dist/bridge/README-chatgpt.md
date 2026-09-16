# Свързване на ChatGPT със Svod (MCP)

Два различни пътя, за два различни абонамента. Първият е готов и работи.

---

## Път 1 — ChatGPT Desktop през вградения Codex (обикновен абонамент) ✅

ChatGPT.app носи Codex вътре в себе си
(`/Applications/ChatGPT.app/Contents/Resources/codex`) и той чете
`~/.codex/config.toml`. Локалните MCP сървъри оттам работят директно — без тунел,
без platform.openai.com, без enterprise план.

Регистрирано е като:

    [mcp_servers.svod]
    command = "~/svod-engine/dist/bridge/svod-mcp-chatgpt.sh"

Проверка: `/Applications/ChatGPT.app/Contents/Resources/codex mcp get svod`

**Рестартирай ChatGPT.app**, за да прочете конфигурацията. После инструментите на
Svod се появяват в Codex сесия вътре в приложението.

Ако нещо не тръгне:

    /Applications/ChatGPT.app/Contents/Resources/codex mcp list
    printf '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}\n' \
      | ~/htdocs/svod/dist/bridge/svod-mcp-chatgpt.sh

Резервно копие на конфигурацията: `~/.codex/config.toml.bak-*`

---

## Път 2 — ChatGPT connector през OpenAI Secure Tunnel (само Business/Enterprise/Edu)

Това е пътят за уеб ChatGPT и споделяне в workspace. Custom MCP connectors се
дават само на Business / Enterprise / Edu — на Plus/Pro опцията „Create" я няма.
Подготвено е, но не е завършено.

Готово: `tunnel-client` v0.0.14 в `~/.local/bin` (sha256 сверен), профил
`~/.config/tunnel-client/svod-chatgpt.yaml` (health на 127.0.0.1:8099, защото
8080 е зает от OrbStack), пускач `svod-chatgpt-tunnel.sh`.

Остава:
1. Тунел от https://platform.openai.com/settings/organization/tunnels → сложи
   `tunnel_...` id-то в YAML-а на мястото на нулите.
2. Runtime ключ с права Tunnels Read + Use → в 1Password като
   `AI Agent / OpenAI Tunnel Runtime / credential`. Ключът в `AI Agent / OpenAI API`
   не става, няма management scope.
3. `~/htdocs/svod/dist/bridge/svod-chatgpt-tunnel.sh` и го дръж жив.
4. ChatGPT web → Settings → Apps → Create → Tunnel → Scan Tools → Create.

`tunnel-client doctor --profile svod-chatgpt` минава всичко освен ключа.

---

## Агентът

| | |
|---|---|
| id | `chatgpt-local` — „ChatGpt Desktop" |
| роля | WRITE (може да пише в трезора) |
| трезор | `personal` |
| токен | `~/Library/Application Support/Svod/agent-chatgpt-local-token.secret` (0600) |
| промпт | записан в конфигурацията на агента |

Роля и трезори се сменят от Svod → Settings → LLM Access → ⋯ → Edit.

## Файловете тук

- `svod-mcp-bridge.mjs` — stdio↔HTTP мост към `http://127.0.0.1:7620/mcp`
- `svod-mcp-chatgpt.sh` — обвивка, която чете токена от 0600 файла и пуска моста
- `svod-chatgpt-tunnel.sh` — пуска тунела (само за път 2)

Svod трябва да е пуснат. Ако рестартираш engine-а, мостът сам преинициализира
сесията за под секунда.

---

## Другите MCP сървъри в ChatGPT Desktop

Освен Svod, в `~/.codex/config.toml` са добавени:

    [mcp_servers.harbormaster]
    command = "~/.config/harbormaster/start-stdio.sh"

    [mcp_servers.lattice]
    command = "~/lattice/apps/desktop-macos/Scripts/lattice-mcp-chatgpt.sh"

Harbormaster тръгва директно — същият пускач, който ползва Claude Code.
Lattice е HTTP на `127.0.0.1:8765` и иска bearer токен, затова минава през
обвивка около `lattice-mcp-bridge.mjs`; токенът стои в
`~/.config/lattice/mcp-token.secret` (0600), не в конфигурацията.

Проверено преди регистрацията: harbormaster 17 инструмента, lattice 33.

**Внимание с Lattice.** През него ChatGPT получава браузър и одобрителната порта
на 127.0.0.1:7912 — тоест може да задейства действия с последствия. Ако не го
искаш това, махни го: `codex mcp remove lattice`.
