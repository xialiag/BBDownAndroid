package com.bbdown.app

import android.content.Context
import com.bbdown.app.core.Logger
import java.io.File
import java.security.MessageDigest

/**
 * 封面/头像图片的磁盘缓存。
 *
 * 背景：WebView 版封面统一走 `AndroidBridge.fetchImage` 取 base64 再转 blob URL，
 * JS 侧的 `_imgCache` 只有内存 LRU（80 条），进程重启即全部失效 → 每次冷启动
 * 都要把整屏封面重新下载一遍（账号页关注列表 50 个头像 + 收藏夹卡片尤其明显）。
 *
 * 这里按 URL 的 SHA-1 落盘，命中直接返回字节，避免重复网络请求。
 * 放在 cacheDir（可被系统在低存储时回收），并受 [MAX_BYTES] 容量上限约束，
 * 超限时按 lastModified 淘汰最旧文件。
 */
object ImageDiskCache {
    private const val DIR_NAME = "imgcache"
    private const val MAX_BYTES = 64L * 1024 * 1024   // 64MB
    private const val TTL_MS = 7L * 24 * 3600 * 1000  // 7 天
    /** 每次写入后最多做一次容量检查（避免每张图都遍历目录） */
    private const val TRIM_INTERVAL_MS = 60_000L
    private var lastTrimAt = 0L

    @Volatile private var dir: File? = null

    /** 缓存子目录名（供缓存统计时排除，避免与 WebView 缓存重复计数） */
    fun dirName(): String = DIR_NAME

    fun init(context: Context) {
        if (dir != null) return
        synchronized(this) {
            if (dir != null) return
            val d = File(context.cacheDir, DIR_NAME)
            if (!d.exists()) d.mkdirs()
            dir = d
            Logger.i("ImageDiskCache", "缓存目录: ${d.absolutePath}")
        }
    }

    private fun key(url: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val h = md.digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(h.size * 2)
        for (b in h) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 命中且未过期则返回字节，否则 null */
    fun get(url: String): ByteArray? {
        val d = dir ?: return null
        val f = File(d, key(url))
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > TTL_MS) {
            f.delete()
            return null
        }
        return try {
            f.readBytes().also { if (it.isNotEmpty()) f.setLastModified(System.currentTimeMillis()) }
        } catch (e: Exception) {
            Logger.w("ImageDiskCache", "读取失败: ${e.message}")
            null
        }
    }

    /** 写入缓存（失败静默：缓存不是关键路径） */
    fun put(url: String, bytes: ByteArray) {
        val d = dir ?: return
        if (bytes.isEmpty()) return
        try {
            val f = File(d, key(url))
            f.writeBytes(bytes)
            maybeTrim(d)
        } catch (e: Exception) {
            Logger.w("ImageDiskCache", "写入失败: ${e.message}")
        }
    }

    /** 容量超限时按最久未使用淘汰 */
    private fun maybeTrim(d: File) {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now
        try {
            val files = d.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= MAX_BYTES) return@forEach
                val len = f.length()
                if (f.delete()) total -= len
            }
            Logger.i("ImageDiskCache", "超限淘汰完成，剩余 ${total / 1024} KB")
        } catch (e: Exception) {
            Logger.w("ImageDiskCache", "淘汰失败: ${e.message}")
        }
    }

    /** 清空缓存，返回释放的字节数 */
    fun clear(): Long {
        val d = dir ?: return 0
        var cleared = 0L
        try {
            d.listFiles()?.forEach { f ->
                if (f.isFile) { cleared += f.length(); f.delete() }
            }
        } catch (e: Exception) {
            Logger.w("ImageDiskCache", "清空失败: ${e.message}")
        }
        lastTrimAt = 0L
        return cleared
    }

    /** 当前占用字节数 */
    fun size(): Long = dir?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
