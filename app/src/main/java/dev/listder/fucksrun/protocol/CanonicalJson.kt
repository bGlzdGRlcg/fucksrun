package dev.listder.fucksrun.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.math.BigInteger

/** JSON wire encoding and the sorted, integer-normalized encoding used by chunk hashes. */
object CanonicalJson {
    fun compact(value: Any?): String = encode(value, canonical = false)
    fun canonical(value: Any?): String = encode(value, canonical = true)

    fun parse(text: String): Any? {
        val tokener = JSONTokener(text)
        val value = decode(tokener.nextValue())
        require(tokener.nextClean() == '\u0000') { "JSON 响应包含多余内容" }
        return value
    }

    fun parseObject(text: String): Map<String, Any?> =
        JsonValues.objectValue(parse(text)) ?: error("JSON 响应不是对象")

    private fun decode(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, decode(value.get(key)))
            }
        }
        is JSONArray -> List(value.length()) { decode(value.get(it)) }
        is String, is Boolean, is Number -> value
        else -> error("不支持的 JSON 值")
    }

    private fun encode(value: Any?, canonical: Boolean): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean -> value.toString()
        is Number -> number(value, canonical)
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "JSON 对象的 key 必须是字符串" }
            val entries = value.entries.let { entries ->
                if (canonical) entries.sortedWith { a, b -> compareKeys(a.key.toString(), b.key.toString()) }
                else entries.toList()
            }
            entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + encode(it.value, canonical) }
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it, canonical) }
        is Array<*> -> value.joinToString(",", "[", "]") { encode(it, canonical) }
        else -> error("不支持的 JSON 类型: ${value.javaClass.simpleName}")
    }

    private fun compareKeys(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ac = a.codePointAt(i)
            val bc = b.codePointAt(j)
            if (ac != bc) return ac.compareTo(bc)
            i += Character.charCount(ac)
            j += Character.charCount(bc)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun number(value: Number, canonical: Boolean): String {
        if (value is Double || value is Float) {
            val d = value.toDouble()
            require(d.isFinite()) { "JSON 不允许 NaN 或 Infinity" }
            val decimal = BigDecimal(value.toString())
            if (canonical && decimal.stripTrailingZeros().scale() <= 0) {
                return decimal.toBigIntegerExact().toString()
            }
            // Python json.dumps uses scientific notation below 1e-4 and from 1e16.
            if (d != 0.0 && (kotlin.math.abs(d) < 1e-4 || kotlin.math.abs(d) >= 1e16)) {
                val stripped = decimal.stripTrailingZeros()
                val exponent = stripped.precision() - stripped.scale() - 1
                val mantissa = stripped.movePointLeft(exponent).toPlainString()
                return mantissa + "e" + (if (exponent < 0) "-" else "+") +
                    kotlin.math.abs(exponent).toString().padStart(2, '0')
            }
            if (d == 0.0 && java.lang.Double.doubleToRawLongBits(d) < 0) return "-0.0"
            val plain = decimal.toPlainString()
            return if ('.' in plain) plain else "$plain.0"
        }
        if (value is BigDecimal && canonical) {
            val normalized = value.stripTrailingZeros()
            return if (normalized.scale() <= 0) normalized.toBigIntegerExact().toString()
            else normalized.toPlainString()
        }
        require(value is Byte || value is Short || value is Int || value is Long ||
            value is BigInteger || value is BigDecimal) { "不支持的 JSON 数字" }
        return value.toString()
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }
}

/** Safe boundaries for untyped server data; no unchecked map/list casts. */
object JsonValues {
    fun objectValue(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *> || value.keys.any { it !is String }) return null
        return buildMap { value.forEach { (key, item) -> if (key is String) put(key, item) } }
    }

    fun arrayValue(value: Any?): List<Any?>? = if (value is List<*>) value.toList() else null
    fun string(value: Any?): String? = when (value) {
        is String -> value
        is Number -> value.toString()
        else -> null
    }
    fun double(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
}
