/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [IcomonAeroHandler.parseWeightFrame].
 *
 * The idle frame is verbatim from an openScale log of an "Aero I" (Urikar Aero I Pro). The
 * weighing frames are synthetic: built from the layout decoded out of the Fitdays SDK, with a
 * valid checksum. They have not yet been compared with a captured weigh-in.
 */
class IcomonAeroHandlerTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Builds a 0xD5 frame for [grams] with the given kg precision index and stable flag. */
    private fun weightFrame(grams: Int, stable: Boolean, kgPrecision: Int = 3, seq: Int = 0): ByteArray {
        val word = (if (stable) 1 shl 31 else 0) or (4 shl 21) or (kgPrecision shl 18) or grams
        val f = ByteArray(20)
        f[0] = 0xAC.toByte()
        f[1] = 0x27
        f[2] = (word ushr 24).toByte()
        f[3] = (word ushr 16).toByte()
        f[4] = (word ushr 8).toByte()
        f[5] = word.toByte()
        f[18] = 0xD5.toByte()
        val sum = (2..18).sumOf { f[it].toInt() and 0xFF }
        f[19] = ((seq shl 5) or (sum and 0x1F)).toByte()
        return f
    }

    @Test
    fun `decodes the idle frame captured from the scale as zero weight`() {
        val idle = hex("AC27008C0000000000000000000000000000D501")
        val reading = IcomonAeroHandler.parseWeightFrame(idle)
        assertThat(reading).isNotNull()
        assertThat(reading!!.grams).isEqualTo(0)
        assertThat(reading.stable).isFalse()
        assertThat(reading.kgPrecision).isEqualTo(3)
    }

    @Test
    fun `decodes a stable 80_3 kg weigh-in`() {
        val reading = IcomonAeroHandler.parseWeightFrame(weightFrame(80_300, stable = true))!!
        assertThat(reading.stable).isTrue()
        assertThat(reading.grams).isEqualTo(80_300)
        assertThat(reading.weightKg).isWithin(1e-3f).of(80.3f)
    }

    @Test
    fun `rounds to the 0_1 kg display division`() {
        val reading = IcomonAeroHandler.parseWeightFrame(weightFrame(80_349, stable = true))!!
        assertThat(reading.weightKg).isWithin(1e-3f).of(80.3f)
        val up = IcomonAeroHandler.parseWeightFrame(weightFrame(80_350, stable = true))!!
        assertThat(up.weightKg).isWithin(1e-3f).of(80.4f)
    }

    @Test
    fun `settling frames are not stable`() {
        val reading = IcomonAeroHandler.parseWeightFrame(weightFrame(79_900, stable = false))!!
        assertThat(reading.stable).isFalse()
    }

    @Test
    fun `rejects a bad checksum, other packet types and wrong sizes`() {
        val bad = weightFrame(80_300, stable = true).also { it[19] = (it[19] + 1).toByte() }
        assertThat(IcomonAeroHandler.parseWeightFrame(bad)).isNull()

        val otherType = weightFrame(80_300, stable = true).also { it[18] = 0xD0.toByte() }
        assertThat(IcomonAeroHandler.parseWeightFrame(otherType)).isNull()

        assertThat(IcomonAeroHandler.parseWeightFrame(ByteArray(8))).isNull()
    }
}
