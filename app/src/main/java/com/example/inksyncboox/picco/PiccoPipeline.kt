package com.example.inksyncboox.picco

/**
 * Image steps of the Picco export (docs/picco-export-spec.md, section 4). Pure integer math so
 * the Kotlin, Swift and Python implementations produce identical pixels.
 *
 * Gray images are ByteArrays read as unsigned (0 = black, 255 = white), row-major.
 */
object PiccoPipeline {

    /** round(255 × (v/255)^(10/9)), fixed so no platform `pow` is involved. */
    val GAMMA_LUT = intArrayOf(
        0, 1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 8, 9, 9, 10, 11, 12, 13, 13, 14, 15, 16, 17, 18, 18, 19, 20, 21, 22, 23, 24, 25,
        25, 26, 27, 28, 29, 30, 31, 32, 33, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46, 47, 48, 49, 50,
        51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77,
        78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104,
        105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127,
        128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 144, 145, 146, 147, 148, 149, 150,
        151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173,
        174, 175, 176, 177, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 193, 194, 195, 196, 197,
        198, 199, 200, 201, 202, 203, 204, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 218, 219, 220, 221,
        222, 223, 224, 225, 226, 227, 228, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 241, 242, 243, 244, 245,
        246, 247, 248, 249, 251, 252, 253, 254, 255
    )

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    /** Step 1: ARGB to gray, blending translucent pixels over white. */
    fun toGray(argb: IntArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (i in 0 until w * h) {
            val c = argb[i]
            val a = (c ushr 24) and 255
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            var y = (299 * r + 587 * g + 114 * b + 500) / 1000
            if (a < 255) y = (y * a + 255 * (255 - a) + 127) / 255
            out[i] = y.toByte()
        }
        return out
    }

    /** Box filter (shrinking). */
    fun box(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        for (oy in 0 until dh) {
            val y0 = (oy.toLong() * sh / dh).toInt()
            val y1 = maxOf(y0 + 1, ((oy + 1).toLong() * sh / dh).toInt())
            for (ox in 0 until dw) {
                val x0 = (ox.toLong() * sw / dw).toInt()
                val x1 = maxOf(x0 + 1, ((ox + 1).toLong() * sw / dw).toInt())
                var sum = 0L
                for (y in y0 until y1) {
                    val row = y * sw
                    for (x in x0 until x1) sum += src.u(row + x)
                }
                val n = ((x1 - x0) * (y1 - y0)).toLong()
                out[oy * dw + ox] = ((sum + n / 2) / n).toInt().toByte()
            }
        }
        return out
    }

    private fun bilinearAxis(o: Int, sn: Int, dn: Int): IntArray {
        var fp = ((2L * o + 1) * sn * 65536L) / (2L * dn) - 32768L
        fp = fp.coerceIn(0L, (sn - 1).toLong() * 65536L)
        val i0 = (fp shr 16).toInt()
        return intArrayOf(i0, minOf(i0 + 1, sn - 1), (fp and 0xFFFF).toInt())
    }

    /** Bilinear filter (enlarging), 16.16 fixed point. */
    fun bilinear(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): ByteArray {
        val out = ByteArray(dw * dh)
        val xs = Array(dw) { bilinearAxis(it, sw, dw) }
        for (oy in 0 until dh) {
            val (y0, y1, wy) = bilinearAxis(oy, sh, dh).let { Triple(it[0], it[1], it[2]) }
            for (ox in 0 until dw) {
                val (x0, x1, wx) = xs[ox].let { Triple(it[0], it[1], it[2]) }
                val top = src.u(y0 * sw + x0).toLong() * (65536 - wx) + src.u(y0 * sw + x1).toLong() * wx
                val bot = src.u(y1 * sw + x0).toLong() * (65536 - wx) + src.u(y1 * sw + x1).toLong() * wx
                out[oy * dw + ox] = ((top * (65536 - wy) + bot * wy + (1L shl 31)) shr 32).toInt().toByte()
            }
        }
        return out
    }

    /** Step 2: content bounds [x0, y0, x1, y1), or the whole page. */
    fun autoCrop(gray: ByteArray, w: Int, h: Int): IntArray {
        val cw = maxOf(1, w * 2 / 5)
        val ch = maxOf(1, h * 2 / 5)
        val small = box(gray, w, h, cw, ch)
        var minX = cw
        var minY = ch
        var maxX = -1
        var maxY = -1
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val v = small.u(y * cw + x)
                if (v > 170 || v < 5) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return intArrayOf(0, 0, w, h)
        var x0 = (minX.toLong() * w / cw).toInt()
        var x1 = (((maxX + 1).toLong() * w + cw - 1) / cw).toInt()
        var y0 = (minY.toLong() * h / ch).toInt()
        var y1 = (((maxY + 1).toLong() * h + ch - 1) / ch).toInt()
        val padX = (w * 12 + 500) / 1000
        val padY = (h * 12 + 500) / 1000
        x0 = maxOf(0, x0 - padX)
        y0 = maxOf(0, y0 - padY)
        x1 = minOf(w, x1 + padX)
        y1 = minOf(h, y1 + padY)
        if ((x1 - x0).toLong() * (y1 - y0) * 100 < 15L * w * h) return intArrayOf(0, 0, w, h)
        return intArrayOf(x0, y0, x1, y1)
    }

    fun subImage(gray: ByteArray, w: Int, rect: IntArray): ByteArray {
        val (x0, y0, x1, y1) = rect.toList()
        val rw = x1 - x0
        val out = ByteArray(rw * (y1 - y0))
        for (y in y0 until y1) System.arraycopy(gray, y * w + x0, out, (y - y0) * rw, rw)
        return out
    }

