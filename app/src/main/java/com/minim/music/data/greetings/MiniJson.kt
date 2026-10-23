package com.minim.music.data.greetings

/**
 * v4.9.6: a tiny, strict JSON reader for the remote greetings file.
 *
 * Pure Kotlin (works the same in unit tests and on the phone, no android.jar stubs) and defensive:
 * it never recurses deeper than [MAX_DEPTH] and refuses input longer than [MAX_CHARS], so a broken or
 * hostile file can only ever make [parse] throw [MiniJsonException], which the caller catches.
 *
 * Objects come back as Map<String, Any?> (keys in file order), arrays as List<Any?>, strings as
 * String, numbers as Long when they are whole and fit, else Double, plus Boolean and null.
 */
object MiniJson {

    const val MAX_DEPTH = 16
    const val MAX_CHARS = 256 * 1024

    class MiniJsonException(message: String) : Exception(message)

    fun parse(text: String): Any? {
        if (text.length > MAX_CHARS) throw MiniJsonException("too long")
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw MiniJsonException("trailing data at ${p.pos}")
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') pos++ else break
            }
        }

        private fun fail(msg: String): Nothing = throw MiniJsonException("$msg at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, pos)) fail("bad literal")
            pos += w.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("expected key")
                val key = str()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                map[key] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("unexpected end")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("unexpected end")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd() || s[pos] !in '0'..'9') fail("bad number")
            while (!atEnd() && s[pos] in '0'..'9') pos++
            var whole = true
            if (!atEnd() && s[pos] == '.') {
                whole = false
                pos++
                if (atEnd() || s[pos] !in '0'..'9') fail("bad number")
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                whole = false
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                if (atEnd() || s[pos] !in '0'..'9') fail("bad number")
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (whole) t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull() ?: fail("bad number")
        }
    }
}
