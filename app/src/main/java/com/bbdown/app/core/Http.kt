package com.bbdown.app.core

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * HTTP 工具，移植自 BBDown.Core.Util.HTTPUtil
 * 支持 gzip/deflate、自动重定向、Cookie/Referer/UA 头
 */
object Http {
    @Volatile var cookie: String = ""
    @Volatile var tvToken: String = ""  // TV/APP 端 access_token
    @Volatile var apiType: String = "web"  // web / tv / app / intl
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"

    /** GET 文本，自动解压 */
    fun get(url: String, extraCookie: String = "", referer: String = "", origin: String = ""): String {
        Logger.d("Http", "GET $url")
        val bytes = getBytes(url, "GET", null, extraCookie, referer, origin = origin)
        val result = String(bytes, Charsets.UTF_8)
        Logger.d("Http", "GET 响应 ${bytes.size} 字节, 前200字: ${result.take(200)}")
        return result
    }

    /** POST 表单数据，返回文本 */
    fun postForm(url: String, formData: Map<String, String>, extraCookie: String = "", referer: String = ""): String {
        Logger.d("Http", "POST $url params=${formData.keys}")
        val body = formData.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        val bytes = postBytes(url, body, "application/x-www-form-urlencoded", extraCookie, referer)
        val result = String(bytes, Charsets.UTF_8)
        Logger.d("Http", "POST 响应 ${bytes.size} 字节, 前200字: ${result.take(200)}")
        return result
    }

    /** POST 字节流，指定 Content-Type */
    fun postBytes(
        url: String, body: ByteArray, contentType: String,
        extraCookie: String = "", referer: String = ""
    ): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Content-Type", contentType)
                val ck = joinCookie(cookie, extraCookie)
                if (ck.isNotEmpty()) setRequestProperty("Cookie", ck)
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                doOutput = true
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            Logger.d("Http", "POST 响应码: $code")
            val stream: InputStream = if (code in 200..399) conn.inputStream else conn.errorStream ?: conn.inputStream
            val encoding = conn.contentEncoding ?: ""
            val decoded: InputStream = when (encoding) {
                "gzip" -> GZIPInputStream(stream)
                "deflate" -> InflaterInputStream(stream)
                else -> stream
            }
            return decoded.use { it.readBytes() }
        } catch (e: Exception) {
            Logger.e("Http", "POST请求失败 $url: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /** GET 字节流 */
    fun getBytes(
        url: String, method: String = "GET", body: ByteArray? = null,
        extraCookie: String = "", referer: String = "", range: String = "", origin: String = ""
    ): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                val ck = joinCookie(cookie, extraCookie)
                if (ck.isNotEmpty()) setRequestProperty("Cookie", ck)
                val ref = if (referer.isNotEmpty()) referer else if (url.contains("api.bilibili.com") || url.contains("bilivideo")) "https://www.bilibili.com/" else ""
                if (ref.isNotEmpty()) setRequestProperty("Referer", ref)
                if (origin.isNotEmpty()) setRequestProperty("Origin", origin)
                if (range.isNotEmpty()) setRequestProperty("Range", range)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            Logger.d("Http", "响应码: $code")
            val stream: InputStream = if (code in 200..399) conn.inputStream else conn.errorStream ?: conn.inputStream
            val encoding = conn.contentEncoding ?: ""
            val decoded: InputStream = when (encoding) {
                "gzip" -> GZIPInputStream(stream)
                "deflate" -> InflaterInputStream(stream)
                else -> stream
            }
            return decoded.use { it.readBytes() }
        } catch (e: Exception) {
            Logger.e("Http", "请求失败 $url: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    /** 合并两个 cookie 字符串，用 "; " 分隔，去除空项和尾部分号 */
    private fun joinCookie(base: String, extra: String): String {
        val parts = mutableListOf<String>()
        if (base.trim().isNotEmpty()) parts.add(base.trim().trim(';').trim())
        if (extra.trim().isNotEmpty()) parts.add(extra.trim().trim(';').trim())
        return parts.joinToString("; ")
    }

    /** GET 获取重定向后的最终地址（用于 b23.tv 短链） */
    fun getLocation(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", userAgent)
            }
            conn.inputStream.use { it.read() }
            return conn.url.toString()
        } catch (e: Exception) {
            return try { conn?.url?.toString() ?: url } catch (_: Exception) { url }
        } finally {
            conn?.disconnect()
        }
    }
}
