package com.kwebmn.dvripcam.video

/**
 * Минимальный парсер H.264 SPS для получения размеров кадра.
 * Поддерживает распространённые профили (Baseline/Main) без scaling-матриц —
 * этого достаточно для нашей камеры (Main profile). Иначе вернёт null.
 */
object SpsParser {

    /** nal: NAL-юнит SPS, начинается со старт-кода 00 00 00 01 и байта заголовка (0x67). */
    fun dimensions(nal: ByteArray): Pair<Int, Int>? = runCatching {
        // пропустить старт-код (4 байта) + NAL-header (1 байт), убрать emulation-байты
        var start = 0
        if (nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1) start = 4
        else if (nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1) start = 3
        val rbsp = removeEmulation(nal, start + 1) // +1 = пропустить NAL-header
        val r = BitReader(rbsp)

        val profileIdc = r.u(8)
        r.u(8)           // constraint flags + reserved
        r.u(8)           // level_idc
        r.ue()           // seq_parameter_set_id

        if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            val chroma = r.ue()
            if (chroma == 3) r.u(1)
            r.ue(); r.ue()   // bit_depth_luma/chroma minus8
            r.u(1)           // qpprime_y_zero_transform_bypass
            if (r.u(1) == 1) return null // scaling matrix present — не поддерживаем
        }

        r.ue()               // log2_max_frame_num_minus4
        val pocType = r.ue()
        if (pocType == 0) {
            r.ue()           // log2_max_pic_order_cnt_lsb_minus4
        } else if (pocType == 1) {
            r.u(1); r.se(); r.se()
            val n = r.ue()
            repeat(n) { r.se() }
        }
        r.ue()               // max_num_ref_frames
        r.u(1)               // gaps_in_frame_num_value_allowed

        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.u(1)
        if (frameMbsOnly == 0) r.u(1) // mb_adaptive_frame_field
        r.u(1)               // direct_8x8_inference

        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.u(1) == 1) {   // frame_cropping_flag
            cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue()
        }

        val width = widthMbs * 16 - (cropL + cropR) * 2
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropT + cropB) * 2
        if (width in 16..8192 && height in 16..8192) width to height else null
    }.getOrNull()

    private fun removeEmulation(data: ByteArray, from: Int): ByteArray {
        val out = ArrayList<Byte>(data.size - from)
        var zeros = 0
        var i = from
        while (i < data.size) {
            val b = data[i]
            if (zeros >= 2 && b.toInt() == 3 && i + 1 < data.size && data[i + 1].toInt() and 0xFF <= 3) {
                zeros = 0; i++; continue // пропустить emulation_prevention_three_byte
            }
            out.add(b)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
            i++
        }
        return out.toByteArray()
    }

    private class BitReader(val data: ByteArray) {
        var bytePos = 0; var bitPos = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                val bit = (data[bytePos].toInt() shr (7 - bitPos)) and 1
                v = (v shl 1) or bit
                bitPos++; if (bitPos == 8) { bitPos = 0; bytePos++ }
            }
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) zeros++
            if (zeros == 0) return 0
            return (1 shl zeros) - 1 + u(zeros)
        }
        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }
    }
}
