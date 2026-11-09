/*  Copyright (C) 2022-2024 Andreas Shimokawa

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */

/* A faithful Kotlin port of the pure-Java port of tiny-ECDH (B163 only).
   Kept close to the C/Java arithmetic intentionally: this code must produce
   byte-identical keys to the_band's expectations, so clever Kotlin idioms
   are avoided on purpose. */

package app.health

object ECDH_B163 {
    private const val CURVE_DEGREE = 163
    private const val ECC_PRV_KEY_SIZE = 24
    private const val ECC_PUB_KEY_SIZE = 2 * ECC_PRV_KEY_SIZE

    /* margin for overhead needed in intermediate calculations */
    private const val BITVEC_MARGIN = 3
    private const val BITVEC_NBITS = CURVE_DEGREE + BITVEC_MARGIN
    private const val BITVEC_NWORDS = (BITVEC_NBITS + 31) / 32
    private const val BITVEC_NBYTES = 4 * BITVEC_NWORDS

    /* NIST B-163 */
    private val polynomial = intArrayOf(0x000000c9, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000008)
    private val coeff_b = intArrayOf(0x4a3205fd, 0x512f7874, 0x1481eb10, 0xb8c953ca.toInt(), 0x0a601907, 0x00000002)
    private val base_x = intArrayOf(0xe8343e36.toInt(), 0xd4994637.toInt(), 0xa0991168.toInt(), 0x86a2d57e.toInt(), 0xf0eba162.toInt(), 0x00000003)
    private val base_y = intArrayOf(0x797324f1, 0xb11c5c0c.toInt(), 0xa2cdd545.toInt(), 0x71a0094f, 0xd51fbc6c.toInt(), 0x00000000)
    private val base_order = intArrayOf(0xa4234c33.toInt(), 0x77e70c12, 0x000292fe, 0x00000000, 0x00000000, 0x00000004)

    /* bit-vector helpers */
    private fun bitvec_get_bit(x: IntArray, idx: Int): Int =
        ((x[idx / 32].toLong() and 0xffffffffL) shr (idx and 31) and 1L).toInt()

    private fun bitvec_clr_bit(x: IntArray, idx: Int) {
        x[idx / 32] = x[idx / 32] and (1 shl (idx and 31)).inv()
    }

    private fun bitvec_copy(x: IntArray, y: IntArray) {
        for (index in 0 until BITVEC_NWORDS) x[index] = y[index]
    }

    private fun bitvec_swap(x: IntArray, y: IntArray) {
        val tmp = IntArray(BITVEC_NWORDS)
        bitvec_copy(tmp, x)
        bitvec_copy(x, y)
        bitvec_copy(y, tmp)
    }

    private fun bitvec_equal(x: IntArray, y: IntArray): Boolean {
        for (index in 0 until BITVEC_NWORDS) {
            if (x[index] != y[index]) return false
        }
        return true
    }

    private fun bitvec_set_zero(x: IntArray) {
        for (index in 0 until BITVEC_NWORDS) x[index] = 0
    }

    private fun bitvec_is_zero(x: IntArray): Boolean {
        for (index in 0 until BITVEC_NWORDS) {
            if (x[index] != 0) return false
        }
        return true
    }

    /* return the number of the highest one-bit + 1 */
    private fun bitvec_degree(x: IntArray): Int {
        var degree = BITVEC_NWORDS * 32
        var y = BITVEC_NWORDS
        while (degree > 0 && x[--y] == 0) {
            degree -= 32
        }
        if (degree != 0) {
            var u32mask = 1 shl 31
            while ((x[y] and u32mask) == 0) {
                u32mask = ((u32mask.toLong() and 0xffffffffL) shr 1).toInt()
                degree -= 1
            }
        }
        return degree
    }

    /* left-shift by 'count' digits */
    private fun bitvec_lshift(x: IntArray, y: IntArray, nbits: Int) {
        val nwords = nbits / 32
        for (index in 0 until nwords) x[index] = 0
        var i = nwords
        var j = 0
        while (i < BITVEC_NWORDS) {
            x[i] = y[j]
            i += 1
            j += 1
        }
        val rest = nbits and 31
        if (rest != 0) {
            i = BITVEC_NWORDS - 1
            while (i > 0) {
                x[i] = ((x[i].toLong() shl rest) or ((x[i - 1].toLong() and 0xffffffffL) shr (32 - rest))).toInt()
                i -= 1
            }
            x[0] = (x[0].toLong() shl rest).toInt()
        }
    }

    /* GF(2^CURVE_DEGREE) arithmetic */
    private fun gf2field_set_one(x: IntArray) {
        x[0] = 1
        for (index in 1 until BITVEC_NWORDS) x[index] = 0
    }

