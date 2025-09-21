package dev.tonnector.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

/**
 * ton_proof message from the TonConnect spec.
 * signature = Ed25519(sha256(0xffff || "ton-connect" || sha256(message)))
 */
fun tonProofMessage(
    workchain: Int,
    addressHash: ByteArray,
    domain: String,
    timestamp: Long,
    payload: String,
): ByteArray {
    val domainBytes = domain.encodeToByteArray()
    val payloadBytes = payload.encodeToByteArray()
    val out = ByteBuffer.allocate(4 + 32 + 4 + domainBytes.size + 8 + payloadBytes.size)
        .order(ByteOrder.BIG_ENDIAN)
    out.putInt(workchain)
    out.put(addressHash)
    out.order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(domainBytes.size)
    out.put(domainBytes)
    out.putLong(timestamp)
    out.put(payloadBytes)
    return "ton-proof-item-v2/".encodeToByteArray() + out.array()
}

fun tonProofHash(message: ByteArray): ByteArray {
    val inner = sha256(message)
    val prefixed = byteArrayOf(0xff.toByte(), 0xff.toByte()) +
        "ton-connect".encodeToByteArray() +
        inner
    return sha256(prefixed)
}
