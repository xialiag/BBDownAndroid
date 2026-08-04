package com.bbdown.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * B站 APP API (gRPC-web) 客户端，移植自 BBDown AppHelper.cs
 */
object AppApiClient {

    // ==================== 设备常量 ====================
    private const val NORMAL_API = "https://grpc.biliapi.net/bilibili.app.playurl.v1.PlayURL/PlayView"
    private const val BANGUMI_API = "https://grpc.biliapi.net/bilibili.pgc.gateway.player.v1.Play/PlayView"
    private const val APP_KEY = "1d8b6e7d45233436"
    private const val MOBI_APP = "android"
    private const val PLATFORM = "android"
    private const val BUILD = 7320200
    private const val CHANNEL = "bilibili"
    private const val BRAND = "Xiaomi"
    private const val MODEL = "23127PN0CC"
    private const val OS_VER = "13"
    private const val APP_VER = "7.32.0"
    private const val REGION = "CN"
    private const val LANGUAGE = "zh"
    private const val APP_ID = 1
    private const val DALVIK_VER = "2.1.0"
    private const val CRONET = "2.0.0-alpha01-1"
    private const val NETWORK_OID = "46007"

    @Volatile
    private var buvid: String? = null

    private fun ensureBuvid(): String {
        buvid?.let { return it }
        buvid = UUID.randomUUID().toString().uppercase() + "infoc"
        return buvid!!
    }

    // ==================== Protobuf 编码器 ====================

    private class ProtoWriter {
        private val buf = ByteArrayOutputStream()

        fun toBytes(): ByteArray = buf.toByteArray()

        private fun writeVarintRaw(v: Long) {
            var value = v
            while (value != (value and 0x7FL)) {
                buf.write((value and 0x7F).toInt() or 0x80)
                value = value ushr 7
            }
            buf.write(value.toInt() and 0x7F)
        }

        private fun writeTag(fieldNum: Int, wireType: Int) {
            writeVarintRaw(((fieldNum shl 3) or wireType).toLong())
        }

        fun writeInt64(fieldNum: Int, value: Long) {
            if (value == 0L) return
            writeTag(fieldNum, 0)
            writeVarintRaw(value)
        }

        fun writeInt32(fieldNum: Int, value: Int) {
            if (value == 0) return
            writeTag(fieldNum, 0)
            writeVarintRaw(value.toLong())
        }

        fun writeUInt32(fieldNum: Int, value: Int) {
            if (value == 0) return
            writeTag(fieldNum, 0)
            writeVarintRaw(value.toLong() and 0xFFFFFFFFL)
        }

        fun writeBool(fieldNum: Int, value: Boolean) {
            if (!value) return
            writeTag(fieldNum, 0)
            buf.write(1)
        }

        fun writeString(fieldNum: Int, value: String) {
            if (value.isEmpty()) return
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            writeTag(fieldNum, 2)
            writeVarintRaw(bytes.size.toLong())
            buf.write(bytes)
        }

        fun writeEnum(fieldNum: Int, value: Int) {
            if (value == 0) return
            writeTag(fieldNum, 0)
            writeVarintRaw(value.toLong())
        }

        fun writeMessage(fieldNum: Int, encoded: ByteArray) {
            if (encoded.isEmpty()) return
            writeTag(fieldNum, 2)
            writeVarintRaw(encoded.size.toLong())
            buf.write(encoded)
        }
    }

    // ==================== Protobuf 解码器 ====================

    private class ProtoReader(private val data: ByteArray) {
        private var pos = 0

        fun isEnd(): Boolean = pos >= data.size

        data class Tag(val fieldNum: Int, val wireType: Int)

        fun readTag(): Tag? {
            if (pos >= data.size) return null
            val v = readVarintRaw()
            if (v == 0L) return null
            return Tag((v.toInt() shr 3), (v.toInt() and 0x07))
        }

        fun readVarintRaw(): Long {
            var result = 0L
            var shift = 0
            while (pos < data.size) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }

        fun readInt64(): Long = readVarintRaw()
        fun readInt32(): Int = readVarintRaw().toInt()
        fun readUInt32(): Int = readVarintRaw().toInt()
        fun readBool(): Boolean = readVarintRaw() != 0L
        fun readEnum(): Int = readVarintRaw().toInt()

        fun readString(): String {
            val len = readVarintRaw().toInt()
            val s = String(data, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }

        fun readMessage(): ByteArray {
            val len = readVarintRaw().toInt()
            val bytes = data.copyOfRange(pos, pos + len)
            pos += len
            return bytes
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                0 -> readVarintRaw()
                1 -> { pos += 8 }
                2 -> { pos += readVarintRaw().toInt() }
                5 -> { pos += 4 }
            }
        }
    }

