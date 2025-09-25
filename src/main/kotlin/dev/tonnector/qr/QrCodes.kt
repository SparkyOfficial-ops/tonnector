package dev.tonnector.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun qrPng(text: String, size: Int = 280): ByteArray {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until size) {
        for (x in 0 until size) {
            image.setRGB(x, y, if (matrix.get(x, y)) 0x0B1220 else 0xF4F7FB)
        }
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}
