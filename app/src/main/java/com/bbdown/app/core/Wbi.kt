package com.bbdown.app.core

import java.security.MessageDigest

/**
 * WBI 签名实现，移植自 BBDown BBDownUtil.GetMixinKey + Parser.WbiSign
 */
object Wbi {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    )

    fun getMixinKey(orig: String): String {
        val sb = StringBuilder(32)
        for (idx in MIXIN_KEY_ENC_TAB) sb.append(orig[idx])
        return sb.toString()
    }

    fun extractKeyFromUrl(url: String): String {
        val name = url.substringAfterLast('/')
        return name.substringBeforeLast('.')
    }

    fun sign(params: String, mixinKey: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest((params + mixinKey).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