    // ==================== Header Protos 编码 ====================

    private fun encodeDevice(): String {
        val pw = ProtoWriter()
        pw.writeInt32(1, APP_ID)
        pw.writeInt32(2, BUILD)
        pw.writeString(3, ensureBuvid())
        pw.writeString(4, MOBI_APP)
        pw.writeString(5, PLATFORM)
        pw.writeString(7, CHANNEL)
        pw.writeString(8, BRAND)
        pw.writeString(9, MODEL)
        pw.writeString(10, OS_VER)
        return android.util.Base64.encodeToString(pw.toBytes(), android.util.Base64.NO_WRAP)
    }

    private fun encodeMetadata(accessKey: String): String {
        val pw = ProtoWriter()
        if (accessKey.isNotEmpty()) pw.writeString(1, accessKey)
        pw.writeString(2, MOBI_APP)
        pw.writeInt32(4, BUILD)
        pw.writeString(5, CHANNEL)
        pw.writeString(6, ensureBuvid())
        pw.writeString(7, PLATFORM)
        return android.util.Base64.encodeToString(pw.toBytes(), android.util.Base64.NO_WRAP)
    }

    private fun encodeFawkesReq(): String {
        val pw = ProtoWriter()
        pw.writeString(1, APP_KEY)
        pw.writeString(2, "prod")
        pw.writeString(3, UUID.randomUUID().toString())
        return android.util.Base64.encodeToString(pw.toBytes(), android.util.Base64.NO_WRAP)
    }

    private fun encodeLocale(): String {
        // Locale { cLocale: LocaleIds { language, region } }
        val inner = ProtoWriter()
        inner.writeString(1, LANGUAGE)
        inner.writeString(3, REGION)
        val pw = ProtoWriter()
        pw.writeMessage(1, inner.toBytes())
        return android.util.Base64.encodeToString(pw.toBytes(), android.util.Base64.NO_WRAP)
    }

    private fun encodeNetwork(): String {
        val pw = ProtoWriter()
        pw.writeEnum(1, 1) // WIFI
        pw.writeString(3, NETWORK_OID)
        return android.util.Base64.encodeToString(pw.toBytes(), android.util.Base64.NO_WRAP)
    }

    private fun encodePlayViewReq(aid: Long, cid: Long, qn: Long, isBangumi: Boolean, encoding: String): ByteArray {
        val pw = ProtoWriter()
        if (isBangumi) {
            pw.writeInt64(1, aid) // epId
        } else {
            pw.writeInt64(1, aid)
        }
        pw.writeInt64(2, cid)
        pw.writeInt64(3, qn)
        pw.writeInt32(4, 0)  // fnver
        pw.writeInt32(5, 4048) // fnval
        pw.writeUInt32(6, 0)  // download (0=播放)
        pw.writeInt32(7, 2)   // forceHost (2=HTTPS)
        pw.writeBool(8, true) // fourk
        pw.writeString(9, "main.ugc-video-detail.0.0")  // spmid
        pw.writeString(10, "main.my-history.0.0")        // fromSpmid
        // preferCodecType: 0=NOCODE(返回全部编码), 1=CODE264, 2=CODE265, 3=CODEAV1
        // NOCODE 保证列表列全 avc/hevc/av1；否则 APP 端只返回单一编码，8K/杜比视界(HEVC)会缺失
        val codecType = when (encoding.uppercase()) {
            "NOCODE", "ALL" -> 0; "AV1" -> 3; "AVC", "H264" -> 1; else -> 2 // default HEVC (265)
        }
        pw.writeEnum(12, codecType)
        return pw.toBytes()
    }

    // ==================== 构建 gRPC Headers ====================

    private fun buildHeaders(accessKey: String): Map<String, String> {
        val ua = "Dalvik/$DALVIK_VER (Linux; U; Android $OS_VER; $BRAND $MODEL) $APP_VER " +
                "os/android model/$BRAND mobi_app/android build/$BUILD channel/$CHANNEL " +
                "innerVer/$BUILD osVer/$OS_VER network/2 grpc-java-cronet/$CRONET"
        return mapOf(
            "Host" to "grpc.biliapi.net",
            "user-agent" to ua,
            "te" to "trailers",
            "x-bili-fawkes-req-bin" to encodeFawkesReq(),
            "x-bili-metadata-bin" to encodeMetadata(accessKey),
            "authorization" to "identify_v1 $accessKey",
            "x-bili-device-bin" to encodeDevice(),
            "x-bili-network-bin" to encodeNetwork(),
            "x-bili-restriction-bin" to "",
            "x-bili-locale-bin" to encodeLocale(),
            "x-bili-exps-bin" to "",
            "grpc-encoding" to "gzip",
            "grpc-accept-encoding" to "identity,gzip",
            "grpc-timeout" to "17996161u",
            "Content-Type" to "application/grpc",
            "Accept" to "*/*"
        )
    }

