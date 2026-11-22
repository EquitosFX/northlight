package app.tvplayer.core.extras

import java.io.Reader

/**
 * Reads a big JSON array of objects one object at a time, keeping only the named fields, so a
 * many-megabyte file never sits in memory as a tree. Strings come back as String, arrays of strings
 * as List<String>, true/false/numbers as their text, null as null; anything else is skipped.
 */
class JsonScan(private val reader: Reader) {
    private val buffer = CharArray(32_768)
    private var position = 0
    private var filled = 0

    private fun peek(): Int {
        if (position == filled) {
            filled = reader.read(buffer)
            position = 0
            if (filled <= 0) { filled = 0; return -1 }
        }
        return buffer[position].code
    }

    private fun next(): Int = peek().also { if (it >= 0) position++ }

    private fun skipSpace() {
        while (true) {
            val c = peek()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) next() else return
        }
    }

    private fun expect(c: Char) {
        skipSpace()
        val got = next()
        if (got != c.code) throw IllegalStateException("Expected '$c' but found ${if (got < 0) "the end" else "'" + got.toChar() + "'"}")
    }

    fun objects(wanted: Set<String>, each: (Map<String, Any?>) -> Unit) {
        expect('[')
        skipSpace()
        if (peek() == ']'.code) { next(); return }
        var more = true
        while (more) {
            skipSpace()
            if (peek() == '{'.code) each(readObject(wanted)) else skipValue()
            skipSpace()
            more = when (next()) {
                ','.code -> true
                ']'.code -> false
                else -> throw IllegalStateException("Broken list")
            }
        }
    }

    private fun readObject(wanted: Set<String>): Map<String, Any?> {
        val found = HashMap<String, Any?>()
        expect('{')
        skipSpace()
        if (peek() == '}'.code) { next(); return found }
        var more = true
        while (more) {
            skipSpace()
            val name = readString()
            expect(':')
            skipSpace()
            if (name in wanted) found[name] = readValue() else skipValue()
            skipSpace()
            more = when (next()) {
                ','.code -> true
                '}'.code -> false
                else -> throw IllegalStateException("Broken object")
            }
        }
        return found
    }

    private fun readValue(): Any? {
        skipSpace()
        return when (peek()) {
            '"'.code -> readString()
            '['.code -> readStrings()
            '{'.code -> { skipValue(); null }
            else -> readBare()
        }
    }

    private fun readStrings(): List<String> {
        expect('[')
        val list = ArrayList<String>()
        skipSpace()
        if (peek() == ']'.code) { next(); return list }
        var more = true
        while (more) {
            skipSpace()
            if (peek() == '"'.code) list += readString() else skipValue()
            skipSpace()
            more = when (next()) {
                ','.code -> true
                ']'.code -> false
                else -> throw IllegalStateException("Broken list")
            }
        }
        return list
    }

    /** true, false, null or a number. */
    private fun readBare(): String? {
        val text = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) break
            text.append(next().toChar())
        }
        return text.toString().takeIf { it != "null" }
    }

    private fun skipValue() {
        skipSpace()
        when (peek()) {
            '"'.code -> readString()
            '['.code, '{'.code -> {
                var depth = 0
                while (true) {
                    val c = peek()
                    if (c < 0) return
                    if (c == '"'.code) { readString(); if (depth == 0) return; continue }
                    next()
                    if (c == '['.code || c == '{'.code) depth++
                    if (c == ']'.code || c == '}'.code) { depth--; if (depth == 0) return }
                }
            }
            else -> readBare()
        }
    }

    private fun readString(): String {
        expect('"')
        val text = StringBuilder()
        while (true) {
            val c = next()
            when {
                c < 0 -> throw IllegalStateException("Unfinished text")
                c == '"'.code -> break
                c == '\\'.code -> when (val e = next()) {
                    'n'.code -> text.append('\n')
                    't'.code -> text.append('\t')
                    'r'.code -> text.append('\r')
                    'b'.code -> text.append('\b')
                    'f'.code -> text.append('\u000C')
                    'u'.code -> {
                        val hex = CharArray(4) { next().toChar() }
                        text.append(String(hex).toInt(16).toChar())
                    }
                    else -> text.append(e.toChar())
                }
                else -> text.append(c.toChar())
            }
        }
        return text.toString()
    }
}
