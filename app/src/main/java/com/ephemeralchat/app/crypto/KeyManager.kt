package com.ephemeralchat.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.spec.*
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Daily private keys are wrapped with a nonexportable Android Keystore AES key.
 * Only the current UTC day's key is retained; no plaintext private key in preferences. */
class KeyManager(context: Context):DailyKeys {
    private val prefs = context.getSharedPreferences("daily_keys_v1", Context.MODE_PRIVATE)
    private val wrappingKey: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("daily-key-wrap", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("daily-key-wrap",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun wrap(data: ByteArray): String {
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE,wrappingKey)
        return Base64.getEncoder().encodeToString(c.iv+c.doFinal(data))
    }
    private fun unwrap(value: String): ByteArray {
        val raw=Base64.getDecoder().decode(value)
        val c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE,wrappingKey,javax.crypto.spec.GCMParameterSpec(128,raw.copyOfRange(0,12)))
        return c.doFinal(raw.copyOfRange(12,raw.size))
    }
    @Synchronized override fun dailyPair(): KeyPair {
        val day=SessionCrypto.today()
        if(prefs.getString("day",null)==day) {
            val kf=KeyFactory.getInstance("EC")
            return KeyPair(decodePeerPublicKey(prefs.getString("public",null)!!),kf.generatePrivate(PKCS8EncodedKeySpec(unwrap(prefs.getString("private",null)!!))))
        }
        val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        check(prefs.edit().clear().putString("day",day).putString("public",Base64.getEncoder().encodeToString(pair.public.encoded)).putString("private",wrap(pair.private.encoded)).commit())
        return pair
    }
    override fun publicKeyBase64() = Base64.getEncoder().encodeToString(dailyPair().public.encoded)
    override fun decodePeerPublicKey(value: String): PublicKey {
        require(value.length in 80..256)
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(value))) as java.security.interfaces.ECPublicKey
        require(key.params.curve.field.fieldSize==256)
        return key
    }
}
