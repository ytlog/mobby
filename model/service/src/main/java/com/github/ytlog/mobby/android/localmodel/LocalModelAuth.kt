package com.github.ytlog.mobby.android.localmodel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalModelAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("local_model_auth", Context.MODE_PRIVATE)
    private val alias = "mobby.localmodel.tokens"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun inferenceToken(): String = token("inference")

    @Synchronized internal fun token(scope: String): String {
        prefs.getString(scope, null)?.let { return decrypt(it) }
        val random = ByteArray(32).also(SecureRandom()::nextBytes)
        val value = Base64.encodeToString(random, Base64.NO_WRAP or Base64.URL_SAFE)
        check(prefs.edit().putString(scope, encrypt(value)).commit())
        return value
    }

    fun allows(provided: String?, manage: Boolean = false): Boolean {
        if (provided.isNullOrBlank()) return false
        val expected = if (manage) token("admin") else token("inference")
        return java.security.MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray()) ||
            (!manage && java.security.MessageDigest.isEqual(provided.toByteArray(), token("admin").toByteArray()))
    }
}
