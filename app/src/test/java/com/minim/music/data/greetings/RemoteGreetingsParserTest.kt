package com.minim.music.data.greetings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGreetingsParserTest {

    private fun file(vararg entries: String, version: String = "1") =
        """{ "version": $version, "greetings": [ ${entries.joinToString(",")} ] }"""

    @Test
    fun parsesAnytimeAndOccasionEntries() {
        val f = RemoteGreetingsParser.parse(
            file(
                """{ "text": "Volume up.\nWorries down." }""",
                """{ "name": "Diwali", "text": "Happy Diwali!\nLights up.", "from": "11-06", "to": "11-10", "day": "11-08", "year": 2026 }"""
            )
        )!!
        assertEquals(1, f.version)
        assertEquals(1, f.anytime.size)
        assertEquals(1, f.occasions.size)
        assertEquals(0, f.skipped)
        assertEquals("Volume up.", f.anytime[0].line1)
        assertEquals("Worries down.", f.anytime[0].line2)
        val d = f.occasions[0]
        assertEquals("Happy Diwali!", d.line1)
        assertEquals(DateRange(11, 6, 11, 10, 2026, 11, 8), d.range)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val f = RemoteGreetingsParser.parse(
            """{ "version": 1, "author": "me", "extra": {"a": [1, 2]}, "greetings": [
                { "text": "Hi.\nThere.", "emoji": true, "weight": 9, "tags": ["x"] } ] }"""
        )!!
        assertEquals(1, f.anytime.size)
        assertEquals(0, f.skipped)
    }

    @Test
    fun badJsonIsRejectedWithoutThrowing() {
        val bad = listOf(
            null, "", "   ", "not json", "{", "[]", "42", "\"text\"", "null",
            """{ "version": 1 }""",                                   // no greetings list
            """{ "version": 1, "greetings": {} }""",                  // not a list
            """{ "version": 1, "greetings": [ { "text": "a" }, ] }""", // trailing comma
            """{ "version": "one", "greetings": [] }""",              // nonsense version
            """{ "version": 0, "greetings": [] }""",
            """{ "version": 1.5, "greetings": [] }""",
            """{ "version": 1, "greetings": [] } trailing""",
            """{ "version": 1, "greetings": [ { "text": "unterminated } ] }""",
            "[".repeat(10_000) + "]".repeat(10_000),                   // far too deep
            "{\"greetings\": [" + "{\"text\":\"x\"},".repeat(30_000) + "{}]}" // far too long
        )
        for (b in bad) assertNull("should reject: ${b?.take(40)}", RemoteGreetingsParser.parse(b))
    }

    @Test
    fun missingVersionIsOkAndNewerVersionStillReadsKnownFields() {
        assertEquals(1, RemoteGreetingsParser.parse("""{ "greetings": [ { "text": "Hi.\nYou." } ] }""")!!.version)
        val newer = RemoteGreetingsParser.parse(file("""{ "text": "Hi.\nYou." }""", version = "2"))!!
        assertEquals(2, newer.version)
        assertEquals(1, newer.anytime.size)
    }

    @Test
    fun badEntriesAreSkippedGoodOnesKept() {
        val long = "x".repeat(RemoteGreetingsParser.MAX_TEXT + 1)
        val f = RemoteGreetingsParser.parse(
            file(
                """{ "text": "Good one.\nKeep me." }""",
                """{ "text": "" }""",
                """{ "text": "   \n  " }""",
                """{ "text": "$long" }""",
                """{ "text": "${"y".repeat(45)}\nshort" }""",            // a line too long
                """{ "text": 42 }""",
                """{ "words": "no text field" }""",
                """"just a string"""",
                """null""",
                """[1, 2]""",
                """{ "text": "Bad date", "from": "02-30" }""",
                """{ "text": "Bad month", "from": "13-01" }""",
                """{ "text": "Zero day", "from": "05-00" }""",
                """{ "text": "April 31", "from": "04-01", "to": "04-31" }""",
                """{ "text": "Not a date", "from": "Diwali" }""",
                """{ "text": "Number date", "from": 1106 }""",
                """{ "text": "No leap day", "from": "02-29", "year": 2027 }""",
                """{ "text": "Bad year", "from": "01-01", "year": "soon" }""",
                """{ "text": "Silly year", "from": "01-01", "year": 99999 }""",
                """{ "text": "To without from", "to": "01-05" }""",
                """{ "text": "Good occasion.\nKeep me too.", "from": "12-25" }"""
            )
        )!!
        assertEquals(1, f.anytime.size)
        assertEquals(1, f.occasions.size)
        assertEquals(19, f.skipped)
    }

    @Test
    fun leapDayWithoutYearOrInLeapYearIsFine() {
        assertNotNull(RemoteGreetingsParser.parseEntry(mapOf("text" to "Leap!", "from" to "02-29")))
        assertNotNull(RemoteGreetingsParser.parseEntry(mapOf("text" to "Leap!", "from" to "02-29", "year" to 2028L)))
        // A range from 2027 that crosses into the leap year 2028 may end on 02-29... of 2028
        assertNotNull(RemoteGreetingsParser.parseEntry(mapOf("text" to "Long one", "from" to "12-01", "to" to "02-29", "year" to 2027L)))
        assertNull(RemoteGreetingsParser.parseEntry(mapOf("text" to "Long one", "from" to "12-01", "to" to "02-29", "year" to 2026L)))
    }

    @Test
    fun mainDayOutsideTheRangeFallsBackToTheFirstDay() {
        val g = RemoteGreetingsParser.parseEntry(mapOf("text" to "Hi", "from" to "03-03", "to" to "03-04", "day" to "07-01"))!!
        assertEquals(DateRange(3, 3, 3, 4, null, 3, 3), g.range)
        val single = RemoteGreetingsParser.parseEntry(mapOf("text" to "Hi", "from" to "8-15"))!!
        assertEquals(DateRange(8, 15, 8, 15), single.range)
    }

    @Test
    fun textIsTidiedAndSplit() {
        assertEquals("One more song." to "It's never just one.", RemoteGreetingsParser.cleanLines("  One more song.   It's never just one. "))
        assertEquals("Hi" to "there", RemoteGreetingsParser.cleanLines("Hi\r\n\tthere"))
        assertEquals("A" to "B C", RemoteGreetingsParser.cleanLines("A\nB\nC"))
        assertEquals("Hey!" to "", RemoteGreetingsParser.cleanLines("Hey!"))
        assertEquals("Tab" to "inside", RemoteGreetingsParser.cleanLines("Tab\u0007\ninside"))
        assertNull(RemoteGreetingsParser.cleanLines("\u0000\u0001"))
        val (l1, l2) = RemoteGreetingsParser.cleanLines("Headphones on and the whole world off")!!
        assertTrue(l1.isNotEmpty() && l2.isNotEmpty())
        assertEquals("Headphones on and the whole world off", "$l1 $l2")
    }

    @Test
    fun namePlaceholder() {
        val g = RemoteGreeting("Happy Diwali, {name}!", "Make it sparkle.")
        assertTrue(g.usesName)
        assertEquals("Happy Diwali, Gaurab!" to "Make it sparkle.", g.linesFor("Gaurab"))
        assertNull(g.linesFor(""))
        assertNull(g.linesFor("   "))
        val plain = RemoteGreeting("Hi.", "You.")
        assertFalse(plain.usesName)
        assertEquals("Hi." to "You.", plain.linesFor(""))
    }

    @Test
    fun entryCountIsCapped() {
        val many = (1..(RemoteGreetingsParser.MAX_ENTRIES + 20)).map { """{ "text": "Line $it" }""" }
        val f = RemoteGreetingsParser.parse(file(*many.toTypedArray()))!!
        assertEquals(RemoteGreetingsParser.MAX_ENTRIES, f.size)
        assertEquals(20, f.skipped)
    }

    @Test
    fun seedFileIsValidAndFullyUsed() {
        val text = javaClass.classLoader!!.getResource("greetings_seed.json")!!.readText()
        val f = RemoteGreetingsParser.parse(text)!!
        assertEquals(0, f.skipped)
        assertTrue(f.anytime.size >= 5)
        assertTrue(f.occasions.size >= 40)
        // Diwali 2026 is on 8 November, 2027 on 29 October
        val diwali26 = f.occasions.filter { it.range!!.isMainDay(2026, 11, 8) }
        val diwali27 = f.occasions.filter { it.range!!.isMainDay(2027, 10, 29) }
        assertTrue(diwali26.any { it.line1.contains("Diwali") })
        assertTrue(diwali27.any { it.line1.contains("Diwali") })
        // New Year's Day greetings show on 1 January of any year, Rongali Bihu on 15 April
        assertTrue(f.occasions.any { it.range!!.matches(2031, 1, 1) && it.line1.contains("New Year") })
        assertTrue(f.occasions.any { it.range!!.isMainDay(2027, 4, 15) && it.line1.contains("Bihu") })
    }
}