    private fun gf2field_is_one(x: IntArray): Boolean {
        if (x[0] != 1) return false
        for (index in 1 until BITVEC_NWORDS) {
            if (x[index] != 0) return false
        }
        return true
    }

    private fun gf2field_add(z: IntArray, x: IntArray, y: IntArray) {
        for (index in 0 until BITVEC_NWORDS) z[index] = x[index] xor y[index]
    }

    private fun gf2field_inc(x: IntArray) {
        x[0] = x[0] xor 1
    }

    private fun gf2field_mul(z: IntArray, x: IntArray, y: IntArray) {
        val tmp = IntArray(BITVEC_NWORDS)
        bitvec_copy(tmp, x)
        if (bitvec_get_bit(y, 0) != 0) {
            bitvec_copy(z, x)
        } else {
            bitvec_set_zero(z)
        }
        for (index in 1 until CURVE_DEGREE) {
            bitvec_lshift(tmp, tmp, 1)
            if (bitvec_get_bit(tmp, CURVE_DEGREE) != 0) {
                gf2field_add(tmp, tmp, polynomial)
            }
            if (bitvec_get_bit(y, index) != 0) {
                gf2field_add(z, z, tmp)
            }
        }
    }

    private fun gf2field_inv(z: IntArray, x: IntArray) {
        val u = IntArray(BITVEC_NWORDS)
        val v = IntArray(BITVEC_NWORDS)
        val g = IntArray(BITVEC_NWORDS)
        val h = IntArray(BITVEC_NWORDS)
        bitvec_copy(u, x)
        bitvec_copy(v, polynomial)
        bitvec_set_zero(g)
        gf2field_set_one(z)
        while (!gf2field_is_one(u)) {
            var i = bitvec_degree(u) - bitvec_degree(v)
            if (i < 0) {
                bitvec_swap(u, v)
                bitvec_swap(g, z)
                i = -i
            }
            bitvec_lshift(h, v, i)
            gf2field_add(u, u, h)
            bitvec_lshift(h, g, i)
            gf2field_add(z, z, h)
        }
    }

    /* point arithmetic */
    private fun gf2point_copy(x1: IntArray, y1: IntArray, x2: IntArray, y2: IntArray) {
        bitvec_copy(x1, x2)
        bitvec_copy(y1, y2)
    }

    private fun gf2point_set_zero(x: IntArray, y: IntArray) {
        bitvec_set_zero(x)
        bitvec_set_zero(y)
    }

    private fun gf2point_is_zero(x: IntArray, y: IntArray): Boolean =
        bitvec_is_zero(x) && bitvec_is_zero(y)

    private fun gf2point_double(x: IntArray, y: IntArray) {
        if (bitvec_is_zero(x)) {
            bitvec_set_zero(y)
        } else {
            val l = IntArray(BITVEC_NWORDS)
            gf2field_inv(l, x)
            gf2field_mul(l, l, y)
            gf2field_add(l, l, x)
            gf2field_mul(y, x, x)
            gf2field_mul(x, l, l)
            gf2field_inc(l)
            gf2field_add(x, x, l)
            gf2field_mul(l, l, x)
            gf2field_add(y, y, l)
        }
    }

    private fun gf2point_add(x1: IntArray, y1: IntArray, x2: IntArray, y2: IntArray) {
        if (gf2point_is_zero(x2, y2)) return
        if (gf2point_is_zero(x1, y1)) {
            gf2point_copy(x1, y1, x2, y2)
        } else {
            if (bitvec_equal(x1, x2)) {
                if (bitvec_equal(y1, y2)) {
                    gf2point_double(x1, y1)
                } else {
                    gf2point_set_zero(x1, y1)
                }
            } else {
                val a = IntArray(BITVEC_NWORDS)
                val b = IntArray(BITVEC_NWORDS)
                val c = IntArray(BITVEC_NWORDS)
                val d = IntArray(BITVEC_NWORDS)
                gf2field_add(a, y1, y2)
                gf2field_add(b, x1, x2)
                gf2field_inv(c, b)
                gf2field_mul(c, c, a)
                gf2field_mul(d, c, c)
                gf2field_add(d, d, c)
                gf2field_add(d, d, b)
                gf2field_inc(d)
                gf2field_add(x1, x1, d)
                gf2field_mul(a, x1, c)
                gf2field_add(a, a, d)
                gf2field_add(y1, y1, a)
                bitvec_copy(x1, d)
            }
        }
    }

    private fun gf2point_mul(x: IntArray, y: IntArray, exp: IntArray) {
        val tmpx = IntArray(BITVEC_NWORDS)
        val tmpy = IntArray(BITVEC_NWORDS)
        val nbits = bitvec_degree(exp)
        gf2point_set_zero(tmpx, tmpy)
        for (i in nbits - 1 downTo 0) {
            gf2point_double(tmpx, tmpy)
            if (bitvec_get_bit(exp, i) != 0) {
                gf2point_add(tmpx, tmpy, x, y)
            }
        }
        gf2point_copy(x, y, tmpx, tmpy)
    }

