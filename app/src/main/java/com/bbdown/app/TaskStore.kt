package com.bbdown.app

import android.content.Context
import android.os.Build
import com.bbdown.app.core.DownloadTask
import com.bbdown.app.core.Logger
import com.bbdown.app.core.PageInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 任务持久化存储：将任务列表序列化为 JSON 保存到应用私有目录。
 * 应用退出/崩溃后，下次启动可恢复未完成/已完成任务列表。
 */
object TaskStore {
    private const val FILE_NAME = "tasks.json"
    private var storeFile: File? = null
    // 同步锁：防止多线程并发写入导致 tmp 文件竞争（NoSuchFileException）
    private val saveLock = Any()

    /** 初始化存储文件路径（应用启动时调用） */
    fun init(context: Context) {
        storeFile = File(context.filesDir, FILE_NAME)
        Logger.i("TaskStore", "存储路径: ${storeFile?.absolutePath}")
    }

    /** 保存当前全部任务到磁盘（线程安全） */
    fun save() {
        val file = storeFile ?: return
        synchronized(saveLock) {
            try {
                val arr = JSONArray()
                for (t in TaskManager.all) {
                    arr.put(taskToJson(t))
                }
                // 原子写入：先写临时文件，再原子地替换原文件，
                // 避免进程在写入过程中被杀死导致 tasks.json 损坏。
                // 使用唯一临时文件名，防止并发写入时 tmp 文件被覆盖
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(arr.toString(), Charsets.UTF_8)
                if (file.exists()) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            java.nio.file.Files.move(
                                tmp.toPath(),
                                file.toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } else {
                            file.delete()
                            tmp.renameTo(file)
                        }
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        // ATOMIC_MOVE 不支持时回退到 copy+delete
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    } catch (_: Exception) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        java.nio.file.Files.move(
                            tmp.toPath(),
                            file.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE
                        )
                    } else {
                        if (!tmp.renameTo(file)) {
                            tmp.copyTo(file, overwrite = true)
                            tmp.delete()
                        } else { /* renameTo 成功 */ }
                    }
                }
            } catch (e: Exception) {
                Logger.e("TaskStore", "保存任务失败", e)
            }
        }
    }

    /** 从磁盘加载已保存的任务列表 */
    fun load(): List<DownloadTask> {
        val file = storeFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = ArrayList<DownloadTask>()
            for (i in 0 until arr.length()) {
                try {
                    val t = jsonToTask(arr.getJSONObject(i))
                    if (t != null) list.add(t)
                } catch (e: Exception) {
                    Logger.w("TaskStore", "恢复任务 ${i} 失败: ${e.message}")
                }
            }
            list
        } catch (e: Exception) {
            Logger.e("TaskStore", "加载任务失败", e)
            emptyList()
        }
    }

    private fun taskToJson(t: DownloadTask): JSONObject {
        val j = JSONObject()
        j.put("taskId", t.taskId)
        j.put("url", t.url)
        j.put("title", t.title)
        j.put("pic", t.pic)
        j.put("videoId", t.videoId)
        j.put("preferCodec", t.preferCodec)
        j.put("preferAudio", t.preferAudio)
        j.put("cookie", t.cookie)
        j.put("downloadMode", t.downloadMode)
        j.put("downloadDanmaku", t.downloadDanmaku)
        j.put("skipMux", t.skipMux)
        j.put("skipSubtitle", t.skipSubtitle)
        j.put("skipCover", t.skipCover)
        j.put("skipAi", t.skipAi)
        j.put("videoAscending", t.videoAscending)
        j.put("audioAscending", t.audioAscending)
        j.put("filePattern", t.filePattern)
        j.put("forceHttp", t.forceHttp)
        j.put("isCheese", t.isCheese)
        j.put("collectionTitle", t.collectionTitle)
        j.put("upperName", t.upperName)
        j.put("desc", t.desc)
        j.put("pubTime", t.pubTime)
        j.put("status", t.status)
        j.put("progress", t.progress)
        j.put("downloadedBytes", t.downloadedBytes)
        j.put("totalBytes", t.totalBytes)
        j.put("errorMsg", t.errorMsg)
        j.put("outputDir", t.outputDir)
        val files = JSONArray()
        for (f in t.outputFiles) files.put(f)
        j.put("outputFiles", files)
        j.put("createTime", t.createTime)
        j.put("finishTime", t.finishTime)
        j.put("seq", t.seq)
        val pages = JSONArray()
        for (p in t.pages) {
            val pj = JSONObject()
            pj.put("index", p.index); pj.put("aid", p.aid); pj.put("cid", p.cid)
            pj.put("epid", p.epid); pj.put("title", p.title); pj.put("duration", p.duration)
            pages.put(pj)
        }
        j.put("pages", pages)
        return j
    }

    private fun jsonToTask(j: JSONObject): DownloadTask? {
        val pages = ArrayList<PageInfo>()
        val parr = j.optJSONArray("pages") ?: JSONArray()
        for (i in 0 until parr.length()) {
            val p = parr.getJSONObject(i)
            pages.add(PageInfo(
                index = p.getInt("index"), aid = p.optString("aid"), cid = p.optString("cid"),
                epid = p.optString("epid"), title = p.optString("title"), duration = p.optInt("duration")
            ))
        }
        val files = ArrayList<String>()
        val farr = j.optJSONArray("outputFiles")
        if (farr != null) {
            for (i in 0 until farr.length()) files.add(farr.getString(i))
        }
        return DownloadTask(
            taskId = j.optString("taskId"),
            url = j.optString("url"),
            title = j.optString("title"),
            pic = j.optString("pic"),
            pages = pages,
            videoId = j.optString("videoId", "80"),
            preferCodec = j.optString("preferCodec", "avc"),
            preferAudio = j.optString("preferAudio", "m4a"),
            cookie = j.optString("cookie"),
            downloadMode = j.optString("downloadMode", "all"),
            downloadDanmaku = j.optBoolean("downloadDanmaku", false),
            skipMux = j.optBoolean("skipMux", false),
            skipSubtitle = j.optBoolean("skipSubtitle", false),
            skipCover = j.optBoolean("skipCover", false),
            skipAi = j.optBoolean("skipAi", true),
            videoAscending = j.optBoolean("videoAscending", false),
            audioAscending = j.optBoolean("audioAscending", false),
            filePattern = j.optString("filePattern", ""),
            forceHttp = j.optBoolean("forceHttp", false),
            isCheese = j.optBoolean("isCheese", false),
            collectionTitle = j.optString("collectionTitle", ""),
            upperName = j.optString("upperName", ""),
            desc = j.optString("desc", ""),
            pubTime = j.optLong("pubTime", 0),
            status = j.optInt("status", 0),
            progress = j.optDouble("progress", 0.0).toFloat(),
            downloadedBytes = j.optLong("downloadedBytes", 0),
            totalBytes = j.optLong("totalBytes", 0),
            errorMsg = j.optString("errorMsg", ""),
            outputDir = j.optString("outputDir", ""),
            outputFiles = files,
            createTime = j.optLong("createTime", System.currentTimeMillis() / 1000),
            finishTime = j.optLong("finishTime", 0),
            seq = j.optLong("seq", 0)
        )
    }
}