    // ==================== gRPC 消息组帧 ====================

    /** 组帧：1-byte 压缩标志 + 4-byte BigEndian 长度 + gzip 载荷 */
    private fun packMessage(data: ByteArray): ByteArray {
        val compressed = gzipCompress(data)
        val out = ByteArrayOutputStream()
        out.write(1) // compressed
        val len = compressed.size
        out.write((len shr 24) and 0xFF)
        out.write((len shr 16) and 0xFF)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(compressed)
        return out.toByteArray()
    }

    /** 解帧：读取前5字节，解压剩余 */
    private fun unpackMessage(data: ByteArray): ByteArray {
        if (data.size < 5) return data
        val compressed = data[0].toInt() == 1
        val size = ((data[1].toInt() and 0xFF) shl 24) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 8) or
                (data[4].toInt() and 0xFF)
        val body = data.copyOfRange(5, 5 + size.coerceAtMost(data.size - 5))
        return if (compressed) gzipDecompress(body) else body
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    // ==================== PlayViewReply 解码 → JSON ====================

    private fun decodeDashItem(reader: ProtoReader): JSONObject? {
        val j = JSONObject()
        var baseUrl = ""
        val backupUrls = JSONArray()
        var bandwidth = 0L
        var codecid = 0
        var id = 0L
        var size = 0L
        while (!reader.isEnd()) {
            val tag = reader.readTag() ?: break
            when (tag.fieldNum) {
                1 -> { id = reader.readUInt32().toLong(); j.put("id", id) }
                2 -> { baseUrl = reader.readString(); j.put("base_url", baseUrl) }
                3 -> { backupUrls.put(reader.readString()); j.put("backup_url", backupUrls) }
                4 -> { bandwidth = reader.readVarintRaw(); j.put("bandwidth", bandwidth) }
                5 -> { codecid = reader.readUInt32(); j.put("codecid", codecid) }
                7 -> { size = reader.readVarintRaw(); j.put("size", size) }
                else -> reader.skipField(tag.wireType)
            }
        }
        return j
    }

    private fun decodeStreamItem(reader: ProtoReader): Pair<Int, JSONObject?> {
        var quality = 0
        var dashVideoJson: JSONObject? = null
        while (!reader.isEnd()) {
            val tag = reader.readTag() ?: break
            when (tag.fieldNum) {
                1 -> { // stream_info
                    val siBytes = reader.readMessage()
                    val siReader = ProtoReader(siBytes)
                    while (!siReader.isEnd()) {
                        val siTag = siReader.readTag() ?: break
                        if (siTag.fieldNum == 1) quality = siReader.readUInt32()
                        else siReader.skipField(siTag.wireType)
                    }
                }
                2 -> { // dash_video
                    val dvBytes = reader.readMessage()
                    val dvReader = ProtoReader(dvBytes)
                    dashVideoJson = decodeDashItem(dvReader)
                }
                else -> reader.skipField(tag.wireType)
            }
        }
        return Pair(quality, dashVideoJson)
    }

