package com.inkslate.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * QR codes for joining one device to another: the code a device shows, and reading one back
 * from a picture - a screenshot pasted in, or a photo. Scanning with a camera is the platform's
 * own business; this is what both platforms share.
 */
object QrCodes {

    /** A code as squares: `modules[y][x]` is dark. Includes no quiet border; draw one around it. */
    class Matrix(val size: Int, private val dark: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
    }

    fun encode(text: String): Matrix {
        val bits = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0)
        )
        val n = bits.width
        return Matrix(n, BooleanArray(n * n) { bits[it % n, it / n] })
    }

    /**
     * The picture of [text] as ARGB pixels, [scale] pixels a square and a four-square white
     * border (what scanners want), for saving or copying as an image.
     */
    fun pixels(text: String, scale: Int = 8): Triple<Int, Int, IntArray> {
        val m = encode(text)
        val border = 4
        val side = (m.size + border * 2) * scale
        val out = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until m.size) for (x in 0 until m.size) {
            if (!m[x, y]) continue
            val px = (x + border) * scale
            val py = (y + border) * scale
            for (dy in 0 until scale) {
                val row = (py + dy) * side
                for (dx in 0 until scale) out[row + px + dx] = 0xFF000000.toInt()
            }
        }
        return Triple(side, side, out)
    }

    /** The text of a QR code found in a picture (ARGB pixels), or null when there is none. */
    fun decode(width: Int, height: Int, argb: IntArray): String? = runCatching {
        val source = RGBLuminanceSource(width, height, argb)
        MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        ).text
    }.getOrNull()
}

/** The addresses other devices can reach this one on. */
object NetAddresses {

    /**
     * Every IPv4 address of this device that is up and not loopback: the Wi-Fi one first, a
     * tailnet (100.64/10) last - a band room shares the Wi-Fi; the tailnet is for further away.
     */
    fun mine(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress } }
            .filter { !it.startsWith("169.254.") }
            .distinct()
            .sortedBy { if (isTailnet(it)) 1 else 0 }
    }.getOrDefault(emptyList())

    fun isTailnet(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }
}

/**
 * The text of a pairing QR code for "Your devices": who to pair with, every address it has, and
 * the one-time code - so pairing is one scan rather than an address and six digits typed in.
 * `<app>://pair?name=Tablet&hosts=192.168.1.4,100.70.1.2&port=47810&code=123456`
 */
object PairLink {
    data class Pair(val name: String, val hosts: List<String>, val port: Int, val code: String)

    fun of(scheme: String, name: String, hosts: List<String>, port: Int, code: String): String =
        "${scheme.lowercase()}://pair?name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            "&hosts=" + hosts.joinToString(",") + "&port=$port&code=$code"

    fun parse(text: String): Pair? {
        val t = text.trim()
        if (!t.contains("://pair?")) return null
        val params = t.substringAfter('?').split('&').associate { kv ->
            kv.substringBefore('=') to runCatching { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val code = params["code"]?.takeIf { it.length >= 4 } ?: return null
        if (hosts.isEmpty()) return null
        return Pair(params["name"].orEmpty().ifBlank { hosts.first() }, hosts, params["port"]?.toIntOrNull() ?: return null, code)
    }
}