    private fun gf2point_on_curve(x: IntArray, y: IntArray): Boolean {
        val a = IntArray(BITVEC_NWORDS)
        val b = IntArray(BITVEC_NWORDS)
        if (gf2point_is_zero(x, y)) {
            return false
        }
        gf2field_mul(a, x, x)
        gf2field_mul(b, a, x)
        gf2field_add(a, a, b)
        gf2field_add(a, a, coeff_b)
        gf2field_mul(b, y, y)
        gf2field_add(a, a, b)
        gf2field_mul(b, x, y)
        return bitvec_equal(a, b)
    }

    /* byte conversion helpers */
    private fun bytes_to_int(bytes: ByteArray, offset: Int): IntArray {
        val value = IntArray(BITVEC_NWORDS)
        var byteptr = offset
        for (index in 0 until BITVEC_NWORDS) {
            value[index] = (bytes[byteptr++].toInt() and 0xff) or
                ((bytes[byteptr++].toInt() and 0xff) shl 8) or
                ((bytes[byteptr++].toInt() and 0xff) shl 16) or
                ((bytes[byteptr++].toInt() and 0xff) shl 24)
        }
        return value
    }

    private fun ints_to_bytes(bytes: ByteArray, ints: IntArray, offset: Int) {
        var byteptr = offset
        for (index in 0 until BITVEC_NWORDS) {
            bytes[byteptr++] = (ints[index] and 0x000000ff).toByte()
            bytes[byteptr++] = ((ints[index] and 0x0000ff00) shr 8).toByte()
            bytes[byteptr++] = ((ints[index] and 0x00ff0000) shr 16).toByte()
            bytes[byteptr++] = ((ints[index] and 0xff000000.toInt()) shr 24).toByte()
        }
    }

    /* ECDH itself */
    private fun ecdh_generate_keys(public_key: ByteArray, private_key: ByteArray): Boolean {
        val private_key_int32 = bytes_to_int(private_key, 0)
        val public_key_int32_1 = bytes_to_int(public_key, 0)
        val public_key_int32_2 = bytes_to_int(public_key, BITVEC_NBYTES)
        gf2point_copy(public_key_int32_1, public_key_int32_2, base_x, base_y)
        if (bitvec_degree(private_key_int32) < CURVE_DEGREE / 2) {
            return false
        }
        val nbits = bitvec_degree(base_order)
        for (i in nbits - 1 until BITVEC_NWORDS * 32) {
            bitvec_clr_bit(private_key_int32, i)
        }
        gf2point_mul(public_key_int32_1, public_key_int32_2, private_key_int32)
        ints_to_bytes(public_key, public_key_int32_1, 0)
        ints_to_bytes(public_key, public_key_int32_2, BITVEC_NBYTES)
        return true
    }

    private fun ecdh_shared_secret(private_key: ByteArray, others_pub: ByteArray, output: ByteArray): Boolean {
        val private_key_int32 = bytes_to_int(private_key, 0)
        val others_pub_int32_1 = bytes_to_int(others_pub, 0)
        val others_pub_int32_2 = bytes_to_int(others_pub, BITVEC_NBYTES)
        if (!gf2point_is_zero(others_pub_int32_1, others_pub_int32_2) &&
            gf2point_on_curve(others_pub_int32_1, others_pub_int32_2)
        ) {
            for (i in 0 until BITVEC_NBYTES * 2) {
                output[i] = others_pub[i]
            }
            val nbits = bitvec_degree(base_order)
            for (i in nbits - 1 until BITVEC_NWORDS * 32) {
                bitvec_clr_bit(private_key_int32, i)
            }
            val output_int32_1 = bytes_to_int(output, 0)
            val output_int32_2 = bytes_to_int(output, BITVEC_NBYTES)
            gf2point_mul(output_int32_1, output_int32_2, private_key_int32)
            ints_to_bytes(output, output_int32_1, 0)
            ints_to_bytes(output, output_int32_2, BITVEC_NBYTES)
            return true
        }
        return false
    }

    /* wrappers used by BandClient */
    fun ecdh_generate_public(privateEC: ByteArray): ByteArray? {
        val pubKey = ByteArray(ECC_PUB_KEY_SIZE)
        return if (ecdh_generate_keys(pubKey, privateEC)) pubKey else null
    }

    fun ecdh_generate_shared(privateEC: ByteArray, remotePublicEC: ByteArray): ByteArray? {
        val sharedKey = ByteArray(ECC_PUB_KEY_SIZE)
        return if (ecdh_shared_secret(privateEC, remotePublicEC, sharedKey)) sharedKey else null
    }
}
