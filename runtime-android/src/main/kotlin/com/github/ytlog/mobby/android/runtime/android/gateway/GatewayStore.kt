package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.localization.AppStrings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

/** No plaintext configuration or API key is written to the CLI home or workspace. */
class GatewayStore(context: Context) {
    private companion object {
        const val KEY_ALIAS = "mobby.gateway"
        val writeLock = Any()
    }
    private val prefs = context.getSharedPreferences("gateway_routes", Context.MODE_PRIVATE)
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun ids(): List<String> = prefs.getString("index", null)?.let { encrypted ->
        Json.parseToJsonElement(decrypt(encrypted)).jsonArray.map { it.jsonPrimitive.content }
    }.orEmpty()
    fun load(id: String, version: Long? = null): GatewayRecord {
        require(id in ids()) { AppStrings.gatewayNotFound }
        val current = GatewayRecord.parse(decrypt(requireNotNull(prefs.getString(id, null)) { AppStrings.gatewayNotFound }))
        if (version == null || version == current.version) return current
        val encoded = requireNotNull(prefs.getString("$id:$version", null)) { AppStrings.gatewayVersionNotFound }
        return GatewayRecord.parse(decrypt(encoded))
    }
    fun list(): List<GatewayRecord> = ids().map(::load)
    fun default(): GatewayChoice? = prefs.getString("default", null)?.let { GatewayChoice.parse(decrypt(it)) }
    fun selectDefault(choice: GatewayChoice) = synchronized(writeLock) {
        require(choice.mode in load(choice.id).modes()) { AppStrings.gatewayDoesNotSupportThisAgent }
        check(prefs.edit().putString("default", encrypt(choice.json())).commit()) { AppStrings.couldNotSaveDefaultGateway }
    }
    fun save(record: GatewayRecord): GatewayRecord = synchronized(writeLock) {
        record.validate()
        val ids = ids()
        val existing = if (record.id in ids) load(record.id) else null
        val saved = record.copy(version = (existing?.version ?: 0) + 1)
        val edit = prefs.edit()
        if (existing != null) edit.putString("${record.id}:${existing.version}", prefs.getString(record.id, null))
        if (default()?.id == record.id && default()?.mode !in saved.modes()) edit.remove("default")
        val updated = if (existing == null) ids + record.id else ids
        check(edit.putString(record.id, encrypt(saved.json()))
            .putString("index", encrypt(JsonArray(updated.map(::JsonPrimitive)).toString()))
            .commit()) { AppStrings.couldNotSaveGateway }
        saved
    }
    fun delete(id: String) = synchronized(writeLock) {
        require(id in ids())
        val updated = ids() - id
        val edit = prefs.edit().remove(id).putString("index", encrypt(JsonArray(updated.map(::JsonPrimitive)).toString()))
        prefs.all.keys.filter { it.startsWith("$id:") }.forEach(edit::remove)
        if (default()?.id == id) edit.remove("default")
        check(edit.commit()) { AppStrings.couldNotDeleteGateway }
    }
}
