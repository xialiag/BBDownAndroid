package com.bbdown.app.core

import org.json.JSONObject
import java.util.TreeMap

/**
 * B 站 API 封装，移植自 BBDown 的 URL 解析/信息获取/playurl 解析逻辑。
 * 覆盖 UGC 视频(av/BV) 与番剧(ep/ss) 的 WEB DASH 路径。
 * 包含扫码登录 API。
 */
object BilibiliApi {

    /** 规范化图片URL：确保以 https:// 开头，兼容 WebView file:// 协议加载 */
    private fun normalizePic(url: String): String {
        val u = url.trim()
        if (u.isEmpty()) return ""
        if (u.startsWith("//")) return "https:$u"
        if (u.startsWith("http://")) return u.replace("http://", "https://")
        if (u.startsWith("https://")) return u
        return u
    }

    val QUALITY_MAP = linkedMapOf(
        "127" to "8K 超高清", "126" to "杜比视界", "125" to "HDR 真彩", "120" to "4K 超清",
        "116" to "1080P 高帧率", "112" to "1080P 高码率", "80" to "1080P 高清",
        "74" to "720P 高帧率", "64" to "720P 高清", "32" to "480P 清晰", "16" to "360P 流畅"
    )

    private var mixinKey: String? = null

    data class ParsedId(val type: String, val aid: String = "", val epId: String = "", val bvid: String = "")

    data class QrCodeInfo(val url: String, val qrcodeKey: String, val image: String = "")

    data class LoginResult(val code: Int, val message: String, val cookie: String = "", val refreshCToken: String = "", val accessToken: String = "")

    /** 确保 WBI mixinKey 已加载 */
    fun ensureWbi() {
        if (mixinKey != null) return
        try {
            val json = JSONObject(Http.get("https://api.bilibili.com/x/web-interface/nav"))
            val wbi = json.getJSONObject("data").getJSONObject("wbi_img")
            val imgKey = Wbi.extractKeyFromUrl(wbi.getString("img_url"))
            val subKey = Wbi.extractKeyFromUrl(wbi.getString("sub_url"))
            mixinKey = Wbi.getMixinKey(imgKey + subKey)
        } catch (e: Exception) {
            mixinKey = ""
        }
    }

