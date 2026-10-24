package org.blackoutrelay.verify

/**
 * Minimal JSON parser for the identity file and verdict output. Handles the
 * standard scalar/container types we need (object, array, string, number,
 * boolean, null). Not a general-purpose library; input is our own files.
 */
object Json {

    sealed interface Value
    data class Str(val s: String) : Value
    data class Num(val v: Long) : Value
    data class Bool(val b: Boolean) : Value
    data object Null : Value
    data class Obj(val fields: Map<String, Value>) : Value
    data class Arr(val items: List<Value>) : Value

    class JsonException(message: String) : IllegalArgumentException(message)

    fun parse(s: String): Value {
        val p = intArrayOf(0)
        val v = parseValue(s, p)
        skipWs(s, p)
        if (p[0] != s.length) throw JsonException("trailing characters at ${p[0]}")
        return v
    }

    private fun parseValue(s: String, p: IntArray): Value {
        skipWs(s, p)
        if (p[0] >= s.length) throw JsonException("unexpected end")
        return when (s[p[0]]) {
            '{' -> parseObj(s, p)
            '[' -> parseArr(s, p)
            '"' -> Str(parseString(s, p))
            't' -> { expect(s, p, "true"); Bool(true) }
            'f' -> { expect(s, p, "false"); Bool(false) }
            'n' -> { expect(s, p, "null"); Null }
            else -> parseNumber(s, p)
        }
    }

    private fun parseObj(s: String, p: IntArray): Obj {
        p[0]++ // '{'
        val map = LinkedHashMap<String, Value>()
        skipWs(s, p)
        if (p[0] < s.length && s[p[0]] == '}') { p[0]++; return Obj(map) }
        while (true) {
            skipWs(s, p)
            if (p[0] >= s.length || s[p[0]] != '"') throw JsonException("expected key")
            val key = parseString(s, p)
            skipWs(s, p)
            if (p[0] >= s.length || s[p[0]] != ':') throw JsonException("expected ':'")
            p[0]++
            map[key] = parseValue(s, p)
            skipWs(s, p)
            if (p[0] >= s.length) throw JsonException("unterminated object")
            when (s[p[0]]) {
                ',' -> p[0]++
                '}' -> { p[0]++; return Obj(map) }
                else -> throw JsonException("expected ',' or '}'")
            }
        }
    }

    private fun parseArr(s: String, p: IntArray): Arr {
        p[0]++ // '['
        val items = ArrayList<Value>()
        skipWs(s, p)
        if (p[0] < s.length && s[p[0]] == ']') { p[0]++; return Arr(items) }
        while (true) {
            items.add(parseValue(s, p))
            skipWs(s, p)
            if (p[0] >= s.length) throw JsonException("unterminated array")
            when (s[p[0]]) {
                ',' -> p[0]++
                ']' -> { p[0]++; return Arr(items) }
                else -> throw JsonException("expected ',' or ']'")
            }
        }
    }

    private fun parseString(s: String, p: IntArray): String {
        p[0]++ // '"'
        val sb = StringBuilder()
        while (true) {
            if (p[0] >= s.length) throw JsonException("unterminated string")
            val c = s[p[0]++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (p[0] >= s.length) throw JsonException("bad escape")
                    when (val e = s[p[0]++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (p[0] + 4 > s.length) throw JsonException("bad unicode escape")
                            sb.append(s.substring(p[0], p[0] + 4).toInt(16).toChar())
                            p[0] += 4
                        }
                        else -> throw JsonException("bad escape char")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(s: String, p: IntArray): Num {
        val start = p[0]
        var neg = false
        if (p[0] < s.length && (s[p[0]] == '-' || s[p[0]] == '+')) {
            neg = s[p[0]] == '-'
            p[0]++
        }
        while (p[0] < s.length && s[p[0]].isDigit()) p[0]++
        if (p[0] < s.length && s[p[0]] == '.') {  // reject floats on the wire
            throw JsonException("floating-point numbers not supported")
        }
        if (p[0] == start) throw JsonException("expected number")
        val v = s.substring(start, p[0]).toLong()
        return Num(if (neg) -v else v)
    }

    private fun expect(s: String, p: IntArray, word: String) {
        if (!s.startsWith(word, p[0])) throw JsonException("expected '$word'")
        p[0] += word.length
    }

    private fun skipWs(s: String, p: IntArray) {
        while (p[0] < s.length && s[p[0]].isWhitespace()) p[0]++
    }

    // -- serialization (for the --json verdict) -----------------------------

    fun stringify(v: Value): String = when (v) {
        is Str -> quote(v.s)
        is Num -> v.v.toString()
        is Bool -> v.b.toString()
        is Null -> "null"
        is Arr -> v.items.joinToString(",", "[", "]") { stringify(it) }
        is Obj -> v.fields.entries.joinToString(",", "{", "}") {
            quote(it.key) + ":" + stringify(it.value)
        }
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
