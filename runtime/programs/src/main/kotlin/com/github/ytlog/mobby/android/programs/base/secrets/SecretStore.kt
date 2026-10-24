package com.github.ytlog.mobby.android.programs.base.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.github.ytlog.mobby.android.programs.base.storage.enumType
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.store.ProgramsDao
import com.github.ytlog.mobby.android.programs.store.SecretRow
import kotlinx.serialization.json.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray
}

/** AES-GCM under the program-only Keystore alias; never the gateway's `mobby.gateway`. */
class KeystoreSecretCipher(private val alias: String = ALIAS) : SecretCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    override fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.doFinal(plain) to cipher.iv
    }
    override fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(ciphertext)

    companion object { const val ALIAS = "mobby.programs" }
}

data class SecretInfo(val name: String, val domains: Set<String>, val updatedAt: Long)

/**
 * Program credentials: write-only for programs, released only into requests to their bound domains.
 * Bound domains must stay within what the program requested for net.http.
 */
class SecretStore(private val dao: ProgramsDao, private val cipher: SecretCipher, private val clock: () -> Long) {
    suspend fun put(programId: String, name: String, value: String, domains: Set<String>) {
        if (!name.matches(NAME)) invalid("invalid_secret_name")
        if (value.isEmpty() || value.length > 8_192) invalid("secret_must_be_1_to_8192_characters")
        if (domains.isEmpty() || domains.any { !Scope.validDomain(it) }) invalid("secret_needs_valid_domains")
        val requested = dao.grants(programId).firstOrNull { it.capability == "net.http" }?.let { Scope.fromJson(it.requestedScope) }
            ?: throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "secret_needs_net_http")
        if (!domains.all { it in requested.domains.orEmpty() || requested.allowsHost(it.removePrefix("*.")) && !it.startsWith("*.") })
            throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "secret_domains_outside_net_http_request")
        val (ciphertext, iv) = cipher.encrypt(value.toByteArray())
        val now = clock()
        val created = dao.secret(programId, name)?.createdAt ?: now
        dao.upsertSecret(SecretRow(programId, name, ciphertext, iv, domains.sorted().joinToString(","), created, now))
    }

    suspend fun list(programId: String) = dao.secrets(programId).map { SecretInfo(it.name, it.domains.split(',').toSet(), it.updatedAt) }

    suspend fun delete(programId: String, name: String) = dao.deleteSecret(programId, name) > 0

    /** The plain value for a request to [host], or a scope denial; never logged. */
    suspend fun reveal(programId: String, name: String, host: String): String {
        val row = dao.secret(programId, name) ?: throw CapabilityException(ProgramErrorCode.RESOURCE_MISSING, "secret_not_found")
        val bound = Scope(domains = row.domains.split(',').toSet())
        if (!bound.allowsHost(host)) throw CapabilityException(ProgramErrorCode.SCOPE_DENIED, "secret_not_bound_to_host")
        return cipher.decrypt(row.ciphertext, row.iv).decodeToString()
    }

    companion object { val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}") }
}

/** `secrets`: programs store and list their credentials; reading the value back is not offered. */
class SecretsCapability(private val store: SecretStore) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("secrets", 1, CapabilityKind.COMMAND,
        schema(listOf("op"), "op" to enumType("put", "list", "delete"), "name" to stringType(64), "value" to stringType(8_192),
            "domains" to buildJsonObject { put("type", "array"); put("maxItems", 32); put("items", stringType(253)) }),
        buildJsonObject { put("type", "object") }, ScopeKind.NONE, setOf(SideEffect.WRITE, SideEffect.DELETE), emptyList(),
        setOf(Surface.BACKGROUND, Surface.UI), 5_000, false, CapabilityLimits(16 * 1024, 60), implicit = true)

    override fun availability(): Availability = Availability.Available

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val program = call.context.programId
        return when (call.args.text("op")) {
            "put" -> {
                val domains = (call.args["domains"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
                store.put(program, call.args.text("name") ?: invalid("name_required"), call.args.text("value") ?: invalid("value_required"), domains)
                CapabilityOutcome(Effect.CONFIRMED, JsonNull)
            }
            "list" -> CapabilityOutcome(Effect.NONE, JsonArray(store.list(program).map { info ->
                buildJsonObject { put("name", info.name); put("domains", JsonArray(info.domains.sorted().map(::JsonPrimitive))); put("updatedAt", info.updatedAt) }
            }))
            "delete" -> CapabilityOutcome(if (store.delete(program, call.args.text("name") ?: invalid("name_required"))) Effect.CONFIRMED else Effect.NONE, JsonNull)
            else -> invalid("unknown_op")
        }
    }
}