    private fun wbiSign(params: Map<String, String>): String {
        val sorted = TreeMap(params)
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(k).append('=').append(v)
        }
        val wrid = Wbi.sign(sb.toString(), mixinKey ?: "")
        return "$sb&w_rid=$wrid"
    }

    // ==================== UP主认证信息缓存 ====================
    // 内存缓存：mid → Triple<officialType, vipType, vipStatus>
    // 从搜索结果、关注列表、card/acc/info API 等多个来源积累，
    // 收藏夹页面优先读缓存，避免重复请求触发 B站 -352 风控
    private val authInfoCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, Int, Int>>()

    // B站指纹 cookie 缓存：buvid3/buvid4/b_lsid/_uuid/b_nut
    // 412 风控根因是缺少这些指纹 cookie，且 Http.cookie 与 extraCookie 拼接时缺分号
    @Volatile
    private var fingerprintCookie: String? = null

    /** 生成完整的B站指纹 cookie 字符串（buvid3 + buvid4 + b_lsid + _uuid + b_nut）
     *  优先从 SPI API 获取 buvid3/buvid4，失败则本地生成 */
    private fun ensureFingerprintCookie(): String {
        fingerprintCookie?.let { return it }
        val ts = System.currentTimeMillis() / 1000
        val sb = StringBuilder()

        // 1. buvid3 + buvid4：优先 SPI API
        var buvid3 = ""
        var buvid4 = ""
        try {
            val resp = Http.get("https://api.bilibili.com/x/frontend/finger/spi")
            val json = JSONObject(resp)
            if (json.optInt("code") == 0) {
                val data = json.optJSONObject("data")
                buvid3 = data?.optString("b_3") ?: ""
                buvid4 = data?.optString("b_4") ?: ""
            }
        } catch (e: Exception) {
            Logger.w("Fav", "SPI API 获取指纹失败: ${e.message}")
        }
        // SPI 失败则本地生成
        if (buvid3.isEmpty()) {
            buvid3 = java.util.UUID.randomUUID().toString().uppercase() + "infoc"
        }
        if (buvid4.isEmpty()) {
            val uuid1 = java.util.UUID.randomUUID().toString().uppercase()
            val uuid2 = java.util.UUID.randomUUID().toString().uppercase().replace("-", "").take(16)
            buvid4 = "$uuid1-$uuid2-022062006-"
        }

        // 2. b_lsid：格式 {8hex}_{8hex} 大写
        val lsid1 = (0 until 8).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")
        val lsid2 = (0 until 8).map { "0123456789ABCDEF"[(Math.random() * 16).toInt()] }.joinToString("")
        val bLsid = "${lsid1}_${lsid2}"

        // 3. _uuid：B站格式，较长的随机串
        val uuidPart = java.util.UUID.randomUUID().toString().uppercase()
        val tsHex = ts.toString(16).uppercase()
        val _uuid = "${uuidPart}${tsHex}-${(ts + 1000).toString(16).toUpperCase()}-1"

        sb.append("buvid3=").append(buvid3)
            .append("; buvid4=").append(buvid4)
            .append("; b_lsid=").append(bLsid)
            .append("; _uuid=").append(_uuid)
            .append("; b_nut=").append(ts)

        val cookie = sb.toString()
        fingerprintCookie = cookie
        Logger.d("Fav", "生成指纹cookie: buvid3=${buvid3.take(16)}... b_lsid=$bLsid")
        return cookie
    }

    /** 缓存UP主认证信息（从任何包含认证数据的 API 响应中提取并积累）
     *  officialType=-1 且 vipType=0 表示无有效数据，不缓存 */
    fun cacheAuthInfo(mid: String, officialType: Int, vipType: Int, vipStatus: Int) {
        if (mid.isEmpty()) return
        if (officialType == -1 && vipType == 0 && vipStatus == 0) return
        authInfoCache[mid] = Triple(officialType, vipType, vipStatus)
    }

    // ==================== 扫码登录 ====================

    /** 获取扫码登录二维码（本地生成 QR 图片）
     *  移植自 BBDown BBDownLoginUtil.LoginWEB
     */
    fun getQrCode(): QrCodeInfo {
        Logger.i("QrLogin", "开始获取二维码...")
        val loginUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header"
        Logger.d("QrLogin", "请求: $loginUrl")
        val resp = Http.get(loginUrl)
        Logger.d("QrLogin", "响应: ${resp.take(300)}")
        val json = JSONObject(resp)
        val code = json.optInt("code", -1)
        if (code != 0) {
            Logger.e("QrLogin", "API返回错误: code=$code, msg=${json.optString("message")}")
            throw IllegalStateException("二维码API错误: ${json.optString("message", "未知错误")}")
        }
        val data = json.getJSONObject("data")
        val url = data.getString("url")
        val qrcodeKey = data.optString("qrcode_key", "")
        Logger.i("QrLogin", "二维码URL: $url")
        Logger.i("QrLogin", "qrcode_key: $qrcodeKey")
        Logger.d("QrLogin", "开始本地生成QR图片...")
        val image = QrCodeUtil.generateBase64Png(url, 240)
        Logger.i("QrLogin", "QR图片生成成功, base64长度=${image.length}")
        return QrCodeInfo(url = url, qrcodeKey = qrcodeKey, image = image)
    }

    /** 轮询扫码登录状态
     *  移植自 BBDown BBDownLoginUtil.GetLoginStatusAsync
     *  Cookie 从响应体 data.url 提取（与 BBDown 一致），而非响应头
     */
    fun pollQrLogin(qrcodeKey: String): LoginResult {
        val queryUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey&source=main-fe-header"
        Logger.d("QrLogin", "轮询: $queryUrl")
        val resp = Http.get(queryUrl)
        Logger.d("QrLogin", "轮询响应: ${resp.take(300)}")
        val json = JSONObject(resp)
        val data = json.optJSONObject("data") ?: JSONObject()
        val code = data.optInt("code", -1)
        val message = data.optString("message", json.optString("message", ""))

        if (code == 0) {
            Logger.i("QrLogin", "登录成功! 开始提取Cookie...")
            // BBDown 方式：从 data.url 的 query 参数提取 cookie
            // data.url 形如: https://passport.biligame.com/crossDomain?DedeUserID=xxx&SESSDATA=xxx&bili_jct=xxx&...
            val crossDomainUrl = data.optString("url", "")
            val refresh = data.optString("refresh_token", "")
            Logger.d("QrLogin", "crossDomain URL: $crossDomainUrl")

            val cookieStr = if (crossDomainUrl.contains("?")) {
                // 截取 ? 之后的 query string，将 & 替换为 ; ，逗号转义
                crossDomainUrl.substringAfter("?")
                    .replace("&", ";")
                    .replace(",", "%2C")
            } else {
                ""
            }
            Logger.i("QrLogin", "提取的Cookie: ${cookieStr.take(80)}...")
            return LoginResult(code = 0, message = "登录成功", cookie = cookieStr, refreshCToken = refresh)
        }
        Logger.d("QrLogin", "轮询状态: code=$code, msg=$message")
        return LoginResult(code = code, message = message)
    }

    // ==================== TV端授权登录 ====================

    private const val TV_APPKEY = "4409e2ce8ffd12b8"
    private const val TV_APPSEC = "59b43e04ad6965f34319062b478f83dd"

    /** TV端 appkey 签名：参数排序+urlencode+appsec 后 MD5 */
    private fun tvSign(params: Map<String, String>): String {
        val sorted = params.toSortedMap()
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(java.net.URLEncoder.encode(k, "UTF-8"))
                .append('=')
                .append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val toHash = sb.toString() + TV_APPSEC
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(toHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** TV端获取扫码登录二维码
     *  API: POST /x/passport-tv-login/qrcode/auth_code
     *  返回的 url 用于生成二维码，用户用B站APP扫码授权
     */
    fun getTvQrCode(): QrCodeInfo {
        Logger.i("TvLogin", "开始获取TV端二维码...")
        val params = LinkedHashMap<String, String>()
        params["appkey"] = TV_APPKEY
        params["local_id"] = "0"
        params["ts"] = "0"
        params["sign"] = tvSign(params)

        val resp = Http.postForm(
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code",
            params
        )
        Logger.d("TvLogin", "auth_code响应: ${resp.take(300)}")
        val json = JSONObject(resp)
        val code = json.optInt("code", -1)
        if (code != 0) {
            Logger.e("TvLogin", "API返回错误: code=$code, msg=${json.optString("message")}")
            throw IllegalStateException("TV二维码API错误: ${json.optString("message", "未知错误")}")
        }
        val data = json.getJSONObject("data")
        val url = data.getString("url")
        val authCode = data.optString("auth_code", "")
        Logger.i("TvLogin", "TV二维码URL: $url, auth_code: $authCode")
        val image = QrCodeUtil.generateBase64Png(url, 240)
        Logger.i("TvLogin", "QR图片生成成功")
        return QrCodeInfo(url = url, qrcodeKey = authCode, image = image)
    }

    /** TV端轮询扫码登录状态
     *  API: POST /x/passport-tv-login/qrcode/poll
     *  成功返回 access_token + cookie_info（SESSDATA等）
     */
    fun pollTvLogin(authCode: String): LoginResult {
        val params = LinkedHashMap<String, String>()
        params["appkey"] = TV_APPKEY
        params["auth_code"] = authCode
        params["local_id"] = "0"
        params["ts"] = "0"
        params["sign"] = tvSign(params)

        val resp = Http.postForm(
            "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll",
            params
        )
        Logger.d("TvLogin", "poll响应: ${resp.take(300)}")
        val json = JSONObject(resp)
        val code = json.optInt("code", -1)
        val message = json.optString("message", "")

        if (code == 0) {
            Logger.i("TvLogin", "TV登录成功!")
            val data = json.optJSONObject("data") ?: JSONObject()
            // 从 cookie_info 提取 Cookie（SESSDATA, bili_jct, DedeUserID）
            val cookieInfo = data.optJSONObject("cookie_info")
            val cookieStr = StringBuilder()
            if (cookieInfo != null) {
                val cookies = cookieInfo.optJSONArray("cookies")
                if (cookies != null) {
                    for (i in 0 until cookies.length()) {
                        val c = cookies.getJSONObject(i)
                        if (cookieStr.isNotEmpty()) cookieStr.append(';')
                        cookieStr.append(c.optString("name")).append('=').append(c.optString("value"))
                    }
                }
            }
            val accessToken = data.optString("access_token", "")
            val refreshToken = data.optString("refresh_token", "")
            Logger.i("TvLogin", "提取Cookie: ${cookieStr.toString().take(80)}..., access_token长度=${accessToken.length}")
            return LoginResult(
                code = 0,
                message = "登录成功",
                cookie = cookieStr.toString(),
                refreshCToken = refreshToken,
                accessToken = accessToken
            )
        }
        Logger.d("TvLogin", "轮询状态: code=$code, msg=$message")
        return LoginResult(code = code, message = message)
    }

    // ==================== 字幕 / 弹幕 / 封面 ====================

    /** 获取视频可用字幕列表 */
    fun getSubtitles(aid: String, cid: String): List<SubtitleInfo> {
        val result = ArrayList<SubtitleInfo>()
        try {
            // x/player/v2 的字幕字段需要 wbi 签名(+cookie) 才会返回, 不带签名永远为空
            ensureWbi()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val params = LinkedHashMap<String, String>()
            params["aid"] = aid
            params["cid"] = cid
            params["wts"] = ts
            val resp = Http.get("https://api.bilibili.com/x/player/v2?${wbiSign(params)}")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return result
            val subObj = data.optJSONObject("subtitle") ?: return result
            val subs = subObj.optJSONArray("subtitles") ?: return result
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val aiType = s.optInt("ai_type", 0)
                result.add(SubtitleInfo(
                    lan = s.optString("lan"),
                    lanDoc = s.optString("lan_doc"),
                    subtitleUrl = s.optString("subtitle_url"),
                    ai = aiType != 0
                ))
            }
        } catch (_: Exception) {}
        return result
    }

    /** 下载字幕并转换为 SRT 格式（参考 DotNet BBDown 的 ConvertSubFromJson）
     *  B站字幕 JSON 结构: { "body": [ { "from": 12.34, "to": 15.67, "content": "..." } ] }
     *  - 缺少 from 字段时起始时间用 0.0
     *  - 缺少 content 字段时该行内容留空
     *  - 时间格式: hh:mm:ss,fff（SRT 标准，逗号分隔毫秒）
     */
    fun downloadSubtitleAsSrt(subtitleUrl: String, duration: Int = 0): String {
        var url = subtitleUrl
        if (url.startsWith("//")) url = "https:$url"
        val resp = Http.get(url)
        val json = JSONObject(resp)
        val body = json.optJSONArray("body") ?: return ""
        // 校验字幕时间范围: 明显超出视频时长(+10s容差)说明该字幕不是这个视频的
        // (B站AI字幕槽位有概率串台, 会返回其他视频的ASR内容), 跳过不保存
        var maxTo = 0.0
        for (i in 0 until body.length()) {
            val to = body.getJSONObject(i).optDouble("to", 0.0)
            if (to > maxTo) maxTo = to
        }
        if (duration > 0 && maxTo > duration + 10) {
            Logger.w("Subtitle", "字幕时间范围(${String.format("%.1f", maxTo)}s)超出视频时长(${duration}s), 疑似与视频无关的字幕, 已跳过")
            return ""
        }
        val sb = StringBuilder()
        for (i in 0 until body.length()) {
            val item = body.getJSONObject(i)
            // DotNet 版: 缺少 from 时用 0.0
            val from = if (item.has("from")) item.optDouble("from", 0.0) else 0.0
            val to = item.optDouble("to", 0.0)
            val content = if (item.has("content")) item.optString("content", "") else ""
            sb.append(i + 1).append('\n')
            sb.append(formatSrtTime(from)).append(" --> ").append(formatSrtTime(to)).append('\n')
            sb.append(content).append("\n\n")
        }
        return sb.toString()
    }

    /** 字幕语言码映射（移植自原版 BBDown SubUtil.GetSubtitleCode）
     * @return Pair<ISO639-2码, 显示名称> */
    fun getSubtitleCode(key: String): Pair<String, String> {
        // zh-hans => zh-Hans
        var k = key
        val regex = Regex("-[a-z]")
        val match = regex.find(k)
        if (match != null) {
            val v = match.value
            k = k.replace(v, v.uppercase())
        }
        return when (k) {
            "ai-Zh" -> Pair("chi", "中文（简体, AI识别）")
            "ai-En" -> Pair("eng", "English(generated by ai)")
            "zh-CN" -> Pair("chi", "中文（简体）")
            "zh-HK" -> Pair("chi", "中文（香港繁體）")
            "zh-Hans" -> Pair("chi", "中文（简体）")
            "zh-TW" -> Pair("chi", "中文（台灣繁體）")
            "zh-Hant" -> Pair("chi", "中文（繁體）")
            "en-US" -> Pair("eng", "English(USA)")
            "ja" -> Pair("jpn", "日本語")
            "ko" -> Pair("kor", "한국어")
            "en" -> Pair("eng", "English")
            "en-CA" -> Pair("eng", "English(Canada)")
            "en-IE" -> Pair("eng", "English(Ireland)")
            "en-GB" -> Pair("eng", "English(UK)")
            "de" -> Pair("ger", "Deutsch")
            "fr" -> Pair("fre", "Français")
            "ru" -> Pair("rus", "Русский")
            "es" -> Pair("spa", "Español")
            "pt" -> Pair("por", "Português")
            "pt-BR" -> Pair("por", "Português(brasil)")
            "pt-PT" -> Pair("por", "Português(portugal)")
            "it" -> Pair("ita", "Italiano")
            "th" -> Pair("tha", "ไทย")
            "vi" -> Pair("vie", "Tiếng Việt")
            "id" -> Pair("ind", "Indonesia")
            "ms" -> Pair("may", "Melayu")
            "tr" -> Pair("tur", "Türkçe")
            "ar" -> Pair("ara", "العربية")
            "hi" -> Pair("hin", "हिन्दी")
            "pl" -> Pair("pol", "Polski")
            "uk" -> Pair("ukr", "Українська")
            "sv" -> Pair("swe", "Svenska")
            "nl" -> Pair("dut", "Nederlands")
            "cs" -> Pair("cze", "čeština")
            "hu" -> Pair("hun", "Magyar")
            "da" -> Pair("dan", "Dansk")
            "fi" -> Pair("fin", "Suomi")
            "no" -> Pair("nor", "norsk språk")
            "el" -> Pair("gre", "Ελληνικά")
            "he" -> Pair("heb", "שפה עברית")
            "bg" -> Pair("bul", "български")
            "ro" -> Pair("rum", "Română")
            "sr" -> Pair("srp", "Српски")
            "hr" -> Pair("hrv", "Hrvatska")
            "sk" -> Pair("slo", "slovenský")
            "sl" -> Pair("slv", "Slovenščina")
            "lt" -> Pair("lit", "lietuvių kalba")
            "lv" -> Pair("lav", "latviešu valoda")
            "et" -> Pair("est", "Eestlane")
            "fa" -> Pair("per", "فارسی")
            "yue" -> Pair("chi", "粵語")
            "yue-HK" -> Pair("chi", "粵語（中國香港）")
            else -> Pair("und", "Undetermined")
        }
    }

    /** 获取弹幕 XML */
    fun getDanmakuXml(cid: String): String {
        return Http.get("https://comment.bilibili.com/${cid}.xml")
    }

    /** 下载封面图片字节 */
    fun downloadCover(coverUrl: String): ByteArray {
        return Http.getBytes(coverUrl)
    }

    /** 强制 HTTP 替换（BBDown 默认行为） */
    fun forceHttp(url: String): String {
        return url.replace("https://", "http://")
    }

    /** SRT 时间格式化: hh:mm:ss,fff（使用四舍五入避免精度丢失） */
    private fun formatSrtTime(seconds: Double): String {
        val totalMs = Math.round(seconds * 1000)
        val ms = totalMs % 1000
        val totalSec = totalMs / 1000
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    // ==================== URL 解析 ====================

    fun parseUrl(input: String): ParsedId {
        var s = input.trim()
        if (s.startsWith("http", ignoreCase = true)) {
            if (s.contains("b23.tv")) {
                val loc = Http.getLocation(s)
                if (loc != s) s = loc
            }
            val avMatch = Regex("av(\\d+)").find(s)
            val bvMatch = Regex("[Bb][Vv]1(\\w+)").find(s)
            val epMatch = Regex("/ep(\\d+)").find(s)
            val ssMatch = Regex("/ss(\\d+)").find(s)
            when {
                s.contains("video/av", ignoreCase = true) && avMatch != null ->
                    return ParsedId("av", aid = avMatch.groupValues[1])
                s.contains("video/bv", ignoreCase = true) && bvMatch != null ->
                    return ParsedId("av", aid = BvConverter.decode(bvMatch.groupValues[1]).toString(),
                        bvid = "BV1" + bvMatch.groupValues[1])
                // 课程(cheese/pugv)检测 — 必须在番剧 ep/ss 之前判断
                s.contains("/cheese/", ignoreCase = true) -> {
                    val cheeseEpMatch = Regex("/ep(\\d+)").find(s)
                    val cheeseSsMatch = Regex("/ss(\\d+)").find(s)
                    when {
                        cheeseEpMatch != null ->
                            return ParsedId("cheese", epId = cheeseEpMatch.groupValues[1])
                        cheeseSsMatch != null -> {
                            val epId = resolveCheeseSeasonToEp(cheeseSsMatch.groupValues[1])
                            return ParsedId("cheese", epId = epId)
                        }
                    }
                }
                s.contains("/ep") && epMatch != null ->
                    return ParsedId("ep", epId = epMatch.groupValues[1])
                s.contains("/ss") && ssMatch != null -> {
                    val epId = resolveSeasonToEp(ssMatch.groupValues[1])
                    return ParsedId("ep", epId = epId)
                }
                bvMatch != null ->
                    return ParsedId("av", aid = BvConverter.decode(bvMatch.groupValues[1]).toString(),
                        bvid = "BV1" + bvMatch.groupValues[1])
                avMatch != null ->
                    return ParsedId("av", aid = avMatch.groupValues[1])
            }
            throw IllegalArgumentException("无法识别的链接")
        } else {
            val bvMatch = Regex("[Bb][Vv]1(\\w+)").matchEntire(s)
            if (bvMatch != null) return ParsedId("av", aid = BvConverter.decode(bvMatch.groupValues[1]).toString(),
                bvid = "BV1" + bvMatch.groupValues[1])
            if (s.startsWith("BV", ignoreCase = true)) return ParsedId("av",
                aid = BvConverter.decode(s.substring(3)).toString(), bvid = s)
            if (s.startsWith("av", ignoreCase = true)) return ParsedId("av", aid = s.substring(2))
            if (s.startsWith("cheese/", ignoreCase = true)) {
                if (s.contains("/ep", ignoreCase = true)) {
                    val epId = Regex("/ep(\\d+)").find(s)?.groupValues?.get(1) ?: ""
                    if (epId.isNotEmpty()) return ParsedId("cheese", epId = epId)
                }
                if (s.contains("/ss", ignoreCase = true)) {
                    val ssId = Regex("/ss(\\d+)").find(s)?.groupValues?.get(1) ?: ""
                    if (ssId.isNotEmpty()) return ParsedId("cheese", epId = resolveCheeseSeasonToEp(ssId))
                }
            }
            if (s.startsWith("ep", ignoreCase = true)) return ParsedId("ep", epId = s.substring(2))
            if (s.startsWith("ss", ignoreCase = true)) return ParsedId("ep", epId = resolveSeasonToEp(s.substring(2)))
            throw IllegalArgumentException("无法识别的输入")
        }
    }

    private fun resolveSeasonToEp(ssId: String): String {
        val json = JSONObject(Http.get("https://api.bilibili.com/pgc/view/web/season?season_id=$ssId"))
        val result = json.optJSONObject("result") ?: throw IllegalStateException("获取番剧信息失败(code=${json.optInt("code")})")
        val episodes = result.optJSONArray("episodes") ?: throw IllegalStateException("番剧无剧集")
        if (episodes.length() == 0) throw IllegalStateException("番剧无剧集")
        return episodes.getJSONObject(0).getString("id")
    }

    /** 课程: 通过 season_id 获取第一个 epId（pugv API，与番剧不互通） */
    private fun resolveCheeseSeasonToEp(ssId: String): String {
        val json = JSONObject(Http.get("https://api.bilibili.com/pugv/view/web/season?season_id=$ssId"))
        val data = json.optJSONObject("data") ?: throw IllegalStateException("获取课程信息失败(code=${json.optInt("code")})")
        val episodes = data.optJSONArray("episodes") ?: throw IllegalStateException("课程无剧集")
        if (episodes.length() == 0) throw IllegalStateException("课程无剧集")
        return episodes.getJSONObject(0).getString("id")
    }

    /** 课程(pugv)信息获取，API: /pugv/view/web/season?ep_id=XXX
     *  注意：pugv API 返回数据在 "data" 字段下，与番剧 pgc API 的 "result" 不同 */
    private fun getCheeseInfo(epId: String): VideoInfo {
        val json = JSONObject(Http.get("https://api.bilibili.com/pugv/view/web/season?ep_id=$epId"))
        val result = json.getJSONObject("data")
        val pages = ArrayList<PageInfo>()
        val arr = result.getJSONArray("episodes")
        var idx = 1
        for (i in 0 until arr.length()) {
            val ep = arr.getJSONObject(i)
            val t = (ep.optString("title") + " " + ep.optString("long_title")).trim()
            pages.add(PageInfo(
                index = idx++,
                aid = ep.optString("aid"),
                cid = ep.optString("cid"),
                epid = ep.optString("id"),
                title = t,
                duration = ep.optInt("duration", 0)
            ))
        }
        return VideoInfo(
            title = result.optString("title").trim(),
            desc = result.optString("subtitle").trim(),
            pic = normalizePic(result.optString("cover")),
            pubTime = 0,
            isBangumi = true,
            isCheese = true,
            pages = pages
        )
    }

    fun getVideoInfo(parsed: ParsedId): VideoInfo {
        if (parsed.type == "av") {
            // 先用 bvid 查询（更可靠），失败再回退到 aid
            if (parsed.bvid.isNotEmpty()) {
                return try {
                    getUgcInfoByBvid(parsed.bvid)
                } catch (e: Exception) {
                    Logger.w("Api", "bvid查询失败，回退aid: ${e.message}")
                    getUgcInfo(parsed.aid)
                }
            }
            return getUgcInfo(parsed.aid)
        }
        if (parsed.type == "cheese") {
            return getCheeseInfo(parsed.epId)
        }
        return getBangumiInfo(parsed.epId)
    }

    /** 通过 bvid 获取视频信息（更可靠，避免 BV decode 出错） */
    fun getUgcInfoByBvid(bvid: String): VideoInfo {
        val resp = Http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        if (resp.trimStart().startsWith("<")) {
            throw IllegalStateException("API返回了HTML而非JSON，可能是请求被拦截(bvid=$bvid)")
        }
        val json = JSONObject(resp)
        val data = json.optJSONObject("data") ?: throw IllegalStateException("获取视频信息失败: code=${json.optInt("code")}, msg=${json.optString("message")}")
        val pages = ArrayList<PageInfo>()
        val arr = data.getJSONArray("pages")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            pages.add(PageInfo(
                index = p.getInt("page"),
                aid = data.optString("aid"),
                cid = p.optString("cid"),
                title = p.optString("part").trim(),
                duration = p.optInt("duration")
            ))
        }
        val owner = data.optJSONObject("owner")
        val stat = data.optJSONObject("stat")
        return VideoInfo(
            title = data.optString("title").trim(),
            desc = data.optString("desc").trim(),
            pic = normalizePic(data.optString("pic")),
            pubTime = data.optLong("pubdate"),
            upperName = owner?.optString("name")?.trim() ?: "",
            ownerMid = owner?.optString("mid")?.trim() ?: "",
            isBangumi = false,
            bvid = data.optString("bvid"),
            play = stat?.optInt("view", 0) ?: 0,
            danmaku = stat?.optInt("danmaku", 0) ?: 0,
            duration = data.optInt("duration", 0),
            ownerFace = owner?.let { normalizePic(it.optString("face")) } ?: "",
            officialType = owner?.let { parseOfficialType(it) } ?: -1,
            vipType = owner?.let { parseVipType(it) } ?: 0,
            vipStatus = owner?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0,
            pages = pages
        )
    }

    private fun getUgcInfo(aid: String): VideoInfo {
        val resp = Http.get("https://api.bilibili.com/x/web-interface/view?aid=$aid")
        if (resp.trimStart().startsWith("<")) {
            throw IllegalStateException("API返回了HTML而非JSON，可能是请求被拦截或参数错误(aid=$aid)")
        }
        val json = JSONObject(resp)
        val data = json.optJSONObject("data") ?: throw IllegalStateException("获取视频信息失败: code=${json.optInt("code")}, msg=${json.optString("message")}")
        val pages = ArrayList<PageInfo>()
        val arr = data.getJSONArray("pages")
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            pages.add(PageInfo(
                index = p.getInt("page"),
                aid = aid,
                cid = p.optString("cid"),
                title = p.optString("part").trim(),
                duration = p.optInt("duration")
            ))
        }
        val owner = data.optJSONObject("owner")
        val stat = data.optJSONObject("stat")
        return VideoInfo(
            title = data.optString("title").trim(),
            desc = data.optString("desc").trim(),
            pic = normalizePic(data.optString("pic")),
            pubTime = data.optLong("pubdate"),
            upperName = owner?.optString("name")?.trim() ?: "",
            ownerMid = owner?.optString("mid")?.trim() ?: "",
            isBangumi = false,
            bvid = data.optString("bvid"),
            play = stat?.optInt("view", 0) ?: 0,
            danmaku = stat?.optInt("danmaku", 0) ?: 0,
            duration = data.optInt("duration", 0),
            ownerFace = owner?.let { normalizePic(it.optString("face")) } ?: "",
            officialType = owner?.let { parseOfficialType(it) } ?: -1,
            vipType = owner?.let { parseVipType(it) } ?: 0,
            vipStatus = owner?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0,
            pages = pages
        )
    }

    private fun getBangumiInfo(epId: String): VideoInfo {
        val json = JSONObject(Http.get("https://api.bilibili.com/pgc/view/web/season?ep_id=$epId"))
        val result = json.getJSONObject("result")
        val pages = ArrayList<PageInfo>()
        val arr = result.getJSONArray("episodes")
        var idx = 1
        for (i in 0 until arr.length()) {
            val ep = arr.getJSONObject(i)
            if (ep.optString("badge") == "预告") continue
            val t = (ep.optString("title") + " " + ep.optString("long_title")).trim()
            pages.add(PageInfo(
                index = idx++,
                aid = ep.optString("aid"),
                cid = ep.optString("cid"),
                epid = ep.optString("id"),
                title = t,
                duration = 0
            ))
        }
        return VideoInfo(
            title = result.optString("title").trim(),
            desc = result.optString("evaluate").trim(),
            pic = normalizePic(result.optString("cover")),
            pubTime = 0,
            isBangumi = true,
            pages = pages
        )
    }

    fun getPlayInfo(aid: String, cid: String, epid: String, isBangumi: Boolean, qn: String = "127", isCheese: Boolean = false): PlayInfo {
        val apiType = Http.apiType
        Logger.i("PlayInfo", "获取播放信息: apiType=$apiType, aid=$aid, cid=$cid, isBangumi=$isBangumi, isCheese=$isCheese")
        // APP/TV API 需要 access_key(TV token)，若无则自动回退到 WEB API(Cookie+WBI签名)
        val effectiveType = if ((apiType == "app" || apiType == "tv") && Http.tvToken.isEmpty()) {
            Logger.w("PlayInfo", "API类型=$apiType 但无TV token(access_key)，自动回退到WEB API")
            "web"
        } else {
            apiType
        }
        return when (effectiveType) {
            "tv" -> getPlayInfoTv(aid, cid, epid, isBangumi, qn, isCheese)
            "app" -> getPlayInfoApp(aid, cid, epid, isBangumi, qn, isCheese)
            "intl" -> getPlayInfoIntl(aid, cid, epid, qn)
            else -> getPlayInfoWeb(aid, cid, epid, isBangumi, qn, isCheese)
        }
    }

    /** WEB 端 playurl（WBI 签名 + Cookie 鉴权） */
    private fun getPlayInfoWeb(aid: String, cid: String, epid: String, isBangumi: Boolean, qn: String, isCheese: Boolean): PlayInfo {
        ensureWbi()
        val ts = (System.currentTimeMillis() / 1000).toString()
        val params = LinkedHashMap<String, String>()
        params["avid"] = aid
        params["cid"] = cid
        params["fnval"] = "4048"
        params["fnver"] = "0"
        params["fourk"] = "1"
        params["otype"] = "json"
        params["qn"] = qn
        if (isBangumi) {
            params["module"] = "bangumi"
            params["ep_id"] = epid
            params["session"] = ""
        }
        if (Http.cookie.isBlank()) params["try_look"] = "1"
        params["wts"] = ts

        val prefix = if (isBangumi) "https://api.bilibili.com/pgc/player/web/playurl?"
                     else "https://api.bilibili.com/x/player/wbi/playurl?"
        var query = wbiSign(params)
        var apiUrl = prefix + query
        // 课程接口：将 /pgc/ 替换为 /pugv/（与原版 BBDown 一致）
        if (isCheese) apiUrl = apiUrl.replace("/pgc/", "/pugv/")
        val extraCookie = if (isBangumi) ";CURRENT_FNVAL=4048;" else ""
        val resp = Http.get(apiUrl, extraCookie = extraCookie)
        return parseDash(resp)
    }

    /** TV 端 playurl（access_key + TV appkey 签名）
     *  移植自 BBDown Parser.cs GetPlayJsonAsync tvApi 分支
     */
    private fun getPlayInfoTv(aid: String, cid: String, epid: String, isBangumi: Boolean, qn: String, isCheese: Boolean): PlayInfo {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sb = StringBuilder()
        if (Http.tvToken.isNotEmpty()) sb.append("access_key=${Http.tvToken}&")
        sb.append("appkey=4409e2ce8ffd12b8&build=106500&cid=$cid&device=android")
        if (isBangumi) sb.append("&ep_id=$epid&expire=0")
        sb.append("&fnval=4048&fnver=0&fourk=1&mid=0&mobi_app=android_tv_yst")
        sb.append("&object_id=$aid&platform=android&playurl_type=1&qn=$qn&ts=$ts")
        val paramStr = sb.toString()
        val sign = tvSignMd5(paramStr)
        var prefix = if (isBangumi) "https://api.snm0516.aisee.tv/pgc/player/api/playurltv?"
                     else "https://api.snm0516.aisee.tv/x/tv/playurl?"
        if (isCheese) prefix = prefix.replace("/pgc/", "/pugv/")
        val apiUrl = "$prefix$paramStr&sign=$sign"
        Logger.d("PlayInfo", "TV API: $apiUrl")
        val resp = Http.get(apiUrl)
        return parseDash(resp)
    }

    /** APP 端 playurl（gRPC 协议，移植自 BBDown AppHelper.cs） */
    private fun getPlayInfoApp(aid: String, cid: String, epid: String, isBangumi: Boolean, qn: String, isCheese: Boolean): PlayInfo {
        val encoding = if (isBangumi || isCheese) "HEVC" else "AVC"
        val result = AppApiClient.getPlayInfo(
            aid = aid, cid = cid, epid = epid,
            isBangumi = isBangumi, isCheese = isCheese,
            encoding = encoding,
            accessKey = Http.tvToken
        )
        return parseDash(result.dashJson)
    }

    /** 国际版 playurl
     *  移植自 BBDown Parser.cs GetPlayJsonAsync intl 分支
     */
    private fun getPlayInfoIntl(aid: String, cid: String, epid: String, qn: String): PlayInfo {
        val sb = StringBuilder()
        if (Http.tvToken.isNotEmpty()) sb.append("access_key=${Http.tvToken}&")
        sb.append("aid=$aid&cid=$cid&ep_id=$epid&platform=android&prefer_code_type=0&qn=$qn")
        val apiUrl = "https://api.biliintl.com/intl/gateway/v2/ogv/playurl?$sb"
        Logger.d("PlayInfo", "INTL API: $apiUrl")
        val resp = Http.get(apiUrl)
        return parseIntlDash(resp)
    }

    /** TV/APP 端 playurl 签名：参数直接拼接 + appsec 后 MD5（不 urlencode） */
    private fun tvSignMd5(paramStr: String): String {
        val toHash = paramStr + TV_APPSEC
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(toHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 解析国际版 playurl 响应（stream_list 结构） */
    private fun parseIntlDash(jsonStr: String): PlayInfo {
        val result = PlayInfo()
        try {
            val root = JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return result
            val videoInfo = data.optJSONObject("video_info") ?: return result
            result.dur = videoInfo.optInt("timelength", 0) / 1000
            // 视频流
            val streamList = videoInfo.optJSONArray("stream_list")
            if (streamList != null) {
                for (i in 0 until streamList.length()) {
                    val stream = streamList.getJSONObject(i)
                    val dashVideo = stream.optJSONObject("dash_video") ?: continue
                    val baseUrl = dashVideo.optString("base_url")
                    if (baseUrl.isEmpty()) continue
                    val videoId = stream.optJSONObject("stream_info")?.optString("quality") ?: ""
                    val backupUrls = ArrayList<String>()
                    val backupArr = dashVideo.optJSONArray("backup_url")
                    if (backupArr != null) {
                        for (j in 0 until backupArr.length()) {
                            backupUrls.add(backupArr.getString(j))
                        }
                    }
                    result.videos = result.videos + VideoTrack(
                        id = videoId,
                        dfn = QUALITY_MAP[videoId] ?: videoId,
                        codecs = getVideoCodec(dashVideo.optString("codecid")),
                        bandwidth = dashVideo.optLong("bandwidth", 0) / 1000,
                        baseUrl = baseUrl,
                        backupUrls = backupUrls,
                        size = dashVideo.optDouble("size", 0.0),
                        dur = result.dur
                    )
                }
            }
            // 音频流
            val audioArr = videoInfo.optJSONArray("dash_audio")
            if (audioArr != null) {
                for (i in 0 until audioArr.length()) {
                    val node = audioArr.getJSONObject(i)
                    val baseUrl = node.optString("base_url")
                    if (baseUrl.isEmpty()) continue
                    val backupUrls = ArrayList<String>()
                    val backupArr = node.optJSONArray("backup_url")
                    if (backupArr != null) {
                        for (j in 0 until backupArr.length()) {
                            backupUrls.add(backupArr.getString(j))
                        }
                    }
                    result.audios = result.audios + AudioTrack(
                        id = node.optString("id"),
                        codecs = "M4A",
                        bandwidth = node.optLong("bandwidth", 0) / 1000,
                        baseUrl = baseUrl,
                        backupUrls = backupUrls,
                        dur = result.dur
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("PlayInfo", "INTL解析失败: ${e.message}")
        }
        return result
    }

    private fun getVideoCodec(codecid: String): String {
        return when (codecid) {
            "13" -> "AV1"
            "12" -> "HEVC"
            else -> "AVC"  // codecid 7 = AVC (H.264)
        }
    }

    private fun parseDash(jsonStr: String): PlayInfo {
        val root0 = JSONObject(jsonStr)
        // 检查API错误码（-400参数错误、-403访问受限、-509风控等）
        val code = root0.optInt("code", 0)
        if (code != 0) {
            val msg = root0.optString("message", "未知错误")
            Logger.e("PlayInfo", "playurl API返回错误: code=$code, msg=$msg, 响应前200字: ${jsonStr.take(200)}")
            throw IllegalStateException("播放信息API错误(code=$code): $msg")
        }
        val root = when {
            root0.has("result") -> root0.getJSONObject("result")
            root0.has("data") -> root0.getJSONObject("data")
            else -> root0
        }
        val result = PlayInfo()
        if (!root.has("dash")) return result
        val dash = root.getJSONObject("dash")
        result.dur = dash.optInt("duration", root.optInt("timelength", 0) / 1000)

        if (dash.has("video")) {
            val videos = dash.getJSONArray("video")
            for (i in 0 until videos.length()) {
                val n = videos.getJSONObject(i)
                val urls = collectUrls(n)
                result.videos = result.videos + VideoTrack(
                    id = n.optString("id"),
                    dfn = QUALITY_MAP[n.optString("id")] ?: n.optString("id"),
                    codecs = codecName(n.optString("codecid")),
                    bandwidth = n.optLong("bandwidth") / 1000,
                    res = if (n.has("width")) n.optString("width") + "x" + n.optString("height") else "",
                    fps = n.optString("frame_rate"),
                    baseUrl = urls.first,
                    backupUrls = urls.second,
                    size = n.optDouble("size", 0.0),
                    dur = result.dur
                )
            }
        }
        val audioList = ArrayList<AudioTrack>()
        if (dash.has("audio")) {
            val audios = dash.getJSONArray("audio")
            for (i in 0 until audios.length()) {
                val n = audios.getJSONObject(i)
                val urls = collectUrls(n)
                audioList.add(AudioTrack(
                    id = n.optString("id"),
                    codecs = audioCodecName(n.optString("codecs")),
                    bandwidth = n.optLong("bandwidth") / 1000,
                    baseUrl = urls.first,
                    backupUrls = urls.second,
                    dur = result.dur
                ))
            }
        }
        try {
            if (dash.has("dolby") && dash.getJSONObject("dolby").has("audio")) {
                val db = dash.getJSONObject("dolby").getJSONArray("audio")
                for (i in 0 until db.length()) {
                    val n = db.getJSONObject(i)
                    val urls = collectUrls(n)
                    audioList.add(AudioTrack(
                        id = n.optString("id"),
                        codecs = audioCodecName(n.optString("codecs")),
                        bandwidth = n.optLong("bandwidth") / 1000,
                        baseUrl = urls.first,
                        backupUrls = urls.second,
                        dur = result.dur
                    ))
                }
            }
        } catch (_: Exception) {}
        try {
            if (dash.has("flac") && dash.getJSONObject("flac").has("audio")) {
                val flac = dash.getJSONObject("flac").getJSONObject("audio")
                val urls = collectUrls(flac)
                audioList.add(AudioTrack(
                    id = flac.optString("id"),
                    codecs = audioCodecName(flac.optString("codecs")),
                    bandwidth = flac.optLong("bandwidth") / 1000,
                    baseUrl = urls.first,
                    backupUrls = urls.second,
                    dur = result.dur
                ))
            }
        } catch (_: Exception) {}
        result.audios = audioList
        return result
    }

    private fun collectUrls(node: JSONObject): Pair<String, List<String>> {
        val list = ArrayList<String>()
        list.add(node.optString("base_url"))
        if (node.has("backup_url") && !node.isNull("backup_url")) {
            val bu = node.getJSONArray("backup_url")
            for (i in 0 until bu.length()) list.add(bu.getString(i))
        }
        val portRegex = Regex("http.*:\\d+")
        val primary = list.firstOrNull { !portRegex.containsMatchIn(it) } ?: list.firstOrNull() ?: ""
        return Pair(primary, list)
    }

    private fun codecName(codecid: String) = when (codecid) {
        "13" -> "AV1"; "12" -> "HEVC"; "7" -> "AVC"; else -> "UNKNOWN"
    }
    private fun audioCodecName(codecs: String) = when (codecs) {
        "mp4a.40.2", "mp4a.40.5" -> "M4A"
        "ec-3" -> "E-AC-3"
        "fLaC" -> "FLAC"
        else -> codecs
    }

    // ==================== 收藏夹 ====================

    data class FavFolder(val id: String, val title: String, val mediaCount: Int, val cover: String)
    data class FavItem(
        val bvid: String, val title: String, val pic: String, val upper: String,
        val duration: Int, val favTime: Long,
        val ownerMid: String = "", val ownerFace: String = "",
        val play: Int = 0, val danmaku: Int = 0, val pubdate: Long = 0,
        val officialType: Int = -1, val vipType: Int = 0, val vipStatus: Int = 0
    )

    /** 获取用户收藏夹列表
     *  API: /x/v3/fav/folder/created/list-all?up_mid={mid}
     */
    fun getFavFolders(mid: String): List<FavFolder> {
        val result = ArrayList<FavFolder>()
        try {
            val resp = Http.get("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
                referer = "https://space.bilibili.com/$mid/favlist")
            Logger.d("Fav", "收藏夹响应: ${resp.take(300)}")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return result
            val list = data.optJSONArray("list") ?: return result
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                result.add(FavFolder(
                    id = item.optString("id"),
                    title = item.optString("title", "默认收藏夹"),
                    mediaCount = item.optInt("media_count", 0),
                    cover = item.optString("cover")
                ))
            }
        } catch (e: Exception) {
            Logger.e("Fav", "获取收藏夹失败: ${e.message}")
        }
        return result
    }

    /** 获取收藏夹内视频列表（支持分页）
     *  API: /x/v3/fav/resource/list?media_id={fid}&pn=1&ps=20
     */
    fun getFavList(mediaId: String, page: Int = 1, pageSize: Int = 20): Pair<List<FavItem>, Int> {
        val result = ArrayList<FavItem>()
        var total = 0
        try {
            val resp = Http.get(
                "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=$pageSize&order=mtime&platform=web",
                referer = "https://space.bilibili.com/favlist"
            )
            Logger.d("Fav", "收藏夹视频响应: ${resp.take(300)}")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return Pair(result, 0)
            total = data.optInt("info", 0)
            val medias = data.optJSONArray("medias") ?: return Pair(result, total)
            for (i in 0 until medias.length()) {
                val item = medias.getJSONObject(i)
                // 分别获取 upper（UP主信息）和 cnt_info（播放/弹幕统计），避免错误地以 cnt_info 作为 upper 备选
                val upper = item.optJSONObject("upper")
                val cntInfo = item.optJSONObject("cnt_info")
                result.add(FavItem(
                    bvid = item.optString("bvid"),
                    title = item.optString("title").replace("<em class=\"keyword\">", "").replace("</em>", ""),
                    pic = normalizePic(item.optString("cover")),
                    upper = upper?.optString("name") ?: "",
                    duration = item.optInt("duration", 0),
                    favTime = item.optLong("fav_time", 0),
                    ownerMid = upper?.optString("mid") ?: "",
                    ownerFace = upper?.let { normalizePic(it.optString("face")) } ?: "",
                    play = cntInfo?.optInt("play", 0) ?: 0,
                    danmaku = cntInfo?.optInt("danmaku", 0) ?: 0,
                    pubdate = item.optLong("pubdate", 0),
                    officialType = upper?.let { parseOfficialType(it) } ?: -1,
                    vipType = upper?.let { parseVipType(it) } ?: 0,
                    vipStatus = upper?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0
                ))
            }
            // 收藏夹API的upper字段仅含 mid/name/face，不含认证(official_verify)和VIP信息。
            // 1) 先用内存缓存填充（从搜索/关注列表等来源积累的认证数据）
            for (i in result.indices) {
                val cached = authInfoCache[result[i].ownerMid]
                if (cached != null) {
                    result[i] = result[i].copy(
                        officialType = cached.first,
                        vipType = cached.second,
                        vipStatus = cached.third
                    )
                }
            }
            // 2) 仍缺失认证信息的，调用 card API + acc/info 兜底补充获取
            val needFetchMids = result
                .filter { (it.officialType == -1 || it.vipType == 0) && it.ownerMid.isNotEmpty() }
                .map { it.ownerMid }
                .distinct()
            if (needFetchMids.isNotEmpty()) {
                Logger.i("Fav", "收藏夹: 补充获取 ${needFetchMids.size} 个UP主的认证/VIP信息")
                val midInfoMap = fetchUpperCardInfoBatch(needFetchMids)
                for (i in result.indices) {
                    val info = midInfoMap[result[i].ownerMid]
                    if (info != null) {
                        result[i] = result[i].copy(
                            officialType = info.first,
                            vipType = info.second,
                            vipStatus = info.third
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Fav", "获取收藏夹视频失败: ${e.message}")
        }
        return Pair(result, total)
    }

    /** 批量获取UP主的认证和VIP信息（并行请求，最多5并发）
     * @return Map<mid, Triple<officialType, vipType, vipStatus>>；获取失败的 mid 不计入（保留原值） */
    private fun fetchUpperCardInfoBatch(mids: List<String>): Map<String, Triple<Int, Int, Int>> {
        val result = mutableMapOf<String, Triple<Int, Int, Int>>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(minOf(5, mids.size))
        try {
            val futures = mids.map { mid ->
                executor.submit<java.util.AbstractMap.SimpleEntry<String, Triple<Int, Int, Int>?>> {
                    java.util.AbstractMap.SimpleEntry(mid, fetchUpperCardInfo(mid))
                }
            }
            futures.forEach { f ->
                try {
                    val entry = f.get(15, java.util.concurrent.TimeUnit.SECONDS)
                    // 仅记录成功获取的结果；失败(null)不覆盖原有值
                    val info = entry.value
                    if (info != null) result[entry.key] = info
                } catch (e: Exception) {
                    // 单个超时或失败，忽略（保留原有值）
                }
            }
        } finally {
            executor.shutdown()
        }
        return result
    }

    /** 获取单个UP主的认证和VIP信息
     * 策略：1) 内存缓存  2) card API（带 buvid3 防风控 + 重试）  3) WBI acc/info 兜底
     * 返回 Triple<officialType, vipType, vipStatus>；获取失败返回 null（不覆盖原有值） */
    private fun fetchUpperCardInfo(mid: String): Triple<Int, Int, Int>? {
        // 1. 内存缓存命中
        authInfoCache[mid]?.let { return it }

        // 2. 指纹 cookie（buvid3/buvid4/b_lsid/_uuid/b_nut，防 -352 风控）
        val extraCk = ensureFingerprintCookie()

        // 3. card API（带重试）
        val maxRetries = 2
        for (attempt in 0..maxRetries) {
            try {
                val resp = Http.get("https://api.bilibili.com/x/web-interface/card?mid=$mid",
                    extraCookie = extraCk,
                    referer = "https://space.bilibili.com/$mid")
                val json = JSONObject(resp)
                val code = json.optInt("code", -1)
                if (code == -352) {
                    Logger.w("Fav", "card API 风控(-352) mid=$mid 第${attempt + 1}次, 等待重试...")
                    if (attempt < maxRetries) { Thread.sleep(500L * (attempt + 1)); continue }
                    break
                }
                if (code != 0) { Logger.w("Fav", "card API code=$code mid=$mid"); break }
                val data = json.optJSONObject("data") ?: break
                val card = data.optJSONObject("card")
                val official = card?.optJSONObject("Official") ?: data.optJSONObject("Official")
                val officialType = if (official != null) {
                    when (official.optInt("role", 0)) { 1 -> 0; 2 -> 1; else -> -1 }
                } else -1
                val vip = card?.optJSONObject("vip") ?: data.optJSONObject("vip")
                val vipType = vip?.optInt("vipType", 0) ?: 0
                val vipStatus = vip?.optInt("vipStatus", 0) ?: 0
                Logger.d("Fav", "card API 成功 mid=$mid: official=$officialType, vip=$vipType/$vipStatus")
                val result = Triple(officialType, vipType, vipStatus)
                authInfoCache[mid] = result
                return result
            } catch (e: Exception) {
                Logger.w("Fav", "card API 异常 mid=$mid: ${e.message}")
                if (attempt < maxRetries) { Thread.sleep(500L * (attempt + 1)); continue }
            }
        }

        // 4. Fallback: WBI 签名的 acc/info API
        try {
            ensureWbi()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val params = LinkedHashMap<String, String>()
            params["mid"] = mid
            params["token"] = ""
            params["platform"] = "web"
            params["web_location"] = "1550101"
            params["wts"] = ts
            val query = wbiSign(params)
            val resp = Http.get("https://api.bilibili.com/x/space/wbi/acc/info?$query",
                extraCookie = extraCk, referer = "https://space.bilibili.com/$mid")
            val json = JSONObject(resp)
            if (json.optInt("code") == 0) {
                val data = json.optJSONObject("data") ?: return null
                val official = data.optJSONObject("official")
                // acc/info: official.type(-1/0/1)，fallback 到 official.role(0/1/2)
                val officialType = if (official != null) {
                    val type = official.optInt("type", -1)
                    if (type != -1) type
                    else when (official.optInt("role", 0)) { 1 -> 0; 2 -> 1; else -> -1 }
                } else -1
                val vip = data.optJSONObject("vip")
                // acc/info: vip.type(0/1/2), vip.status(0/1)
                val vipType = vip?.optInt("type", 0) ?: 0
                val vipStatus = vip?.optInt("status", 0) ?: 0
                Logger.d("Fav", "acc/info API 成功 mid=$mid: official=$officialType, vip=$vipType/$vipStatus")
                val result = Triple(officialType, vipType, vipStatus)
                authInfoCache[mid] = result
                return result
            } else {
                Logger.w("Fav", "acc/info API code=${json.optInt("code")} mid=$mid")
            }
        } catch (e: Exception) {
            Logger.w("Fav", "acc/info API 失败 mid=$mid: ${e.message}")
        }
        return null
    }

    // ==================== 合集(ugc_season)检测 ====================

    /** 合集中单个视频的轻量元数据（无需额外API请求即可获得） */
    data class CollectionVideoMeta(
        val bvid: String,
        val aid: String = "",
        val cid: String = "",
        val title: String = "",
        val pic: String = "",
        val desc: String = "",       // 视频描述（arc.desc），用于写入元数据 description
        val duration: Int = 0,
        val pubdate: Long = 0,
        val ownerName: String = "",
        val ownerMid: String = "",
        val ownerFace: String = "",
        val play: Int = 0,
        val danmaku: Int = 0,
        val officialType: Int = -1,
        val vipType: Int = 0,
        val vipStatus: Int = 0
    )

    data class CollectionInfo(
        val seasonId: String,
        val title: String,
        val mid: String,
        val bvidList: List<String>,
        val total: Int,
        val videoMetas: List<CollectionVideoMeta> = emptyList(),
        val type: String = ""  // "season"=合集, "series"=系列
    )

    /** 检测视频是否属于UGC合集
     *  通过 /x/web-interface/view?bvid=xxx 检查 data.ugc_season 字段
     */
    fun checkUgcSeason(bvid: String): CollectionInfo? {
        try {
            val resp = Http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return null
            val ugcSeason = data.optJSONObject("ugc_season") ?: return null

            val seasonId = ugcSeason.optString("id")
            val title = ugcSeason.optString("title", "未知合集")
            val mid = ugcSeason.optString("mid")

            // 合集内所有视频属于同一UP主，从 data.owner 提取UP主信息作为默认值
            val seasonOwner = data.optJSONObject("owner")
            val seasonOwnerName = seasonOwner?.optString("name") ?: ""
            val seasonOwnerMid = seasonOwner?.optString("mid") ?: ""
            val seasonOwnerFace = seasonOwner?.let { normalizePic(it.optString("face")) } ?: ""
            val seasonOfficialType = seasonOwner?.let { parseOfficialType(it) } ?: -1
            val seasonVipType = seasonOwner?.let { parseVipType(it) } ?: 0
            val seasonVipStatus = seasonOwner?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0

            val bvidList = ArrayList<String>()
            val videoMetas = ArrayList<CollectionVideoMeta>()
            val sections = ugcSeason.optJSONArray("sections")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val section = sections.getJSONObject(i)
                    val episodes = section.optJSONArray("episodes")
                    if (episodes != null) {
                        for (j in 0 until episodes.length()) {
                            val ep = episodes.getJSONObject(j)
                            val bv = ep.optString("bvid")
                            if (bv.isNotEmpty() && bv !in bvidList) {
                                bvidList.add(bv)
                                // episode 的 arc 子对象包含统计(pubdate/stat)与UP主信息(若存在则覆盖合集级UP主)
                                val arc = ep.optJSONObject("arc")
                                val arcOwner = arc?.optJSONObject("owner")
                                val stat = arc?.optJSONObject("stat")
                                val arcOwnerName = arcOwner?.optString("name") ?: ""
                                val arcOwnerMid = arcOwner?.optString("mid") ?: ""
                                val arcOwnerFace = arcOwner?.let { normalizePic(it.optString("face")) } ?: ""
                                videoMetas.add(CollectionVideoMeta(
                                    bvid = bv,
                                    aid = ep.optString("aid"),
                                    cid = ep.optString("cid"),
                                    title = ep.optString("title"),
                                    // 部分合集 episode 无 cover 字段（封面在 arc.pic 中），需回退
                                    pic = normalizePic(ep.optString("cover").ifEmpty { arc?.optString("pic") ?: "" }),
                                    desc = arc?.optString("desc") ?: "",
                                    duration = ep.optInt("duration", 0),
                                    pubdate = arc?.optLong("pubdate", 0) ?: 0,
                                    ownerName = arcOwnerName.ifEmpty { seasonOwnerName },
                                    ownerMid = arcOwnerMid.ifEmpty { seasonOwnerMid },
                                    ownerFace = arcOwnerFace.ifEmpty { seasonOwnerFace },
                                    play = stat?.optInt("view", 0) ?: 0,
                                    danmaku = stat?.optInt("danmaku", 0) ?: 0,
                                    officialType = arcOwner?.let { parseOfficialType(it) }
                                        ?: seasonOfficialType,
                                    vipType = arcOwner?.let { parseVipType(it) }
                                        ?: seasonVipType,
                                    vipStatus = arcOwner?.optJSONObject("vip")
                                        ?.optInt("vipStatus", 0) ?: seasonVipStatus
                                ))
                            }
                        }
                    }
                }
            }
            Logger.i("Collection", "检测到合集: $title, ${bvidList.size}个视频")
            return CollectionInfo(seasonId, title, mid, bvidList, bvidList.size, videoMetas, type = "season")
        } catch (e: Exception) {
            Logger.e("Collection", "检测合集失败: ${e.message}")
            return null
        }
    }

    /** 获取UGC合集完整视频列表（分页）
     *  API: /x/polymer/web-space/seasons_archives_list?mid={mid}&season_id={season_id}
     *  返回 bvid 列表（兼容旧调用）
     */
    fun getUgcSeasonArchives(mid: String, seasonId: String, pageSize: Int = 30): List<String> {
        return getUgcSeasonArchivesWithMeta(mid, seasonId, pageSize).map { it.bvid }
    }

    /** 获取UGC合集完整视频列表（含元数据，无需额外API请求即可获得标题、封面等）
     *  archives 数组已包含 bvid, aid, title, pic, duration 等字段
     */
    fun getUgcSeasonArchivesWithMeta(mid: String, seasonId: String, pageSize: Int = 30): List<CollectionVideoMeta> {
        val metaList = ArrayList<CollectionVideoMeta>()
        var pageNum = 1
        while (true) {
            try {
                val resp = Http.get(
                    "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid=$mid&season_id=$seasonId&sort_reverse=false&page_num=$pageNum&page_size=$pageSize",
                    referer = "https://space.bilibili.com/$mid/lists/$seasonId?type=season"
                )
                val json = JSONObject(resp)
                val data = json.optJSONObject("data") ?: break
                val archives = data.optJSONArray("archives") ?: break
                if (archives.length() == 0) break

                for (i in 0 until archives.length()) {
                    val arc = archives.getJSONObject(i)
                    val bv = arc.optString("bvid")
                    if (bv.isNotEmpty()) {
                        val owner = arc.optJSONObject("owner")
                        val stat = arc.optJSONObject("stat")
                        metaList.add(CollectionVideoMeta(
                            bvid = bv,
                            aid = arc.optString("aid"),
                            cid = arc.optString("cid"),
                            title = arc.optString("title"),
                            pic = normalizePic(arc.optString("pic")),
                            desc = arc.optString("desc"),
                            duration = arc.optInt("duration", 0),
                            pubdate = arc.optLong("pubdate", 0),
                            ownerName = owner?.optString("name") ?: "",
                            ownerMid = owner?.optString("mid") ?: "",
                            ownerFace = owner?.let { normalizePic(it.optString("face")) } ?: "",
                            play = stat?.optInt("view", 0) ?: 0,
                            danmaku = stat?.optInt("danmaku", 0) ?: 0,
                            officialType = owner?.let { parseOfficialType(it) } ?: -1,
                            vipType = owner?.let { parseVipType(it) } ?: 0,
                            vipStatus = owner?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0
                        ))
                    }
                }

                val page = data.optJSONObject("page")
                val total = page?.optInt("total", 0) ?: 0
                if (total > 0) {
                    val totalPages = (total + pageSize - 1) / pageSize
                    if (pageNum >= totalPages) break
                } else {
                    if (archives.length() < pageSize) break
                }
                pageNum++
            } catch (e: Exception) {
                Logger.e("Collection", "获取合集视频失败: ${e.message}")
                break
            }
        }
        Logger.i("Collection", "合集视频列表(含元数据): ${metaList.size}个")
        return metaList
    }

    // ==================== 合集/系列完整检测(回退逻辑) ====================

    /** 从视频信息中获取UP主的mid */
    fun getVideoOwnerMid(bvid: String): String? {
        try {
            val resp = Http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return null
            val owner = data.optJSONObject("owner") ?: return null
            return owner.optString("mid")
        } catch (e: Exception) {
            Logger.e("Collection", "获取UP主mid失败: ${e.message}")
            return null
        }
    }

    /** UP主合集/系列信息 */
    data class SeasonSeriesItem(
        val type: String,   // "season" 或 "series"
        val id: String,
        val name: String,
        val total: Int,
        val recentBvids: List<String>
    )

    /** 获取UP主的所有合集(seasons)和系列(series)列表
     *  API: /x/polymer/web-space/home/seasons_series?mid={mid}
     */
    fun getUserSeasonsSeries(mid: String): List<SeasonSeriesItem> {
        val result = ArrayList<SeasonSeriesItem>()
        var pageNum = 1
        val pageSize = 20
        while (true) {
            try {
                val resp = Http.get(
                    "https://api.bilibili.com/x/polymer/web-space/home/seasons_series?mid=$mid&page_num=$pageNum&page_size=$pageSize",
                    referer = "https://space.bilibili.com/$mid/"
                )
                val json = JSONObject(resp)
                if (json.optInt("code") != 0) break
                val data = json.optJSONObject("data") ?: break
                val itemsLists = data.optJSONObject("items_lists") ?: break

                // 合集列表 (seasons_list)
                val seasonsList = itemsLists.optJSONArray("seasons_list")
                if (seasonsList != null) {
                    for (i in 0 until seasonsList.length()) {
                        val item = seasonsList.getJSONObject(i)
                        val meta = item.optJSONObject("meta") ?: continue
                        val archives = item.optJSONArray("archives")
                        val recentBvids = ArrayList<String>()
                        if (archives != null) {
                            for (j in 0 until archives.length()) {
                                val bvid = archives.getJSONObject(j).optString("bvid")
                                if (bvid.isNotEmpty()) recentBvids.add(bvid)
                            }
                        }
                        result.add(SeasonSeriesItem(
                            type = "season",
                            id = meta.optString("season_id"),
                            name = meta.optString("name", "未知合集"),
                            total = meta.optInt("total", 0),
                            recentBvids = recentBvids
                        ))
                    }
                }

                // 系列列表 (series_list)
                val seriesList = itemsLists.optJSONArray("series_list")
                if (seriesList != null) {
                    for (i in 0 until seriesList.length()) {
                        val item = seriesList.getJSONObject(i)
                        val meta = item.optJSONObject("meta") ?: continue
                        val archives = item.optJSONArray("archives")
                        val recentBvids = ArrayList<String>()
                        if (archives != null) {
                            for (j in 0 until archives.length()) {
                                val bvid = archives.getJSONObject(j).optString("bvid")
                                if (bvid.isNotEmpty()) recentBvids.add(bvid)
                            }
                        }
                        result.add(SeasonSeriesItem(
                            type = "series",
                            id = meta.optString("series_id"),
                            name = meta.optString("name", "未知系列"),
                            total = meta.optInt("total", 0),
                            recentBvids = recentBvids
                        ))
                    }
                }

                // 分页检查
                val page = itemsLists.optJSONObject("page")
                val totalPages = page?.optInt("total", 1) ?: 1
                if (pageNum >= totalPages) break
                pageNum++
            } catch (e: Exception) {
                Logger.e("Collection", "获取UP主合集/系列列表失败: ${e.message}")
                break
            }
        }
        Logger.i("Collection", "UP主(mid=$mid)合集/系列: ${result.size}个")
        return result
    }

    /** 获取系列(list)完整视频列表(分页)
     *  API: /x/series/archives?mid={mid}&series_id={series_id}
     *  兼容旧调用：仅返回 bvid 列表
     */
    fun getSeriesArchives(mid: String, seriesId: String, pageSize: Int = 30): List<String> {
        return getSeriesArchivesWithMeta(mid, seriesId, pageSize).map { it.bvid }
    }

    /** 获取系列(list)完整视频列表（含元数据）
     *  archives 数组已包含 bvid, aid, title, pic, duration 等字段
     *  修复：series 类型合集之前只返回 BV 号，导致封面和标题缺失
     */
    fun getSeriesArchivesWithMeta(mid: String, seriesId: String, pageSize: Int = 30): List<CollectionVideoMeta> {
        val metaList = ArrayList<CollectionVideoMeta>()
        var pageNum = 1
        while (true) {
            try {
                val resp = Http.get(
                    "https://api.bilibili.com/x/series/archives?mid=$mid&current_mid=$mid&series_id=$seriesId&only_normal=true&sort=asc&ps=$pageSize&pn=$pageNum&web_location=333.1387",
                    referer = "https://space.bilibili.com/$mid/"
                )
                val json = JSONObject(resp)
                if (json.optInt("code") != 0) break
                val data = json.optJSONObject("data") ?: break
                val archives = data.optJSONArray("archives") ?: break
                if (archives.length() == 0) break

                for (i in 0 until archives.length()) {
                    val arc = archives.getJSONObject(i)
                    val bv = arc.optString("bvid")
                    if (bv.isNotEmpty()) {
                        val owner = arc.optJSONObject("owner")
                        val stat = arc.optJSONObject("stat")
                        metaList.add(CollectionVideoMeta(
                            bvid = bv,
                            aid = arc.optString("aid"),
                            cid = arc.optString("cid"),
                            title = arc.optString("title"),
                            pic = normalizePic(arc.optString("pic")),
                            desc = arc.optString("desc"),
                            duration = arc.optInt("duration", 0),
                            pubdate = arc.optLong("pubdate", 0),
                            ownerName = owner?.optString("name") ?: "",
                            ownerMid = owner?.optString("mid") ?: "",
                            ownerFace = owner?.let { normalizePic(it.optString("face")) } ?: "",
                            play = stat?.optInt("view", 0) ?: 0,
                            danmaku = stat?.optInt("danmaku", 0) ?: 0,
                            officialType = owner?.let { parseOfficialType(it) } ?: -1,
                            vipType = owner?.let { parseVipType(it) } ?: 0,
                            vipStatus = owner?.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0
                        ))
                    }
                }

                val page = data.optJSONObject("page")
                val total = page?.optInt("total", 0) ?: 0
                if (total > 0) {
                    val totalPages = (total + pageSize - 1) / pageSize
                    if (pageNum >= totalPages) break
                } else {
                    if (archives.length() < pageSize) break
                }
                pageNum++
            } catch (e: Exception) {
                Logger.e("Collection", "获取系列视频列表失败: ${e.message}")
                break
            }
        }
        return metaList
    }

    /** 综合检测：先检查ugc_season，未找到则搜索UP主合集/系列列表
     *  参考脚本实现，支持直播回放等ugc_season字段缺失的场景
     */
    fun checkCollectionComprehensive(bvid: String): CollectionInfo? {
        // Step 1: 先检查 ugc_season（原逻辑）
        val ugcResult = checkUgcSeason(bvid)
        if (ugcResult != null) {
            Logger.i("Collection", "通过ugc_season检测到合集: ${ugcResult.title}")
            return ugcResult
        }

        Logger.i("Collection", "ugc_season未找到，回退搜索UP主合集/系列...")
        // Step 2: 获取视频UP主的mid
        val ownerMid = getVideoOwnerMid(bvid)
        if (ownerMid.isNullOrEmpty()) {
            Logger.w("Collection", "无法获取UP主mid，合集检测终止")
            return null
        }

        // Step 3: 获取UP主的所有合集/系列列表
        val items = getUserSeasonsSeries(ownerMid)
        if (items.isEmpty()) {
            Logger.i("Collection", "UP主没有合集/系列")
            return null
        }

        // Step 4: 在合集/系列的最近视频中查找
        for (item in items) {
            if (bvid in item.recentBvids) {
                Logger.i("Collection", "在${if(item.type=="season")"合集" else "系列"}「${item.name}」最近视频中找到")
                return fetchFullCollectionList(item, ownerMid)
            }
        }

        // Step 5: 最近视频未找到，逐个获取完整列表精确匹配
        Logger.i("Collection", "最近视频未找到，逐个搜索完整列表...")
        for (item in items) {
            val metaList = if (item.type == "season") {
                getUgcSeasonArchivesWithMeta(ownerMid, item.id)
            } else {
                getSeriesArchivesWithMeta(ownerMid, item.id)
            }
            val fullBvList = metaList.map { it.bvid }
            if (bvid in fullBvList) {
                Logger.i("Collection", "在${if(item.type=="season")"合集" else "系列"}「${item.name}」完整列表中找到")
                return CollectionInfo(
                    seasonId = item.id,
                    title = item.name,
                    mid = ownerMid,
                    bvidList = fullBvList,
                    total = fullBvList.size,
                    videoMetas = metaList,
                    type = item.type  // "season"=合集, "series"=系列
                )
            }
        }

        Logger.i("Collection", "未在任何合集/系列中找到该视频")
        return null
    }

    // ==================== UP主搜索与投稿视频 ====================

    data class UpperInfo(
        val mid: String,
        val uname: String,
        val face: String,
        val sign: String,
        val fans: Int,
        val videoCount: Int,
        // 认证信息：official_verify.type，-1=无，0=个人认证(小闪电)，1=机构认证
        val officialType: Int = -1,
        val officialDesc: String = "",
        // 大会员：vip.vipType，0=无，1=月度大会员，2=年度以上大会员；vipStatus 0=无 1=有效
        val vipType: Int = 0,
        val vipStatus: Int = 0,
        // 特别关注：0=否，1=是
        val special: Int = 0
    )

    /** 关注分组(分类) */
    data class FollowTag(
        val tagid: Int,
        val name: String,
        val count: Int
    )

    data class UpperVideo(
        val bvid: String,
        val title: String,
        val pic: String,
        val play: Int,
        val danmaku: Int,
        val duration: String,
        val created: Long,
        val desc: String
    )

    /** 搜索UP主
     *  API: /x/web-interface/search/type?search_type=bili_user&keyword=xxx
     *  B站风控可能返回 412 + HTML 页面，需检测并重试
     */
    fun searchUpper(keyword: String): List<UpperInfo> {
        val result = ArrayList<UpperInfo>()
        try {
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val apiUrl = "https://api.bilibili.com/x/web-interface/search/type?search_type=bili_user&keyword=$encodedKeyword&page=1&page_size=20"
            val refUrl = "https://search.bilibili.com/upuser?keyword=$encodedKeyword"

            // 完整指纹 cookie 防412风控 + 增加重试到5次
            val extraCk = ensureFingerprintCookie()
            var resp = ""
            val maxRetries = 5
            for (attempt in 1..maxRetries) {
                resp = Http.get(apiUrl, extraCookie = extraCk, referer = refUrl)
                Logger.d("Search", "搜索UP主响应(第${attempt}次): ${resp.take(200)}")
                // 检测风控：412 时 B站返回 HTML 页面而非 JSON
                if (resp.trimStart().startsWith("<")) {
                    Logger.w("Search", "搜索UP主被风控(412)，第${attempt}次重试...")
                    if (attempt < maxRetries) {
                        Thread.sleep(2000L * attempt) // 递增等待：2s, 4s, 6s, 8s
                        continue
                    }
                    Logger.e("Search", "搜索UP主失败：多次重试仍被风控")
                    return result
                }
                break
            }

            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return result
            val items = data.optJSONArray("result") ?: return result
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                result.add(UpperInfo(
                    mid = item.optString("mid"),
                    uname = item.optString("uname").replace("<em class=\"keyword\">", "").replace("</em>", ""),
                    face = item.optString("upic").let { if (it.startsWith("//")) "https:$it" else it },
                    sign = item.optString("usign").replace("<em class=\"keyword\">", "").replace("</em>", ""),
                    fans = item.optInt("fans", 0),
                    videoCount = item.optInt("videos", 0),
                    officialType = parseOfficialType(item),
                    officialDesc = parseOfficialDesc(item),
                    vipType = parseVipType(item),
                    vipStatus = item.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0
                ))
            }
            // 搜索API(bili_user)不返回认证/VIP信息，批量补充获取
            val needFetchMids = result
                .filter { (it.officialType == -1 || it.vipType == 0) && it.mid.isNotEmpty() }
                .map { it.mid }
                .distinct()
            if (needFetchMids.isNotEmpty()) {
                Logger.i("Search", "搜索UP主: 补充获取 ${needFetchMids.size} 个UP主的认证/VIP信息")
                val midInfoMap = fetchUpperCardInfoBatch(needFetchMids)
                for (i in result.indices) {
                    val info = midInfoMap[result[i].mid]
                    if (info != null) {
                        result[i] = result[i].copy(
                            officialType = info.first,
                            vipType = info.second,
                            vipStatus = info.third
                        )
                    }
                }
            }
            // 缓存搜索结果中的认证/VIP信息，供收藏夹页面复用
            for (u in result) {
                cacheAuthInfo(u.mid, u.officialType, u.vipType, u.vipStatus)
            }
            Logger.i("Search", "搜索到 ${result.size} 个UP主")
        } catch (e: Exception) {
            Logger.e("Search", "搜索UP主失败: ${e.message}")
        }
        return result
    }

    /** 获取UP主投稿视频列表（支持分页）
     *  API: /x/space/wbi/arc/search?mid={mid}&pn={page}&ps={pageSize}
     *  需要WBI签名
     */
    fun getUpperVideos(mid: String, page: Int = 1, pageSize: Int = 30): Pair<List<UpperVideo>, Int> {
        val result = ArrayList<UpperVideo>()
        var total = 0
        try {
            ensureWbi()
            val ts = (System.currentTimeMillis() / 1000).toString()
            val params = LinkedHashMap<String, String>()
            params["mid"] = mid
            params["pn"] = page.toString()
            params["ps"] = pageSize.toString()
            params["order"] = "pubdate"
            params["wts"] = ts

            val query = wbiSign(params)
            val apiUrl = "https://api.bilibili.com/x/space/wbi/arc/search?$query"
            val resp = Http.get(apiUrl, referer = "https://space.bilibili.com/$mid/video")
            Logger.d("Search", "UP主视频响应: ${resp.take(300)}")
            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return Pair(result, 0)

            // 获取总数
            val pageObj = data.optJSONObject("page")
            if (pageObj != null) {
                total = pageObj.optInt("count", 0)
            }

            val listObj = data.optJSONObject("list") ?: return Pair(result, total)
            val vlist = listObj.optJSONArray("vlist") ?: return Pair(result, total)
            for (i in 0 until vlist.length()) {
                val v = vlist.getJSONObject(i)
                result.add(UpperVideo(
                    bvid = v.optString("bvid"),
                    title = v.optString("title").replace("<em class=\"keyword\">", "").replace("</em>", ""),
                    pic = normalizePic(v.optString("pic")),
                    play = v.optInt("play", 0),
                    danmaku = v.optInt("video_review", 0),
                    duration = formatDuration(v.optString("length", "0:00")),
                    created = v.optLong("created", 0),
                    desc = v.optString("description", "")
                ))
            }
            Logger.i("Search", "获取UP主(mid=$mid)视频: 第${page}页, ${result.size}个, 共${total}个")
        } catch (e: Exception) {
            Logger.e("Search", "获取UP主视频失败: ${e.message}")
        }
        return Pair(result, total)
    }

    // ==================== 关注列表 ====================

    /** 获取当前登录用户关注列表（分页）
     *  API: /x/relation/followings?vmid={mid}&pn={page}&ps={pageSize}
     *  orderType: "attention"=最常访问(最近比较在意)，""=按关注时间(最近关注)
     *  tagId: 0=全部，>0=指定分组(分类)
     *  需要登录Cookie
     */
    fun getFollowings(mid: String, page: Int = 1, pageSize: Int = 50, orderType: String = "attention", tagId: Int = 0): Pair<List<UpperInfo>, Int> {
        val result = ArrayList<UpperInfo>()
        var total = 0
        val origin = "https://space.bilibili.com"
        val referer = "https://space.bilibili.com/$mid/fans/follow"
        try {
            if (tagId > 0) {
                // ===== 分组过滤模式 =====
                // 使用全量缓存：首次请求时获取所有关注页并按tag过滤，后续翻页直接从缓存读取
                val cache = filteredCache
                val cacheValid = cache != null && cache.mid == mid && cache.tagId == tagId && cache.orderType == orderType

                if (cacheValid && cache != null) {
                    // 命中缓存，直接分页返回
                    val allFiltered = cache.items
                    total = cache.total
                    val skipCount = (page - 1) * pageSize
                    val pageStart = skipCount.coerceAtMost(allFiltered.size)
                    val pageEnd = (pageStart + pageSize).coerceAtMost(allFiltered.size)
                    result.addAll(allFiltered.subList(pageStart, pageEnd))
                    Logger.i("Follow", "分组过滤(缓存)第${page}页: 取${result.size}个, 总${total}个")
                } else {
                    // 缓存未命中，全量获取所有关注页并过滤
                    val fetchSize = 50
                    val allFiltered = ArrayList<UpperInfo>()
                    var globalTotal: Int
                    var p = 1

                    while (true) {
                        // API限制已移除：不再添加页间延迟
                        val sb = StringBuilder("https://api.bilibili.com/x/relation/followings?vmid=$mid&pn=$p&ps=$fetchSize&order=desc&jsonp=jsonp")
                        if (orderType.isNotEmpty()) sb.append("&order_type=").append(orderType)
                        val resp = Http.get(sb.toString(), referer = referer, origin = origin)
                        val json = JSONObject(resp)
                        if (json.optInt("code") != 0) {
                            break
                        }
                        val data = json.optJSONObject("data") ?: break
                        globalTotal = data.optInt("total", 0)
                        val list = data.optJSONArray("list") ?: break
                        if (list.length() == 0) break

                        for (i in 0 until list.length()) {
                            val item = list.getJSONObject(i)
                            val tagArr = item.optJSONArray("tag")
                            val belongsToTag = tagArr != null && (0 until tagArr.length()).any { tagArr.optInt(it) == tagId }
                            if (belongsToTag) {
                                allFiltered.add(UpperInfo(
                                    mid = item.optString("mid"),
                                    uname = item.optString("uname"),
                                    face = item.optString("face").let { if (it.startsWith("//")) "https:$it" else if (it.isNotEmpty() && !it.startsWith("http")) "https://$it" else it },
                                    sign = item.optString("sign"),
                                    fans = 0,
                                    videoCount = 0,
                                    officialType = parseOfficialType(item),
                                    officialDesc = parseOfficialDesc(item),
                                    vipType = parseVipType(item),
                                    vipStatus = item.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0,
                                    special = item.optInt("special", 0)
                                ))
                            }
                        }
                        // 到最后一页则停止
                        if (list.length() < fetchSize) break
                        // 已获取全部关注(globalTotal)，停止
                        if (globalTotal > 0 && p * fetchSize >= globalTotal) break
                        p++
                        // API限制已移除：不再限制最大页数，获取全部数据
                    }

                    // 使用分组标签的count作为真实总数(比过滤后的数量更准确)
                    val tagCount = cachedFollowTags?.find { it.tagid == tagId }?.count ?: 0
                    total = if (tagCount > 0) tagCount else allFiltered.size

                    // 缓存全量过滤结果
                    filteredCache = FilteredCache(mid, tagId, orderType, allFiltered, total)

                    // 返回当前页
                    val skipCount = (page - 1) * pageSize
                    val pageStart = skipCount.coerceAtMost(allFiltered.size)
                    val pageEnd = (pageStart + pageSize).coerceAtMost(allFiltered.size)
                    result.addAll(allFiltered.subList(pageStart, pageEnd))

                    Logger.i("Follow", "分组过滤(mid=$mid,tag=$tagId)第${page}页: 取${result.size}个, 过滤${allFiltered.size}个, 总${total}个")
                }
            } else {
                // 无分组过滤：直接使用 followings API
                filteredCache = null // 清除分组缓存
                val sb = StringBuilder("https://api.bilibili.com/x/relation/followings?vmid=$mid&pn=$page&ps=$pageSize&order=desc&jsonp=jsonp")
                if (orderType.isNotEmpty()) sb.append("&order_type=").append(orderType)
                val resp = Http.get(sb.toString(), referer = referer, origin = origin)
                Logger.d("Follow", "关注列表响应: ${resp.take(200)}")
                val json = JSONObject(resp)
                if (json.optInt("code") != 0) {
                    Logger.e("Follow", "获取关注列表失败: ${json.optString("message")}")
                    return Pair(result, total)
                }
                val data = json.optJSONObject("data") ?: return Pair(result, total)
                total = data.optInt("total", 0)
                val list = data.optJSONArray("list") ?: return Pair(result, total)
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    result.add(UpperInfo(
                        mid = item.optString("mid"),
                        uname = item.optString("uname"),
                        face = item.optString("face").let { if (it.startsWith("//")) "https:$it" else if (it.isNotEmpty() && !it.startsWith("http")) "https://$it" else it },
                        sign = item.optString("sign"),
                        fans = 0,
                        videoCount = 0,
                        officialType = parseOfficialType(item),
                        officialDesc = parseOfficialDesc(item),
                        vipType = parseVipType(item),
                        vipStatus = item.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0,
                        special = item.optInt("special", 0)
                    ))
                }
                Logger.i("Follow", "获取关注列表(mid=$mid)第${page}页(order=$orderType): ${result.size}个, 共${total}个")
            }
            // 缓存关注列表中的认证/VIP信息，供收藏夹页面复用
            for (u in result) {
                cacheAuthInfo(u.mid, u.officialType, u.vipType, u.vipStatus)
            }
            // 并行获取每个UP的粉丝数（关注列表API不返回粉丝数，需额外请求）
            if (result.isNotEmpty()) {
                fetchFansBatch(result)
            }
        } catch (e: Exception) {
            Logger.e("Follow", "获取关注列表失败: ${e.message}")
        }
        return Pair(result, total)
    }

    // 粉丝数缓存，避免同一UP主重复请求触发风控
    private val fansCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 并行批量获取UP主粉丝数，直接修改list中的UpperInfo.fans字段
     *  使用缓存减少API请求（已解除并发限制） */
    private fun fetchFansBatch(list: ArrayList<UpperInfo>) {
        // 先用缓存填充，减少API请求
        val toFetch = list.filter { u -> u.fans == 0 && !fansCache.containsKey(u.mid) }
        // 已有缓存的直接赋值
        for (i in list.indices) {
            val u = list[i]
            val cached = fansCache[u.mid]
            if (u.fans == 0 && cached != null && cached > 0) {
                list[i] = u.copy(fans = cached)
            }
        }
        if (toFetch.isEmpty()) return

        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = toFetch.map { u ->
                executor.submit<Pair<String, Int>> {
                    Pair(u.mid, getUpperFans(u.mid))
                }
            }
            val fansMap = HashMap<String, Int>()
            for (f in futures) {
                try {
                    val (m, fans) = f.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    fansMap[m] = fans
                    if (fans > 0) fansCache[m] = fans // 写入缓存
                } catch (e: Exception) { /* 超时或失败，跳过 */ }
            }
            for (i in list.indices) {
                val u = list[i]
                val fans = fansMap[u.mid]
                if (fans != null && fans > 0) {
                    list[i] = u.copy(fans = fans)
                }
            }
        } catch (e: Exception) {
            Logger.w("Follow", "批量获取粉丝数失败: ${e.message}")
        } finally {
            executor.shutdown()
        }
    }

    /** 获取单个UP主粉丝数 API: /x/relation/stat?vmid={mid} */
    private fun getUpperFans(mid: String): Int {
        return try {
            val resp = Http.get("https://api.bilibili.com/x/relation/stat?vmid=$mid", referer = "https://space.bilibili.com/$mid", origin = "https://space.bilibili.com")
            val json = JSONObject(resp)
            json.optJSONObject("data")?.optInt("follower", 0) ?: 0
        } catch (e: Exception) { 0 }
    }

    // ===== 关注分组缓存与风控容错 =====
    @Volatile
    private var cachedFollowTags: List<FollowTag>? = null
    private var cachedFollowTagsTime: Long = 0
    private val TAGS_CACHE_MS = 5 * 60 * 1000L // 5分钟缓存

    // ===== 分组过滤全量缓存 =====
    // 缓存按 tagId 过滤后的完整列表，避免每次翻页都重新请求API
    private data class FilteredCache(
        val mid: String,
        val tagId: Int,
        val orderType: String,
        val items: List<UpperInfo>,
        val total: Int
    )
    @Volatile
    private var filteredCache: FilteredCache? = null

    /** 清除分组过滤缓存(切换分组/排序/用户时调用) */
    fun clearFilteredCache() {
        filteredCache = null
    }

    /** 时长格式化：兼容秒数(整数)和 "M:SS"/"H:MM:SS" 格式 */
    private fun formatDuration(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.contains(":")) return raw
        return try {
            val seconds = raw.trim().toLong()
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        } catch (e: Exception) { raw }
    }

    /** 清除关注分组缓存，下次 getFollowTags 会重新请求API */
    fun clearFollowTagsCache() {
        cachedFollowTags = null
        cachedFollowTagsTime = 0
        filteredCache = null
        Logger.d("Follow", "关注分组缓存已清除")
    }

    /** 获取关注分组(分类)列表
     *  API: /x/relation/tags
     *  带缓存和风控容错：优先返回缓存，API失败时返回缓存(若有)
     */
    fun getFollowTags(): List<FollowTag> {
        // 优先返回缓存（5分钟内有效）
        val now = System.currentTimeMillis()
        if (cachedFollowTags != null && now - cachedFollowTagsTime < TAGS_CACHE_MS) {
            Logger.d("Follow", "使用缓存的关注分组: ${cachedFollowTags!!.size}个")
            return cachedFollowTags!!
        }

        val result = ArrayList<FollowTag>()
        val origin = "https://space.bilibili.com"
        val referer = "https://space.bilibili.com/"

        // 尝试多个URL变体，规避风控
        val urls = listOf(
            "https://api.bilibili.com/x/relation/tags",
            "https://api.bilibili.com/x/relation/tags?cross_domain=true"
        )

        for (apiUrl in urls) {
            try {
                val resp = Http.get(apiUrl, referer = referer, origin = origin)
                Logger.d("Follow", "关注分组响应: ${resp.take(300)}")
                val json = JSONObject(resp)

                if (json.optInt("code") != 0) {
                    val code = json.optInt("code")
                    Logger.w("Follow", "获取关注分组失败(code=$code): ${json.optString("message")}")
                    continue
                }

                val data = json.opt("data")
                val arr = when (data) {
                    is org.json.JSONArray -> data
                    is org.json.JSONObject -> data.optJSONArray("list") ?: org.json.JSONArray()
                    else -> org.json.JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    result.add(FollowTag(
                        tagid = t.optInt("tagid", t.optInt("tag_id", 0)),
                        name = t.optString("name", t.optString("tip", "")),
                        count = t.optInt("count", 0)
                    ))
                }
                Logger.i("Follow", "获取关注分组: ${result.size}个")

                // 缓存成功结果
                cachedFollowTags = result
                cachedFollowTagsTime = now
                return result
            } catch (e: Exception) {
                Logger.e("Follow", "获取关注分组异常: ${e.message}")
                continue
            }
        }

        // 所有URL都失败，返回缓存(如果有)
        if (cachedFollowTags != null) {
            Logger.i("Follow", "API失败，返回缓存的关注分组: ${cachedFollowTags!!.size}个")
            return cachedFollowTags!!
        }
        return result
    }

    /** 解析认证类型：official_verify.type(-1/0/1)，兼容 official.role(0/1/2) */
    private fun parseOfficialType(item: JSONObject): Int {
        val ov = item.optJSONObject("official_verify")
        if (ov != null) return ov.optInt("type", -1)
        val off = item.optJSONObject("official")
        if (off != null) {
            // official.role: 0=无,1=个人认证(小闪电),2=机构认证 → 映射为 -1/0/1
            return when (off.optInt("role", 0)) {
                1 -> 0
                2 -> 1
                else -> -1
            }
        }
        return -1
    }

    private fun parseOfficialDesc(item: JSONObject): String {
        val ov = item.optJSONObject("official_verify")
        if (ov != null) return ov.optString("desc", "")
        val off = item.optJSONObject("official")
        return off?.optString("title", "") ?: ""
    }

    /** 解析大会员类型：vip.vipType(0/1/2) */
    private fun parseVipType(item: JSONObject): Int {
        return item.optJSONObject("vip")?.optInt("vipType", 0) ?: 0
    }

    data class VideoSearchResult(
        val bvid: String,
        val title: String,
        val pic: String,
        val author: String,
        val mid: String,
        val play: Int,
        val danmaku: Int,
        val duration: String,
        val desc: String,
        val pubdate: Long = 0,
        val ownerFace: String = "",
        val officialType: Int = -1,
        val vipType: Int = 0,
        val vipStatus: Int = 0
    )

    /** 搜索视频
     *  API: /x/web-interface/search/type?search_type=video&keyword=xxx
     *  B站风控可能返回 412 + HTML 页面，需检测并重试
     */
    fun searchVideos(keyword: String): List<VideoSearchResult> {
        val result = ArrayList<VideoSearchResult>()
        try {
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val apiUrl = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encodedKeyword&page=1&page_size=20"
            val refUrl = "https://search.bilibili.com/all?keyword=$encodedKeyword"

            // 完整指纹 cookie 防412风控 + 增加重试到5次
            val extraCk = ensureFingerprintCookie()
            var resp = ""
            val maxRetries = 5
            for (attempt in 1..maxRetries) {
                resp = Http.get(apiUrl, extraCookie = extraCk, referer = refUrl)
                Logger.d("Search", "搜索视频响应(第${attempt}次): ${resp.take(200)}")
                if (resp.trimStart().startsWith("<")) {
                    Logger.w("Search", "搜索视频被风控(412)，第${attempt}次重试...")
                    if (attempt < maxRetries) {
                        Thread.sleep(2000L * attempt) // 递增等待：2s, 4s, 6s, 8s
                        continue
                    }
                    Logger.e("Search", "搜索视频失败：多次重试仍被风控")
                    return result
                }
                break
            }

            val json = JSONObject(resp)
            val data = json.optJSONObject("data") ?: return result
            val items = data.optJSONArray("result") ?: return result
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                result.add(VideoSearchResult(
                    bvid = item.optString("bvid"),
                    title = item.optString("title")
                        .replace("<em class=\"keyword\">", "")
                        .replace("</em>", "")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\""),
                    pic = normalizePic(item.optString("pic")),
                    author = item.optString("author"),
                    mid = item.optString("mid"),
                    play = item.optInt("play", 0),
                    danmaku = item.optInt("video_review", 0),
                    duration = formatDuration(item.optString("duration", "0:00")),
                    desc = item.optString("description")
                        .replace("<em class=\"keyword\">", "")
                        .replace("</em>", ""),
                    pubdate = item.optLong("pubdate", 0),
                    ownerFace = normalizePic(item.optString("upic")),
                    officialType = item.optJSONObject("official_verify")?.optInt("type", -1) ?: -1,
                    vipType = parseVipType(item),
                    vipStatus = item.optJSONObject("vip")?.optInt("vipStatus", 0) ?: 0
                ))
            }
            // 搜索API(video)不返回认证/VIP信息，批量补充获取
            val needFetchMids = result
                .filter { (it.officialType == -1 || it.vipType == 0) && it.mid.isNotEmpty() }
                .map { it.mid }
                .distinct()
            if (needFetchMids.isNotEmpty()) {
                Logger.i("Search", "搜索视频: 补充获取 ${needFetchMids.size} 个UP主的认证/VIP信息")
                val midInfoMap = fetchUpperCardInfoBatch(needFetchMids)
                for (i in result.indices) {
                    val info = midInfoMap[result[i].mid]
                    if (info != null) {
                        result[i] = result[i].copy(
                            officialType = info.first,
                            vipType = info.second,
                            vipStatus = info.third
                        )
                    }
                }
            }
            // 缓存视频搜索结果中UP主的认证/VIP信息，供收藏夹页面复用
            for (v in result) {
                cacheAuthInfo(v.mid, v.officialType, v.vipType, v.vipStatus)
            }
            Logger.i("Search", "搜索到 ${result.size} 个视频")
        } catch (e: Exception) {
            Logger.e("Search", "搜索视频失败: ${e.message}")
        }
        return result
    }
    private fun fetchFullCollectionList(item: SeasonSeriesItem, mid: String): CollectionInfo {
        // season 类型用 getUgcSeasonArchivesWithMeta 获取含元数据的完整列表
        val metaList = if (item.type == "season") {
            getUgcSeasonArchivesWithMeta(mid, item.id)
        } else {
            // series 类型使用 getSeriesArchivesWithMeta 获取含元数据的完整列表
            getSeriesArchivesWithMeta(mid, item.id)
        }
        val finalList = if (metaList.isNotEmpty()) metaList.map { it.bvid } else item.recentBvids
        val finalMetas = if (metaList.isNotEmpty()) metaList else item.recentBvids.map { CollectionVideoMeta(bvid = it) }
        return CollectionInfo(
            seasonId = item.id,
            title = item.name,
            mid = mid,
            bvidList = finalList,
            total = finalList.size,
            videoMetas = finalMetas,
            type = item.type
        )
    }
}
