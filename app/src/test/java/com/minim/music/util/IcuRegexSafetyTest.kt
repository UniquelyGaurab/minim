package com.minim.music.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 5.3.1: Minim crashed on Android 9 because a regex that desktop Java (where these tests run)
 * accepts was rejected by Android's ICU regex engine. This test finds EVERY regex literal in the app
 * source by itself (so a new one can't be forgotten) and checks it two ways:
 *
 *  1. [IcuRegexRules]: a strict check that rejects anything ICU rejects or reads differently from
 *     desktop Java (bare `]` / `}` / `{`, `]` first in a class, `[` inside a class, `&&`, Java-only
 *     flags, escapes and properties). Always runs.
 *  2. The real ICU engine (the one Android uses), through regex/icu_regex_check.py, when the
 *     machine has python3 and libicu. Skipped otherwise.
 */
class IcuRegexSafetyTest {

    private val sourceRoot: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private val patterns: List<RegexSources.Found> by lazy { RegexSources.scan(sourceRoot) }

    @Test fun `finds the app's regexes and every one is a plain literal`() {
        assertTrue("only ${patterns.size} regexes found, the scanner is broken", patterns.size >= 55)
        val dynamic = patterns.filter { it.pattern == null }.map { it.where }
        assertEquals("build these from a string literal so they can be checked: $dynamic", emptyList<String>(), dynamic)
    }

    @Test fun `no regex is built outside SafeRegex`() {
        val raw = sourceRoot.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.name != "SafeRegex.kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    if (Regex("""(?<![\w.])Regex\(|Pattern\.compile\(|\.toRegex\(|\.toPattern\(""").containsMatchIn(code)) {
                        "${f.name}:${i + 1}"
                    } else null
                }
            }.toList()
        assertEquals("use SafeRegex.of / SafeRegex.pattern", emptyList<String>(), raw)
    }

    @Test fun `every app regex is ICU safe and compiles`() {
        val bad = mutableListOf<String>()
        for (p in patterns) {
            val pattern = p.pattern ?: continue
            IcuRegexRules.problems(pattern).forEach { bad.add("${p.where}: $it  in  $pattern") }
            try {
                Regex(pattern)
            } catch (e: Exception) {
                bad.add("${p.where}: doesn't compile: ${e.message}")
            }
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
    }

    @Test fun `the strict check catches the 5_3_0 crash and other ICU traps`() {
        // the exact pattern that crashed 5.3.0 on Android 9
        assertFalse(IcuRegexRules.problems("""\([^)]*\)|\[[^]]*]|\{[^}]*}|（[^）]*）|【[^】]*】""").isEmpty())
        assertFalse(IcuRegexRules.problems("""[^]]""").isEmpty()) // ] first in a class
        assertFalse(IcuRegexRules.problems("""a]""").isEmpty()) // bare ]
        assertFalse(IcuRegexRules.problems("""a}""").isEmpty()) // bare }
        assertFalse(IcuRegexRules.problems("""a{""").isEmpty()) // { that isn't a count
        assertFalse(IcuRegexRules.problems("""{2}""").isEmpty()) // count with nothing before it
        assertFalse(IcuRegexRules.problems("""[a[b]]""").isEmpty()) // nested set in ICU
        assertFalse(IcuRegexRules.problems("""[a-z&&[^e]]""").isEmpty())
        assertFalse(IcuRegexRules.problems("""(?U)\w+""").isEmpty()) // Java-only flag
        assertFalse(IcuRegexRules.problems("""\p{javaLowerCase}""").isEmpty())
        assertFalse(IcuRegexRules.problems("""\y""").isEmpty()) // unknown escape
        assertFalse(IcuRegexRules.problems("""[abc""").isEmpty())
        // and lets the safe forms through
        assertEquals(
            emptyList<String>(),
            IcuRegexRules.problems("""\([^)]*\)|\[[^\]]*\]|\{[^\}]*\}|（[^）]*）|【[^】]*】""")
        )
        assertEquals(emptyList<String>(), IcuRegexRules.problems("""(?i)^\s*\d{1,4}[.):\]-]\p{L}+(?=-|$)\b"""))
    }

    @Test fun `the real ICU engine accepts every app regex`() {
        val script = listOf(File("src/test/resources/regex/icu_regex_check.py"), File("app/src/test/resources/regex/icu_regex_check.py"))
            .firstOrNull { it.isFile }
        assumeTrue("checker script missing", script != null)
        val hasIcu = listOf("/usr/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu").any { dir ->
            File(dir).listFiles()?.any { it.name.startsWith("libicui18n.so.") } == true
        }
        assumeTrue("no libicu on this machine", hasIcu)
        val proc = try {
            ProcessBuilder("python3", script!!.path, sourceRoot.path).redirectErrorStream(true).start()
        } catch (e: Exception) {
            null
        }
        assumeTrue("no python3 on this machine", proc != null)
        val out = proc!!.inputStream.bufferedReader().readText()
        assertTrue("ICU check timed out", proc.waitFor(60, TimeUnit.SECONDS))
        assumeTrue("ICU not loadable here: $out", !out.contains("ICU not found"))
        assertEquals(out, 0, proc.exitValue())
        assertTrue(out, Regex("""ICU \d+: (\d+) patterns, 0 rejected""").find(out)!!.groupValues[1].toInt() >= 55)
    }

    @Test fun `a rejected pattern falls back to one that never matches instead of throwing`() {
        val before = SafeRegex.failures
        val r = SafeRegex.of("(unclosed")
        assertEquals(before + 1, SafeRegex.failures)
        assertFalse(r.containsMatchIn("(unclosed anything at all"))
        assertEquals("Song (Live)", r.replace("Song (Live)", ""))
        val p = SafeRegex.pattern("[abc")
        assertFalse(p.matcher("[abc").find())
    }
}

/** Finds the string literal given to every SafeRegex.of / SafeRegex.pattern / Regex( / Pattern.compile( call. */
object RegexSources {

    data class Found(val where: String, val pattern: String?)

    private val CALL = Regex("""(?<![\w.])Regex\(|Pattern\.compile\(|SafeRegex\.of\(|SafeRegex\.pattern\(""")
    private val RAW_TEMPLATE = Regex("""\$\{[^}]*}|\$[A-Za-z_][A-Za-z0-9_]*""")

    fun scan(root: File): List<Found> = root.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") && it.name != "SafeRegex.kt" }
        .sortedBy { it.path }
        .flatMap { f ->
            val src = f.readText()
            CALL.findAll(src).map { m ->
                val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                Found("${f.name}:$line", literalAt(src, m.range.last + 1))
            }
        }.toList()

    /** The (possibly `+`-joined) string literal starting at [start], templates as "1"; null if none. */
    fun literalAt(src: String, start: Int): String? {
        val sb = StringBuilder()
        var i = start
        var any = false
        while (true) {
            while (i < src.length && src[i].isWhitespace()) i++
            if (src.startsWith("\"\"\"", i)) {
                var j = src.indexOf("\"\"\"", i + 3)
                while (j + 3 < src.length && src[j + 3] == '"') j++
                sb.append(RAW_TEMPLATE.replace(src.substring(i + 3, j).replace("\${'$'}", "\u0000"), "1").replace("\u0000", "$"))
                i = j + 3
            } else if (i < src.length && src[i] == '"') {
                var j = i + 1
                while (src[j] != '"') {
                    when {
                        src[j] == '\\' -> {
                            val n = src[j + 1]
                            when (n) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                'b' -> sb.append('\b')
                                'u' -> { sb.append(src.substring(j + 2, j + 6).toInt(16).toChar()); j += 4 }
                                else -> sb.append(n)
                            }
                            j += 2
                        }
                        src[j] == '$' && (src.getOrNull(j + 1)?.let { it == '{' || it.isLetter() || it == '_' } == true) -> {
                            sb.append('1')
                            if (src[j + 1] == '{') {
                                j = src.indexOf('}', j) + 1
                            } else {
                                j++
                                while (src[j].isLetterOrDigit() || src[j] == '_') j++
                            }
                        }
                        else -> { sb.append(src[j]); j++ }
                    }
                }
                i = j + 1
            } else {
                break
            }
            any = true
            var k = i
            while (k < src.length && src[k].isWhitespace()) k++
            if (k < src.length && src[k] == '+') { i = k + 1; continue }
            break
        }
        return if (any) sb.toString() else null
    }
}

/**
 * A strict reading of a regex: everything ICU (Android) rejects, or reads differently from desktop
 * Java, is a problem. Literal brackets and braces must always be escaped.
 */
object IcuRegexRules {

    // Escapes both engines know. Android sets UREGEX_ERROR_ON_UNKNOWN_ESCAPES, so a letter escape
    // that isn't here (like \y) fails on the phone.
    private const val KNOWN_LETTER_ESCAPES = "dDsSwWbBAzZGnrtfaeuxpPQEhHvVRXk0cN"
    private val GENERAL_CATEGORY = Regex("""\^?(L|Lu|Ll|Lt|Lm|Lo|M|Mn|Mc|Me|N|Nd|Nl|No|P|Pc|Pd|Ps|Pe|Pi|Pf|Po|S|Sm|Sc|Sk|So|Z|Zs|Zl|Zp|C|Cc|Cf|Co|Cn)""")
    private val COUNT = Regex("""\{\d+(,\d*)?}""")
    private val INLINE_FLAGS = Regex("""\(\?[imsx]*(-[imsx]+)?[:)]""")

    fun problems(p: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        var canQuantify = false
        while (i < p.length) {
            val c = p[i]
            when (c) {
                '\\' -> {
                    val (next, problem) = escape(p, i)
                    problem?.let { out.add("$it at $i") }
                    i = next
                    canQuantify = true
                    continue
                }
                '[' -> {
                    val (next, problems) = charClass(p, i)
                    out.addAll(problems)
                    i = next
                    canQuantify = true
                    continue
                }
                ']' -> out.add("unescaped ] at $i")
                '}' -> out.add("unescaped } at $i")
                '{' -> {
                    val m = COUNT.matchAt(p, i)
                    if (m == null) {
                        out.add("{ that isn't a {n,m} count at $i")
                    } else {
                        if (!canQuantify) out.add("count with nothing to repeat at $i")
                        i = m.range.last + 1
                        // a lazy/possessive marker may follow
                        if (i < p.length && (p[i] == '?' || p[i] == '+')) i++
                        canQuantify = false
                        continue
                    }
                }
                '(' -> {
                    if (p.startsWith("(?", i)) {
                        val ok = listOf("(?:", "(?=", "(?!", "(?<=", "(?<!", "(?>").any { p.startsWith(it, i) } ||
                            Regex("""\(\?<[A-Za-z][A-Za-z0-9]*>""").matchAt(p, i) != null ||
                            INLINE_FLAGS.matchAt(p, i) != null
                        if (!ok) out.add("group or flag ICU doesn't support at $i")
                    }
                    canQuantify = false
                }
                '|' -> canQuantify = false
                '*', '+', '?' -> if (!canQuantify && !(c == '?' && i > 0 && p[i - 1] == '(')) {
                    // after another quantifier this is lazy/possessive, which both engines allow
                    if (i == 0 || p[i - 1] !in "*+?}") out.add("$c with nothing to repeat at $i")
                }
                else -> canQuantify = true
            }
            if (c == ')') canQuantify = true
            if (c == '*' || c == '+' || c == '?') canQuantify = false
            i++
        }
        return out
    }

    /** One escape starting at [i] (the backslash): where it ends, and a problem if any. */
    private fun escape(p: String, i: Int): Pair<Int, String?> {
        if (i + 1 >= p.length) return p.length to "trailing backslash"
        val n = p[i + 1]
        return when {
            n == 'p' || n == 'P' -> {
                if (i + 2 < p.length && p[i + 2] == '{') {
                    val end = p.indexOf('}', i + 3)
                    if (end < 0) return p.length to "unclosed \\p{"
                    val name = p.substring(i + 3, end)
                    (end + 1) to (if (GENERAL_CATEGORY.matches(name)) null else "property \\$n{$name} may not exist in ICU")
                } else {
                    val name = p.getOrNull(i + 2)?.toString().orEmpty()
                    (i + 3) to (if (GENERAL_CATEGORY.matches(name)) null else "bad \\$n$name")
                }
            }
            n == 'Q' -> {
                val end = p.indexOf("\\E", i + 2)
                (if (end < 0) p.length else end + 2) to null
            }
            n == 'u' -> (i + 6) to (if (Regex("[0-9a-fA-F]{4}").matchAt(p, i + 2) != null) null else "bad \\u escape")
            n == 'x' -> {
                if (p.getOrNull(i + 2) == '{') (p.indexOf('}', i + 3).let { if (it < 0) p.length else it + 1 }) to null
                else (i + 4) to null
            }
            n == 'N' -> (i + 2) to "\\N{name} is ICU-only, write the character"
            n == 'c' -> (i + 3) to null
            n.isLetter() && n !in KNOWN_LETTER_ESCAPES -> (i + 2) to "unknown escape \\$n"
            else -> (i + 2) to null
        }
    }

    /** A character class starting at [start] ('['): where it ends, and its problems. */
    private fun charClass(p: String, start: Int): Pair<Int, List<String>> {
        val out = mutableListOf<String>()
        var i = start + 1
        if (i < p.length && p[i] == '^') i++
        if (i < p.length && p[i] == ']') {
            out.add("] first in a class at $i (escape it as \\])")
            i++
        }
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    val (next, problem) = escape(p, i)
                    problem?.let { out.add("$it at $i") }
                    i = next
                    continue
                }
                c == ']' -> return (i + 1) to out
                c == '[' -> out.add("[ inside a class at $i (ICU reads it as a nested set, escape it)")
                c == '&' && p.getOrNull(i + 1) == '&' -> out.add("&& inside a class at $i")
            }
            i++
        }
        out.add("class opened at $start is never closed")
        return p.length to out
    }
}
