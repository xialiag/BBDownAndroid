package com.bbdown.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream

/**
 * 本地二维码生成工具，使用 ZXing 在设备端生成 QR 码，无需依赖外部 API。
 */
object QrCodeUtil {

    /** 生成 QR 码的 base64 data URL，可直接用于 WebView <img> 标签 */
    fun generateBase64Png(text: String, size: Int = 240): String {
        Logger.d("QrCode", "开始生成QR码, text长度=${text.length}, size=$size")
        try {
            val writer = MultiFormatWriter()
            Logger.d("QrCode", "调用ZXing encode...")
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            Logger.d("QrCode", "BitMatrix: ${width}x${height}")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            Logger.d("QrCode", "Bitmap创建完成, 开始压缩为PNG...")
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            bitmap.recycle()
            val base64 = "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            Logger.d("QrCode", "QR码生成成功, PNG字节=${baos.size()}, base64长度=${base64.length}")
            return base64
        } catch (e: Exception) {
            Logger.e("QrCode", "QR码生成失败", e)
            throw e
        }
    }
}