    /** 将 PlayViewReply 解码为 BBDown dash JSON 格式 */
    fun convertToDashJson(protobufBytes: ByteArray): String {
        val reader = ProtoReader(protobufBytes)
        val videoTracks = JSONArray()
        val audioTracks = JSONArray()
        var timelength = 0L
        val clips = JSONArray()
        val backgroundAudios = JSONArray()
        val roleAudioList = JSONArray()

        while (!reader.isEnd()) {
            val tag = reader.readTag() ?: break
            when (tag.fieldNum) {
                1 -> { // video_info
                    val viBytes = reader.readMessage()
                    val viReader = ProtoReader(viBytes)
                    while (!viReader.isEnd()) {
                        val viTag = viReader.readTag() ?: break
                        when (viTag.fieldNum) {
                            3 -> timelength = viReader.readVarintRaw() // timelength
                            5 -> { // stream_list
                                val slBytes = viReader.readMessage()
                                val slReader = ProtoReader(slBytes)
                                val (quality, dashVideoJson) = decodeStreamItem(slReader)
                                if (dashVideoJson != null && dashVideoJson.optString("base_url").isNotEmpty()) {
                                    dashVideoJson.put("id", quality)
                                    videoTracks.put(dashVideoJson)
                                }
                            }
                            6 -> { // dash_audio
                                val daBytes = viReader.readMessage()
                                val audioJson = decodeDashItem(ProtoReader(daBytes))
                                if (audioJson != null) {
                                    // APP gRPC 音频用 codecid 而非 codecs，统一转为 M4A
                                    audioJson.put("codecs", "M4A")
                                    audioTracks.put(audioJson)
                                }
                            }
                            7 -> { // dolby
                                val dolbyBytes = viReader.readMessage()
                                val dolbyReader = ProtoReader(dolbyBytes)
                                while (!dolbyReader.isEnd()) {
                                    val dTag = dolbyReader.readTag() ?: break
                                    if (dTag.fieldNum == 2) { // audio
                                        val audioBytes = dolbyReader.readMessage()
                                        val audioJson = decodeDashItem(ProtoReader(audioBytes))
                                        audioJson?.put("codecs", "E-AC-3")
                                        if (audioJson != null) audioTracks.put(audioJson)
                                    } else dolbyReader.skipField(dTag.wireType)
                                }
                            }
                            9 -> { // flac
                                val flacBytes = viReader.readMessage()
                                val flacReader = ProtoReader(flacBytes)
                                while (!flacReader.isEnd()) {
                                    val fTag = flacReader.readTag() ?: break
                                    if (fTag.fieldNum == 2) { // audio
                                        val audioBytes = flacReader.readMessage()
                                        val audioJson = decodeDashItem(ProtoReader(audioBytes))
                                        audioJson?.put("codecs", "FLAC")
                                        if (audioJson != null) audioTracks.put(audioJson)
                                    } else flacReader.skipField(fTag.wireType)
                                }
                            }
                            else -> viReader.skipField(viTag.wireType)
                        }
                    }
                }
                3 -> { // business → clip_info_list
                    val bizBytes = reader.readMessage()
                    val bizReader = ProtoReader(bizBytes)
                    while (!bizReader.isEnd()) {
                        val bizTag = bizReader.readTag() ?: break
                        if (bizTag.fieldNum == 6) { // clip_info
                            val clipBytes = bizReader.readMessage()
                            val clipReader = ProtoReader(clipBytes)
                            val clipJson = JSONObject()
                            while (!clipReader.isEnd()) {
                                val cTag = clipReader.readTag() ?: break
                                when (cTag.fieldNum) {
                                    2 -> clipJson.put("start", clipReader.readInt32())
                                    3 -> clipJson.put("end", clipReader.readInt32())
                                    5 -> clipJson.put("toastText", clipReader.readString())
                                    else -> clipReader.skipField(cTag.wireType)
                                }
                            }
                            clips.put(clipJson)
                        } else bizReader.skipField(bizTag.wireType)
                    }
                }
                7 -> { // play_ext_info → dubbing_info
                    val extBytes = reader.readMessage()
                    val extReader = ProtoReader(extBytes)
                    while (!extReader.isEnd()) {
                        val extTag = extReader.readTag() ?: break
                        if (extTag.fieldNum == 1) { // play_dubbing_info
                            val dubBytes = extReader.readMessage()
                            val dubReader = ProtoReader(dubBytes)
                            while (!dubReader.isEnd()) {
                                val dubTag = dubReader.readTag() ?: break
                                when (dubTag.fieldNum) {
                                    1 -> { // background_audio
                                        val bgBytes = dubReader.readMessage()
                                        val bgReader = ProtoReader(bgBytes)
                                        // AudioMaterialProto - extract audio array (field 7)
                                        while (!bgReader.isEnd()) {
                                            val bgF = bgReader.readTag() ?: break
                                            if (bgF.fieldNum == 7) {
                                                val audioBytes = bgReader.readMessage()
                                                val audioJson = decodeDashItem(ProtoReader(audioBytes))
                                                if (audioJson != null) backgroundAudios.put(audioJson)
                                            } else bgReader.skipField(bgF.wireType)
                                        }
                                    }
                                    2 -> { // role_audio_list
                                        val roleBytes = dubReader.readMessage()
                                        val roleReader = ProtoReader(roleBytes)
                                        // RoleAudioProto - extract audio_material_list (field 4)
                                        while (!roleReader.isEnd()) {
                                            val roleF = roleReader.readTag() ?: break
                                            if (roleF.fieldNum == 4) {
                                                val amBytes = roleReader.readMessage()
                                                val amReader = ProtoReader(amBytes)
                                                val roleJson = JSONObject()
                                                val roleAudios = JSONArray()
                                                while (!amReader.isEnd()) {
                                                    val amF = amReader.readTag() ?: break
                                                    when (amF.fieldNum) {
                                                        1 -> roleJson.put("audio_id", amReader.readString())
                                                        2 -> roleJson.put("title", amReader.readString())
                                                        5 -> roleJson.put("person_name", amReader.readString())
                                                        7 -> {
                                                            val audioBytes = amReader.readMessage()
                                                            val audioJson = decodeDashItem(ProtoReader(audioBytes))
                                                            if (audioJson != null) roleAudios.put(audioJson)
                                                        }
                                                        else -> amReader.skipField(amF.wireType)
                                                    }
                                                }
                                                roleJson.put("audio", roleAudios)
                                                roleAudioList.put(roleJson)
                                            } else roleReader.skipField(roleF.wireType)
                                        }
                                    }
                                    else -> dubReader.skipField(dubTag.wireType)
                                }
                            }
                        } else extReader.skipField(extTag.wireType)
                    }
                }
                else -> reader.skipField(tag.wireType)
            }
        }

        // 构建 BBDown dash JSON
        val dash = JSONObject()
        dash.put("duration", timelength.toInt())
        dash.put("video", videoTracks)
        dash.put("audio", audioTracks)

        val data = JSONObject()
        data.put("timelength", timelength)
        data.put("dash", dash)
        if (clips.length() > 0) data.put("clip_info_list", clips)

        val root = JSONObject()
        root.put("code", 0)
        root.put("message", "0")
        root.put("ttl", 1)
        root.put("data", data)

        // dubbing_info
        if (backgroundAudios.length() > 0 || roleAudioList.length() > 0) {
            val dubbingInfo = JSONObject()
            dubbingInfo.put("background_audio", backgroundAudios)
            dubbingInfo.put("role_audio_list", roleAudioList)
            root.put("dubbing_info", dubbingInfo)
        }

        return root.toString()
    }

