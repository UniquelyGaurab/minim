package com.minim.music.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v5.2.0: the Gemini settings. Kept in their own file (minim_ai) that cloud sync never reads
 * and Android's backup skips (see res/xml/backup_rules.xml and data_extraction_rules.xml).
 *
 * Your API key is encrypted with AES-GCM using a key held in the Android Keystore, which
 * never leaves this phone. The plain key only exists in memory while a request is made, and
 * it is never logged.
 */
class GeminiSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The model ID the chat calls (a preset or your custom one). */
    var model: String
        get() = GeminiModels.resolve(prefs.getString(KEY_MODEL, null))
        set(value) = prefs.edit().putString(KEY_MODEL, GeminiModels.normalize(value)).apply()

    /** The last custom ID you typed, so picking "Custom" again shows it. */
    var customModel: String
        get() = prefs.getString(KEY_CUSTOM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM, GeminiModels.normalize(value)).apply()

    val hasApiKey: Boolean get() = prefs.contains(KEY_API)

    /** Decrypts the saved key, or null when there is none (or it can't be read any more). */
    fun apiKey(): String? {
        val stored = prefs.getString(KEY_API, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, IV_LEN))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /** Saves [key] encrypted. Returns false if the phone couldn't encrypt it (then nothing is saved). */
    fun setApiKey(key: String): Boolean {
        val clean = cleanKey(key)
        if (clean.isEmpty()) {
            clearApiKey()
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val enc = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + enc.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(enc, 0, out, iv.size, enc.size)
            prefs.edit().putString(KEY_API, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val PREFS = "minim_ai"
        private const val KEY_MODEL = "model"
        private const val KEY_CUSTOM = "custom_model"
        private const val KEY_API = "api_key_enc"
        private const val ALIAS = "minim_gemini_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        /** Strips spaces, quotes and line breaks that sneak in when pasting. */
        fun cleanKey(raw: String): String = raw.trim().trim('"', '\'', '`').filterNot { it.isWhitespace() }

        /** AI Studio keys look like "AIza" + 35 characters. Only a hint; Google decides. */
        fun looksLikeKey(raw: String): Boolean = Regex("^AIza[0-9A-Za-z_\\-]{30,}$").matches(cleanKey(raw))

        /** "AIza…x9Qk" for showing which key is saved without showing it. */
        fun masked(key: String): String = if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)
    }
}
