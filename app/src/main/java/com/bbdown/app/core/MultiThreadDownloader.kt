package com.bbdown.app.core

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多线程分片下载器，移植自 BBDown 的多线程下载逻辑。
 * 支持断点续传：通过 .dl 侧边文件记录每段下载进度，崩溃/取消后可从断点继续。
 */
class MultiThreadDownloader(
    private val threads: Int = 8,
    private val cookie: String = "",
    private val progress: (downloaded: Long, total: Long, speed: Long) -> Unit
) {
    @Volatile var canceled = false
        private set

    /** 当前活跃的分片线程，供 cancel() 中断唤醒阻塞中的 read() */
    @Volatile private var activeJobs: List<Thread> = emptyList()

    fun cancel() {
        canceled = true
        // 中断分片线程：阻塞在 input.read() 的线程会立即抛出，无需等待 readTimeout(60s)
        activeJobs.forEach { it.interrupt() }
    }

    /** 单个下载分片的状态 */
    private data class Segment(
        val start: Long,
        val end: Long,
        // 分片线程写、断点保存线程读，加 @Volatile 保证可见性
        @Volatile var downloaded: Long = 0
    ) {
        val size: Long get() = end - start + 1
        val isComplete: Boolean get() = downloaded >= size
        val resumeFrom: Long get() = start + downloaded
    }

    fun download(url: String, dest: File, referer: String = "https://www.bilibili.com/") {
        val total = querySize(url)
        if (total <= 0) {
            singleThreadDownload(url, dest, referer)
            return
        }
        // 完整文件已存在，跳过
        if (dest.exists() && dest.length() == total) {
            progress(total, total, 0)
            return
        }

        // 断点续传：加载或创建分片配置
        val cfgFile = File(dest.parentFile, dest.name + ".dl")
        val segments = loadResumeConfig(cfgFile, url, total)
            ?: createSegments(total)

        // 确保文件大小正确
        val raf = RandomAccessFile(dest, "rw")
        raf.setLength(total)
        raf.close()

        val downloaded = AtomicLong(segments.sumOf { it.downloaded })
        val startTime = System.currentTimeMillis()
        val firstError = AtomicReference<Throwable?>(null)

        val jobs = ArrayList<Thread>()
        for (seg in segments) {
            if (seg.isComplete) continue // 该分片已完成，跳过
            val t = Thread {
                try {
                    downloadPart(url, dest, seg.resumeFrom, seg.end, referer) { delta ->
                        seg.downloaded += delta
                        val d = downloaded.addAndGet(delta)
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val spd = if (elapsed > 0) (d / elapsed).toLong() else 0
                        progress(d, total, spd)
                    }
                } catch (e: Exception) {
                    if (!canceled) {
                        // 捕获异常到共享变量，不再 re-throw 导致线程未捕获异常崩溃
                        firstError.compareAndSet(null, e)
                        canceled = true
                        Logger.e("MultiThreadDownloader", "下载分片失败: ${e.message}", e)
                    }
                }
            }
            jobs.add(t)
        }
        // 注册为活跃线程，供 cancel() 中断（需在 start 前赋值，避免 cancel 与 start 的竞态窗口）
        activeJobs = jobs
        for (t in jobs) t.start()

        // 定期保存断点配置线程：每 3 秒保存一次当前进度，
        // 保证在崩溃/被系统杀死时进度不会丢失，仍可从断点继续。
        val saverThread = Thread {
            try {
                while (true) {
                    Thread.sleep(3000)
                    if (canceled) break
                    if (jobs.all { !it.isAlive }) break
                    saveResumeConfig(cfgFile, url, total, segments)
                }
            } catch (_: InterruptedException) {
                // 收到中断信号，正常退出
            }
        }
        saverThread.isDaemon = true
        saverThread.start()

        for (t in jobs) t.join()

        // 下载结束，中断并等待定期保存线程退出
        saverThread.interrupt()
        try {
            saverThread.join()
        } catch (_: InterruptedException) {}
        activeJobs = emptyList()

        if (canceled) {
            // 保存进度以便下次续传
            saveResumeConfig(cfgFile, url, total, segments)
            // 如果是错误导致的取消（非用户主动取消），抛出异常让上层处理
            val err = firstError.get()
            if (err != null) {
                throw java.io.IOException("下载失败: ${err.message}", err)
            }
            throw InterruptedException("下载已取消")
        }

        // 下载完成，删除配置文件
        cfgFile.delete()
        progress(total, total, 0)
    }

    /** 创建均匀分片 */
    private fun createSegments(total: Long): List<Segment> {
        val partSize = total / threads
        val segs = ArrayList<Segment>()
        for (i in 0 until threads) {
            val start = i * partSize
            val end = if (i == threads - 1) total - 1 else (start + partSize - 1)
            if (start > end) continue
            segs.add(Segment(start, end))
        }
        return segs
    }

    /** 加载断点续传配置，URL 不匹配则返回 null */
    private fun loadResumeConfig(cfgFile: File, url: String, total: Long): List<Segment>? {
        if (!cfgFile.exists()) return null
        try {
            val json = JSONObject(cfgFile.readText())
            if (json.optString("url") != url) return null
            if (json.optLong("total") != total) return null
            val arr = json.optJSONArray("parts") ?: return null
            val segs = ArrayList<Segment>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                segs.add(Segment(
                    p.getLong("start"),
                    p.getLong("end"),
                    p.optLong("downloaded", 0)
                ))
            }
            Logger.i("MultiThreadDownloader", "恢复断点: ${segs.filter { !it.isComplete }.size}/${segs.size} 段未完成")
            return segs
        } catch (_: Exception) {
            return null
        }
    }

    /** 保存断点续传配置 */
    private fun saveResumeConfig(cfgFile: File, url: String, total: Long, segments: List<Segment>) {
        try {
            val json = JSONObject()
            json.put("url", url)
            json.put("total", total)
            val arr = JSONArray()
            for (seg in segments) {
                val p = JSONObject()
                p.put("start", seg.start)
                p.put("end", seg.end)
                p.put("downloaded", seg.downloaded)
                arr.put(p)
            }
            json.put("parts", arr)
            cfgFile.writeText(json.toString())
        } catch (e: Exception) {
            Logger.w("MultiThreadDownloader", "保存断点配置失败: ${e.message}")
        }
    }

    private fun querySize(url: String): Long {
        // PGC(番剧) CDN 会拒绝 HTTP 请求并返回 403；forceHttp 开启时自动回退 HTTPS 重试
        val candidates = if (url.startsWith("http://"))
            listOf(url, "https://" + url.substring(7)) else listOf(url)
        for (currentUrl in candidates) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Referer", "https://www.bilibili.com/")
                    if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                    setRequestProperty("Range", "bytes=0-0")
                }
                val code = conn.responseCode
                // HTTP 403：番剧 CDN 拒绝 HTTP，回退 HTTPS 重试
                if (code == 403 && currentUrl.startsWith("http://")) continue
                val cr = conn.getHeaderField("Content-Range")
                if (cr != null && cr.contains("/")) {
                    return cr.substringAfter('/').toLongOrNull() ?: -1L
                }
                return conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            } catch (_: Exception) {
                // 当前 URL 失败，尝试下一个候选(HTTPS)
            } finally {
                conn?.disconnect()
            }
        }
        return -1L
    }

    private fun downloadPart(
        url: String, dest: File, start: Long, end: Long,
        referer: String, onProgress: (Long) -> Unit
    ) {
        // PGC(番剧) CDN 会拒绝 HTTP 请求并返回 403；forceHttp 开启时自动回退 HTTPS 重试
        val candidates = if (url.startsWith("http://"))
            listOf(url, "https://" + url.substring(7)) else listOf(url)
        var lastError: Exception? = null
        for (currentUrl in candidates) {
            var conn: HttpURLConnection? = null
            var raf: RandomAccessFile? = null
            var input: java.io.InputStream? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Referer", referer)
                    if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                    setRequestProperty("Range", "bytes=$start-$end")
                }
                // 检查 HTTP 响应码，避免 FileNotFoundException 崩溃
                val code = conn.responseCode
                // HTTP 403：番剧 CDN 拒绝 HTTP，回退 HTTPS 重试
                if (code == 403 && currentUrl.startsWith("http://")) {
                    Logger.w("MultiThreadDownloader", "HTTP 403(番剧CDN拒绝HTTP)，回退HTTPS重试")
                    continue
                }
                // 分片请求必须返回 206：若服务器忽略 Range 返回 200 全量，非首分片会写到错误偏移导致文件损坏
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) } catch (_: Exception) { null }
                    throw java.io.IOException("服务器未返回 206 Partial Content(HTTP $code)${if (errBody != null) ": $errBody" else ""}")
                }
                raf = RandomAccessFile(dest, "rw")
                raf.seek(start)
                input = conn.inputStream
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (canceled) return
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    onProgress(n.toLong())
                }
                return  // 下载成功，退出
            } catch (e: Exception) {
                // 取消/中断时快速退出，不再重试候选 URL
                if (canceled) throw InterruptedException("下载已取消")
                lastError = e
            } finally {
                try { input?.close() } catch (_: Exception) {}
                try { raf?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
        // 所有候选 URL 均失败，抛出最后一个异常让上层处理
        if (lastError != null) throw lastError
    }

    @Suppress("UNUSED_VARIABLE")
    private fun singleThreadDownload(url: String, dest: File, referer: String) {
        // PGC(番剧) CDN 会拒绝 HTTP 请求并返回 403；forceHttp 开启时自动回退 HTTPS 重试
        val candidates = if (url.startsWith("http://"))
            listOf(url, "https://" + url.substring(7)) else listOf(url)
        var lastError: Exception? = null
        for (currentUrl in candidates) {
            var conn: HttpURLConnection? = null
            var input: java.io.InputStream? = null
            try {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", Http.userAgent)
                    setRequestProperty("Referer", referer)
                    if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                }
                // 检查 HTTP 响应码
                val code = conn.responseCode
                // HTTP 403：番剧 CDN 拒绝 HTTP，回退 HTTPS 重试
                if (code == 403 && currentUrl.startsWith("http://")) {
                    Logger.w("MultiThreadDownloader", "HTTP 403(番剧CDN拒绝HTTP)，回退HTTPS重试")
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(500) } catch (_: Exception) { null }
                    throw java.io.IOException("HTTP $code 下载失败${if (errBody != null) ": $errBody" else ""}")
                }
                val total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                input = conn.inputStream
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    val startTime = System.currentTimeMillis()
                    while (true) {
                        if (canceled) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val spd = if (elapsed > 0) (downloaded / elapsed).toLong() else 0
                        progress(downloaded, total, spd)
                    }
                }
                return  // 下载成功，退出
            } catch (e: Exception) {
                // 取消/中断时快速退出，不再重试候选 URL
                if (canceled) throw InterruptedException("下载已取消")
                lastError = e
            } finally {
                try { input?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }
        if (lastError != null) throw lastError
    }
}
