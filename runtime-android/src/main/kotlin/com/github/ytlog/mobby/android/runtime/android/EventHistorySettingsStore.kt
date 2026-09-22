package com.github.ytlog.mobby.android.runtime.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.ytlog.mobby.android.runtime.api.EventHistorySettings
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-encrypted policy, separate from existing gateway storage and its persistent key alias. */
internal class EventHistorySettingsStore(context: Context, private val key: () -> SecretKey = ::policyKey) {
    private companion object {
        val lock = Any()
        const val ALIAS = "mobby.runtime-storage-policy"
        fun policyKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private val prefs = context.getSharedPreferences("runtime-storage-policy", Context.MODE_PRIVATE)
    fun load(): EventHistorySettings = synchronized(lock) {
        val encoded = prefs.getString("limits", null) ?: return@synchronized EventHistorySettings()
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val parts = cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).split(':')
        require(parts.size == 2 || parts.size == 4 || parts.size == 5)
        EventHistorySettings(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 30, parts.getOrNull(3)?.toInt() ?: 256, parts.getOrNull(4)?.toInt() ?: 512)
    }
    fun save(value: EventHistorySettings) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal("${value.retentionDays}:${value.budgetMiB}:${value.outputRetentionDays}:${value.outputBudgetMiB}:${value.attachmentBudgetMiB}".toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("limits", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun attachmentBudgetBytes(): Long = load().attachmentBudgetMiB * 1024L * 1024
    fun outputPolicy(): OutputRetentionPolicy = load().let { OutputRetentionPolicy(it.outputRetentionDays * 86_400_000L, it.outputBudgetMiB * 1024L * 1024) }
    fun policy(): EventHistoryPolicy = load().let { EventHistoryPolicy(it.retentionDays * 86_400_000L, it.budgetMiB * 1024L * 1024) }
}
