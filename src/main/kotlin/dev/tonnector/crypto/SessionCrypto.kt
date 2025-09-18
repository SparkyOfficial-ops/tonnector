package dev.tonnector.crypto

import com.iwebpp.crypto.TweetNaclFast
import java.security.SecureRandom

/**
 * TonConnect session box: client_id is the hex X25519 public key.
 * Ciphertext on the wire is nonce (24 bytes) || nacl.box output.
 */
class SessionCrypto(
    val publicKey: ByteArray,
    val secretKey: ByteArray,
) {
    val sessionId: String = publicKey.toHex()

    fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val boxed = TweetNaclFast.Box(recipientPublicKey, secretKey).box(plaintext, nonce)
            ?: error("nacl.box failed")
        return nonce + boxed
    }

    fun decrypt(payload: ByteArray, senderPublicKey: ByteArray): ByteArray? {
        if (payload.size <= NONCE_LEN) return null
        val nonce = payload.copyOfRange(0, NONCE_LEN)
        val boxed = payload.copyOfRange(NONCE_LEN, payload.size)
        return TweetNaclFast.Box(senderPublicKey, secretKey).open(boxed, nonce)
    }

    companion object {
        private const val NONCE_LEN = 24
        private val random = SecureRandom()

        fun generate(): SessionCrypto {
            val pair = TweetNaclFast.Box.keyPair()
            return SessionCrypto(pair.publicKey, pair.secretKey)
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = trim().lowercase().removePrefix("0x")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
