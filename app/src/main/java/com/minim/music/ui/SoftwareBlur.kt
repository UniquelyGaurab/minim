package com.minim.music.ui

/**
 * v4.9.8: the fallback blur for Android 9 to 11, which have no RenderEffect. It runs on a small
 * copy of the cover (about 96 px wide): three box blur passes each way look like a Gaussian, and
 * the ImageView's smooth scaling turns the small result into a soft full screen background.
 * Pure Kotlin on ARGB ints so the tests can run it; alpha is kept as it is.
 */
object SoftwareBlur {

    fun blur(src: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3): IntArray {
        require(src.size == w * h) { "pixel count ${src.size} != $w x $h" }
        if (radius <= 0 || w <= 0 || h <= 0) return src.copyOf()
        var a = src.copyOf()
        var b = IntArray(src.size)
        repeat(passes) {
            boxHorizontal(a, b, w, h, radius)
            boxVertical(b, a, w, h, radius)
        }
        return a
    }

    /** One horizontal box pass with clamped edges (a sliding window sum, O(w*h)). */
    private fun boxHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[row + i.coerceIn(0, w - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = src[i.coerceIn(0, h - 1) * w + x]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
