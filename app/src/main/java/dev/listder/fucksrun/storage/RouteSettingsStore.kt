package dev.listder.fucksrun.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.listder.fucksrun.protocol.DrawnRoute
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AMap Web JS credentials are independent from the official API Token or Android SDK keys. */
data class RouteSettings(
    val mapKey: String = "",
    val securityCode: String = "",
    val draftJson: String? = null,
)

/** Device-bound AES-GCM storage, following LoginSettingsStore's Keystore convention. */
class RouteSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("route-settings", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): RouteSettings = RouteSettings(
        mapKey = preferences.getString("map-key", null)?.let(::decrypt).orEmpty(),
        securityCode = preferences.getString("security-code", null)?.let(::decrypt).orEmpty(),
        draftJson = preferences.getString("draft", null),
    )

    @Synchronized
    fun save(settings: RouteSettings) {
        settings.draftJson?.let(DrawnRoute::parse)
        val editor = preferences.edit().remove("configured").putString("draft", settings.draftJson)
        if (settings.mapKey.isBlank()) editor.remove("map-key")
        else editor.putString("map-key", encrypt(settings.mapKey.trim()))
        if (settings.securityCode.isBlank()) editor.remove("security-code")
        else editor.putString("security-code", encrypt(settings.securityCode.trim()))
        check(editor.commit()) { "地图设置保存失败" }
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
        require(bytes.size >= 28) { "保存的地图凭据已损坏，请重新填写" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private companion object {
        const val KEY_ALIAS = "fucksrun-route-settings-v1"
    }
}
