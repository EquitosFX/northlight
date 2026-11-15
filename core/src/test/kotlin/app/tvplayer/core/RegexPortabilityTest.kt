package app.tvplayer.core

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Android's regex engine (ICU) is stricter than desktop Java's, which these tests run on: a bare
 * "{" or "}" that isn't part of a {n,m} count is a syntax error on the TV but fine here. This scans
 * every pattern in the app's source so such a slip fails the build instead of crashing the app.
 */
class RegexPortabilityTest {
    @Test
    fun bracesInPatternsAreEscapedOrCounts() {
        val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java")).filter { it.exists() }
        if (roots.isEmpty()) fail("No source folders found")
        val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
        val problems = ArrayList<String>()
        for (root in roots) {
            root.walk().filter { it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    for (m in literal.findAll(line)) {
                        val pattern = m.groupValues[1]
                        val stripped = pattern
                            .replace("\\\\", "")
                            .replace("\\{", "").replace("\\}", "")
                            .replace(Regex("\\\\p\\{[A-Za-z]+\\}"), "")
                            .replace(Regex("\\{\\d+(,\\d*)?\\}"), "")
                            .replace(Regex("\\$\\{?[A-Za-z_][A-Za-z_0-9]*\\}?"), "") // Kotlin string templates
                        if ('{' in stripped || '}' in stripped) problems += "${file.name}:${i + 1}: $pattern"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail("Unescaped braces (crash on Android):\n" + problems.joinToString("\n"))
    }
}
