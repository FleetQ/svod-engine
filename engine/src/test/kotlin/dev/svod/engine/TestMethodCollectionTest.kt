package dev.svod.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards against silently un-run tests.
 *
 * A Kotlin `@Test` whose body is an expression returning something other than `Unit` compiles to a
 * method with a non-void return type, and JUnit Jupiter **does not collect it** — no failure, no
 * skip, no warning. It simply never runs, and the suite still reports green.
 *
 * This bit twice. Most recently `AgentAdminTest` declared 10 tests and ran 5: every one of the five
 * ended in `assertFailsWith`, which returns the Throwable, so `= runBlocking { … }` returned
 * `Throwable` instead of `Unit`. The five that never executed were all the NEGATIVE agent-auth
 * cases (duplicate id, invalid id pattern, raw token instead of a Secrets ref, delete-unknown,
 * update-unknown) — precisely the assertions you would least want silently absent.
 *
 * The fix at each site is `fun name(): Unit = runBlocking { … }`. This test makes the class of
 * mistake impossible to reintroduce unnoticed: it reflects over every compiled test class and
 * fails if any `@Test` method has a non-void return type.
 */
class TestMethodCollectionTest {

    @Test
    fun `every @Test method returns Unit, so JUnit actually collects it`() {
        val root = testClassesRoot()
        val loader = javaClass.classLoader
        val offenders = mutableListOf<String>()
        val unreadable = mutableListOf<String>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "class" }) {
            val className = file.relativeTo(root).path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
            // initialize=false: never run a test class's static initialisers just to inspect it.
            val cls = try {
                Class.forName(className, false, loader)
            } catch (t: Throwable) {
                unreadable += "$className (${t.javaClass.simpleName})"
                continue
            }
            val methods = try {
                cls.declaredMethods
            } catch (t: Throwable) {
                unreadable += "$className (${t.javaClass.simpleName} reading methods)"
                continue
            }
            for (m in methods) {
                val isTest = m.annotations.any { it.annotationClass.qualifiedName == JUPITER_TEST }
                if (isTest && m.returnType != Void.TYPE) {
                    offenders += "${cls.name}.${m.name}(): returns ${m.returnType.simpleName}, so JUnit skips it"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "These @Test methods are silently never collected — declare them `(): Unit = …`:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
        // A class we cannot introspect is a hole in this guard, not a pass.
        assertTrue(
            unreadable.isEmpty(),
            "Could not introspect these test classes, so they are unguarded:\n" +
                unreadable.joinToString("\n") { "  - $it" },
        )
    }

    /** The compiled test-classes directory this very class was loaded from. */
    private fun testClassesRoot(): File {
        val src = javaClass.protectionDomain.codeSource
            ?: error("no code source for ${javaClass.name}; cannot locate the test classes")
        return File(src.location.toURI()).also {
            assertTrue(it.isDirectory, "expected a test-classes directory, got ${it.path}")
        }
    }

    private companion object {
        // kotlin.test.Test is a typealias for the Jupiter annotation on the JVM, so this is what
        // is actually present at runtime regardless of which import a test used.
        const val JUPITER_TEST = "org.junit.jupiter.api.Test"
    }
}
