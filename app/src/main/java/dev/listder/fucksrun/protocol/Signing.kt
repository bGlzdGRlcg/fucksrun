package dev.listder.fucksrun.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signing {
    const val HMAC_KEY = "mzy-campus-run-upload-v1-202605"
    const val SIGN_SUFFIX = "qu_mo_ni_qu_bu_yao_zai_lai_ni_xian_le"
    const val SIGN_VERSION = "running-chunk-v1"

    fun sha256(value: String): String = digest("SHA-256", value)
    fun md5(value: String): String = digest("MD5", value)
    fun hmacSha256(value: String, key: String = HMAC_KEY): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    fun sign(fields: List<Any>): String = hmacSha256(fields.joinToString("|") { field(it) })
    fun requestSignature(compactBody: String, timestamp: String, nonce: String, token: String): String =
        md5("$compactBody|$timestamp|$nonce|$token")

    fun chunkHash(
        record: Any, session: String, seq: Int, startMs: Long, endMs: Long,
        points: List<Map<String, Any?>>, previous: String,
    ): String = sha256(listOf(field(record), session, seq, startMs, endMs,
        CanonicalJson.canonical(points), previous).joinToString("|"))

    private fun field(value: Any): String = when (value) {
        is Number -> CanonicalJson.canonical(value)
        else -> value.toString()
    }
    private fun digest(algorithm: String, value: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8)))
    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        val alphabet = "0123456789abcdef"
        bytes.forEach {
            val value = it.toInt() and 255
            append(alphabet[value ushr 4])
            append(alphabet[value and 15])
        }
    }
}