    /** Step 4: 90° clockwise; returns the image and its new width × height. */
    fun rotateCw(img: ByteArray, w: Int, h: Int): Triple<ByteArray, Int, Int> {
        val out = ByteArray(w * h)
        for (ny in 0 until w) {
            for (nx in 0 until h) out[ny * h + nx] = img[(h - 1 - nx) * w + ny]
        }
        return Triple(out, h, w)
    }

    /** Step 5: fit inside tw × th (480 × 800 for XTC, 464 × 764 for EPUB), enlarging at most 2.5×. */
    fun fit(img: ByteArray, rw: Int, rh: Int, tw: Int = Xtc.WIDTH, th: Int = Xtc.HEIGHT): Triple<ByteArray, Int, Int> {
        var dw: Int
        var dh: Int
        if (tw.toLong() * rh <= th.toLong() * rw) {
            dw = tw
            dh = maxOf(1, ((rh.toLong() * tw + rw / 2) / rw).toInt())
        } else {
            dh = th
            dw = maxOf(1, ((rw.toLong() * th + rh / 2) / rh).toInt())
        }
        if (2L * dw > 5L * rw) {
            dw = maxOf(1, 5 * rw / 2)
            dh = maxOf(1, 5 * rh / 2)
        }
        val scaled = if (dw.toLong() * dh > rw.toLong() * rh) bilinear(img, rw, rh, dw, dh) else box(img, rw, rh, dw, dh)
        return Triple(scaled, dw, dh)
    }

    /** Step 6: stretch the darkest/brightest 0.5% to black/white (if worthwhile), then gamma. */
    fun levelsGamma(img: ByteArray): ByteArray {
        val n = img.size
        val hist = IntArray(256)
        for (i in 0 until n) hist[img.u(i)]++
        val k = n.toLong() * 5 / 1000
        var cum = 0L
        var lo = 0
        for (v in 0..255) {
            cum += hist[v]
            if (cum > k) { lo = v; break }
        }
        cum = 0L
        var hi = 255
        for (v in 255 downTo 0) {
            cum += hist[v]
            if (cum > k) { hi = v; break }
        }
        val stretch = hi - lo >= 16
        val out = ByteArray(n)
        for (i in 0 until n) {
            var v = img.u(i)
            if (stretch) {
                v = when {
                    v <= lo -> 0
                    v >= hi -> 255
                    else -> ((v - lo) * 255 + (hi - lo) / 2) / (hi - lo)
                }
            }
            out[i] = GAMMA_LUT[v].toByte()
        }
        return out
    }

    /** Step 7: brightness indices (0 = black .. 3 or 1 = white), serpentine scan. */
    fun dither(img: ByteArray, w: Int, h: Int, twoBit: Boolean, method: PiccoDither): ByteArray {
        val out = ByteArray(w * h)
        var cur = IntArray(w)
        var nxt = IntArray(w)
        var nxt2 = IntArray(w)
        for (y in 0 until h) {
            val d = if (y % 2 == 0) 1 else -1
            var x = if (d == 1) 0 else w - 1
            repeat(w) {
                var v = img.u(y * w + x) + if (method != PiccoDither.NONE) cur[x] else 0
                v = v.coerceIn(0, 255)
                val b: Int
                val level: Int
                if (twoBit) {
                    b = (v * 3 + 127) / 255
                    level = b * 85
                } else {
                    b = if (v >= 128) 1 else 0
                    level = b * 255
                }
                out[y * w + x] = b.toByte()
                val err = v - level
                when (method) {
                    PiccoDither.FLOYD_STEINBERG -> {
                        val e7 = err * 7 / 16
                        val e3 = err * 3 / 16
                        val e5 = err * 5 / 16
                        val e1 = err - e7 - e3 - e5
                        if (x + d in 0 until w) cur[x + d] += e7
                        if (x - d in 0 until w) nxt[x - d] += e3
                        nxt[x] += e5
                        if (x + d in 0 until w) nxt[x + d] += e1
                    }
                    PiccoDither.ATKINSON -> {
                        val e = err / 8
                        if (x + d in 0 until w) cur[x + d] += e
                        if (x + 2 * d in 0 until w) cur[x + 2 * d] += e
                        if (x - d in 0 until w) nxt[x - d] += e
                        nxt[x] += e
                        if (x + d in 0 until w) nxt[x + d] += e
                        nxt2[x] += e
                    }
                    PiccoDither.NONE -> Unit
                }
                x += d
            }
            val recycled = cur
            cur = nxt
            nxt = nxt2
            nxt2 = recycled.also { it.fill(0) }
        }
        return out
    }

    /** Step 8: centers the image on a white 480 × 800 canvas. */
    fun compose(indices: ByteArray, dw: Int, dh: Int, twoBit: Boolean): ByteArray {
        val white: Byte = if (twoBit) 3 else 1
        val canvas = ByteArray(Xtc.WIDTH * Xtc.HEIGHT) { white }
        val ox = (Xtc.WIDTH - dw) / 2
        val oy = (Xtc.HEIGHT - dh) / 2
        for (y in 0 until dh) System.arraycopy(indices, y * dw, canvas, (oy + y) * Xtc.WIDTH + ox, dw)
        return canvas
    }

    /** EPUB: centers a smooth gray image on a white tw × th canvas. */
    fun composeGray(img: ByteArray, dw: Int, dh: Int, tw: Int, th: Int): ByteArray {
        val canvas = ByteArray(tw * th) { -1 }
        val ox = (tw - dw) / 2
        val oy = (th - dh) / 2
        for (y in 0 until dh) System.arraycopy(img, y * dw, canvas, (oy + y) * tw + ox, dw)
        return canvas
    }
}
