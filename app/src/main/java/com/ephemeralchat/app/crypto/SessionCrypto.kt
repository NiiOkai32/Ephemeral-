package com.ephemeralchat.app.crypto

import java.security.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned daily ECDH + HKDF-SHA256 + AES-256-GCM. Each device combines its own daily private key
 * with the peer public key. This protocol requires authenticated pairing. */
object SessionCrypto {
    fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()
    fun deriveSharedKey(privateKey: PrivateKey, peer: PublicKey, local: PublicKey, day: String, connectionContext: String = ""): SecretKeySpec {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        val publicKeys = listOf(Base64.getEncoder().encodeToString(local.encoded), Base64.getEncoder().encodeToString(peer.encoded)).sorted().joinToString("|")
        val salt = MessageDigest.getInstance("SHA-256").digest("ephemeral/v1/$day/$publicKeys/$connectionContext".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val secret = agreement.generateSecret()
        val prk = mac.doFinal(secret)
        secret.fill(0)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val key = mac.doFinal("message-and-signaling-key\u0001".toByteArray())
        prk.fill(0)
        return SecretKeySpec(key, "AES")
    }
    fun encrypt(key: SecretKeySpec, plaintext: ByteArray, context: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext))
    }
    fun decrypt(key: SecretKeySpec, payload: String, context: String): ByteArray {
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        cipher.updateAAD(context.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size))
    }
}
