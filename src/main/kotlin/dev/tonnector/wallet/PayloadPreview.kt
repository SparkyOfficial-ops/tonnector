package dev.tonnector.wallet

import org.ton.cell.Cell
import org.ton.cell.CellSlice

data class PayloadView(
    val kind: String,
    val comment: String? = null,
    val jettonAmount: String? = null,
    val opcode: String? = null,
    val preview: String,
)

fun previewPayload(cell: Cell?): PayloadView {
    if (cell == null || (cell.bits.isEmpty() && cell.refs.isEmpty())) {
        return PayloadView("empty", preview = "no payload")
    }
    return try {
        val slice = cell.beginParse()
        if (slice.remainingBits < 32) {
            return PayloadView("raw", preview = "payload ${slice.remainingBits} bits")
        }
        val op = slice.loadUInt(32).toLong()
        when (op) {
            0L -> {
                val text = readUtf8(slice)
                PayloadView("comment", comment = text, opcode = "0x00000000", preview = "comment: $text")
            }
            0x0f8a7ea5L -> {
                if (slice.remainingBits >= 64) slice.loadUInt(64)
                val amount = if (slice.remainingBits >= 4) readCoins(slice) else "?"
                PayloadView(
                    "jetton",
                    jettonAmount = amount,
                    opcode = "0x0f8a7ea5",
                    preview = "jetton transfer $amount",
                )
            }
            0x5fcc3d14L -> PayloadView("nft", opcode = "0x5fcc3d14", preview = "nft transfer")
            0x595f07bcL -> PayloadView("jetton-burn", opcode = "0x595f07bc", preview = "jetton burn")
            else -> PayloadView("op", opcode = "0x" + op.toString(16), preview = "opcode 0x${op.toString(16)}")
        }
    } catch (e: Exception) {
        PayloadView("raw", preview = "payload (undecoded: ${e.message})")
    }
}

private fun readUtf8(slice: CellSlice): String {
    val bits = slice.remainingBits
    val bytes = bits / 8
    if (bytes <= 0) return ""
    return slice.loadBits(bytes * 8).toByteArray().toString(Charsets.UTF_8)
}

private fun readCoins(slice: CellSlice): String {
    val len = slice.loadUInt(4).toInt()
    if (len == 0) return "0"
    if (slice.remainingBits < len * 8) return "?"
    return slice.loadUInt(len * 8).toString()
}

private val org.ton.bitstring.BitString.isEmpty: Boolean get() = size == 0
