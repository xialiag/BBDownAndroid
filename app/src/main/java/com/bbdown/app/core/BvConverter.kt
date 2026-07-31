package com.bbdown.app.core

/**
 * BV 与 av 互转，移植自 BBDown.Core.Util.BilibiliBvConverter
 */
object BvConverter {
    private const val XOR_CODE = 23442827791579L
    private const val MASK_CODE = (1L shl 51) - 1
    private const val MAX_AID = MASK_CODE + 1
    private const val BASE = 58L
    private const val BV_LEN = 9
    private val ALPHABET = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf".toByteArray()
    private val REV = HashMap<Byte, Long>()

    init {
        for (i in ALPHABET.indices) REV[ALPHABET[i]] = i.toLong()
    }

    fun encode(avid: Long): String {
        require(avid in 1 until MAX_AID)
        val bvid = ByteArray(BV_LEN)
        var tmp = (MAX_AID or avid) xor XOR_CODE
        var i = BV_LEN - 1
        while (tmp != 0L) {
            bvid[i] = ALPHABET[(tmp % BASE).toInt()]
            tmp /= BASE
            i--
        }
        var t = bvid[0]; bvid[0] = bvid[6]; bvid[6] = t
        t = bvid[1]; bvid[1] = bvid[4]; bvid[4] = t
        return "BV1" + String(bvid)
    }

    fun decode(bvid: String): Long {
        require(bvid.length == BV_LEN) { "BV1$bvid must be 12 char" }
        val b = bvid.toByteArray()
        var t = b[0]; b[0] = b[6]; b[6] = t
        t = b[1]; b[1] = b[4]; b[4] = t
        var avid = 0L
        for (byte in b) {
            avid = avid * BASE + (REV[byte] ?: throw IllegalArgumentException("invalid bv char"))
        }
        return (avid and MASK_CODE) xor XOR_CODE
    }
}
