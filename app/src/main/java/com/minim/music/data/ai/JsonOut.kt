package com.minim.music.data.ai

/**
 * v5.2.0: a tiny JSON writer for the Gemini requests. Reading uses the app's own
 * MiniJson, so the chat code needs no new library and runs in plain unit tests.
 */
object JsonOut {

    fun write(value: Any?): String = StringBuilder().also { put(it, value) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isFinite()) sb.append(trimNum(v)) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(trimNum(v.toDouble())) else sb.append("null")
            is Number -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    put(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    put(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> put(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun trimNum(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

internal fun Any?.asList(): List<Any?>? = this as? List<Any?>
