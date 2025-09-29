package dev.tonnector.wallet

import org.ton.cell.Cell
import org.ton.cell.CellSlice

data class PayloadView(
    val kind: String,
    val comment: String? = null,
    val opcode: String? = null,
    val preview: String,
)

fun previewPayload(cell: Cell?): PayloadView {
    if (cell == null) return PayloadView("empty", preview = "no payload")
    return try {
        val slice = cell.beginParse()
        if (slice.remainingBits < 32) return PayloadView("raw", preview = "payload ${slice.remainingBits} bits")
        val op = slice.loadUInt(32).toLong()
        if (op == 0L) {
            val text = readUtf8(slice)
            PayloadView("comment", comment = text, opcode = "0x00000000", preview = "comment: $text")
        } else {
            PayloadView("op", opcode = "0x" + op.toString(16), preview = "opcode 0x${op.toString(16)}")
        }
    } catch (e: Exception) {
        PayloadView("raw", preview = "payload (undecoded: ${e.message})")
    }
}

private fun readUtf8(slice: CellSlice): String {
    val bytes = slice.remainingBits / 8
    if (bytes <= 0) return ""
    return slice.loadBits(bytes * 8).toByteArray().toString(Charsets.UTF_8)
}