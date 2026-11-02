package com.github.ytlog.mobby.android.device

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.io.*
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/** TLS needs an encodable RSA key. Only its AES-GCM ciphertext leaves memory. */
internal object ScreenAdbIdentity {
    private const val SEAL_ALIAS = "mobby.screen.adb.seal"
    data class Identity(val key: PrivateKey, val certificate: Certificate)

    @Synchronized fun load(context: Context): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val file = AtomicFile(File(context.noBackupFilesDir, "screen-adb-identity.sealed"))
        val sealed = try {
            file.openRead().use { input ->
                check(file.baseFile.length() in 29..16384) { "adb_identity_size" }
                input.readBytes()
            }
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists()) throw missing
            null
        }
        if (sealed != null) {
            check(store.containsAlias(SEAL_ALIAS)) { "adb_identity_key_missing" }
            check(sealed[0] == 1.toByte()) { "adb_identity_version" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, store.getKey(SEAL_ALIAS, null) as SecretKey, GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
            val clear = cipher.doFinal(sealed, 13, sealed.size - 13)
            try {
                DataInputStream(ByteArrayInputStream(clear)).use { input ->
                    val keyLength = input.readInt(); check(keyLength in 1..4096)
                    val encoded = ByteArray(keyLength); input.readFully(encoded)
                    val privateKey = try { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) } finally { encoded.fill(0) }
                    val certificateLength = input.readInt(); check(certificateLength in 1..8192)
                    val certificate = ByteArray(certificateLength); input.readFully(certificate)
                    check(input.available() == 0)
                    return Identity(privateKey, CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)))
                }
            } finally { clear.fill(0) }
        }
        check(!EmbeddedAdb.paired(context)) { "adb_identity_missing" }
        if (!store.containsAlias(SEAL_ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(SEAL_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        val identity = createIdentity()
        val encoded = identity.key.encoded
        val certificate = identity.certificate.encoded
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(encoded.size); output.write(encoded)
            output.writeInt(certificate.size); output.write(certificate)
        }
        encoded.fill(0)
        val clear = buffer.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey(SEAL_ALIAS, null) as SecretKey)
        val encrypted = try { cipher.doFinal(clear) } finally { clear.fill(0); buffer.reset() }
        val output = file.startWrite()
        try { output.write(1); output.write(cipher.iv); output.write(encrypted); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        return identity
    }

    @Suppress("DEPRECATION") private fun createIdentity(): Identity {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // Use the fixed BC certificate builder without replacing Android's global crypto providers.
        val generator = X509V3CertificateGenerator()
        val name = X500Principal("CN=mobby screen")
        generator.setSerialNumber(BigInteger(128, SecureRandom()).setBit(127))
        generator.setIssuerDN(name); generator.setSubjectDN(name)
        generator.setNotBefore(Date(0)); generator.setNotAfter(Date(4_102_444_800_000L))
        generator.setPublicKey(pair.public); generator.setSignatureAlgorithm("SHA256withRSA")
        return Identity(pair.private, generator.generate(pair.private))
    }
}
