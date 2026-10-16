package dev.listder.fucksrun.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings never leave private app storage; credentials are encrypted with a device-bound key. */
data class LoginSettings(
    val token: String = "",
    val targetKm: String = "3.0",
)

class LoginSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("login-settings", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): LoginSettings {
        return LoginSettings(
            token = preferences.getString("token", null)?.let(::decrypt).orEmpty(),
            targetKm = preferences.getString("distance", "3.0") ?: "3.0",
        )
    }

    @Synchronized
    fun save(settings: LoginSettings) {
        val editor = preferences.edit()
            .remove("initialized")
            .remove("api")
            .putString("distance", settings.targetKm)
            .remove("remember")
        if (settings.token.isNotBlank()) {
            editor.putString("token", encrypt(settings.token))
        } else editor.remove("token")
        editor.remove("map-key")
        check(editor.commit()) { "设置保存失败" }
    }

    @Synchronized
    fun clearToken() {
        check(preferences.edit().remove("initialized").remove("token")
            .remove("remember").commit()) { "凭据清除失败" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(text: String): String {
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size >= 28) { "保存的凭据已损坏，请清除后重新输入" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "fucksrun-login-settings-v1"
}
    }