    // ==================== HTTP POST (gRPC-web) ====================

    private fun postGrpc(url: String, body: ByteArray, headers: Map<String, String>, cookie: String): ByteArray {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", headers["user-agent"] ?: "")
                headers.forEach { (k, v) -> if (k != "user-agent") setRequestProperty(k, v) }
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                doOutput = true
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream ?: conn.inputStream
            val respBytes = stream.use { it.readBytes() }
            if (code !in 200..399) {
                val msg = String(respBytes, StandardCharsets.UTF_8).take(500)
                throw IllegalStateException("gRPC请求失败 HTTP $code: $msg")
            }
            return respBytes
        } catch (e: Exception) {
            if (e is IllegalStateException) throw e
            throw IllegalStateException("gRPC请求异常: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    // ==================== 公开 API ====================

    data class AppPlayResult(val dashJson: String, val isBangumi: Boolean)

    /**
     * 通过 gRPC APP API 获取播放信息，返回 BBDown dash JSON 格式字符串
     */
    fun getPlayInfo(
        aid: String, cid: String, epid: String,
        isBangumi: Boolean, isCheese: Boolean = false,
        encoding: String = "HEVC", accessKey: String = ""
    ): AppPlayResult {
        val qn = 127L // APP API 固定请求最高画质
        val aidLong = epid.ifEmpty { aid }.toLongOrNull() ?: 0L
        val cidLong = cid.toLongOrNull() ?: 0L

        val effectiveBangumi = isBangumi || isCheese
        val apiUrl = if (effectiveBangumi) BANGUMI_API else NORMAL_API

        val payload = encodePlayViewReq(aidLong, cidLong, qn, effectiveBangumi, encoding)
        val body = packMessage(payload)
        val headers = buildHeaders(accessKey)

        Logger.i("AppApi", "gRPC请求: url=$apiUrl, aid=$aidLong, cid=$cidLong, bangumi=$effectiveBangumi, encoding=$encoding")
        Logger.d("AppApi", "Headers: ${headers.filterKeys { !it.startsWith("x-bili-") || it == "x-bili-device-bin" }}")

        val respBytes = postGrpc(apiUrl, body, headers, Http.cookie)
        Logger.d("AppApi", "gRPC响应: ${respBytes.size} bytes")

        val decoded = unpackMessage(respBytes)
        Logger.d("AppApi", "解帧后: ${decoded.size} bytes")

        val dashJson = convertToDashJson(decoded)
        Logger.d("AppApi", "DashJSON前200字: ${dashJson.take(200)}")

        return AppPlayResult(dashJson, effectiveBangumi)
    }
}
