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

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import org.junit.Test
import java.util.Calendar

/**
 * Tests for [IcomonAeroHandler]'s packet parsers.
 *
 * The idle frame and the two result packets are verbatim from openScale logs of an "Aero I"
 * (Urikar Aero I Pro): 552 ohm from a barefoot weigh-in, and none from a weigh-in where the feet did
 * not make contact. The weighing frames are synthetic, built from the decoded layout with a valid
 * checksum; the captured stable frame `AC 27 80 8D 3B 3C …` decodes to the same 80.7 kg.
 */
class IcomonAeroHandlerTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
        val idle = hex("AC 27 00 8C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 D5 01")
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

    @Test
    fun `decodes the captured stable frame as 80_7 kg`() {
        val frame = hex("AC 27 80 8D 3B 3C 00 00 00 00 00 00 00 00 00 00 00 00 D5 19")
        val reading = IcomonAeroHandler.parseWeightFrame(frame)!!
        assertThat(reading.stable).isTrue()
        assertThat(reading.grams).isEqualTo(80_700)
        assertThat(reading.weightKg).isWithin(1e-3f).of(80.7f)
    }

    @Test
    fun `reads 552 ohm from the captured barefoot result packet`() {
        val frame = hex("AC 27 01 00 02 28 00 00 00 00 00 00 00 00 00 00 00 07 D6 08")
        assertThat(IcomonAeroHandler.parseResultFrame(frame)).isEqualTo(552)
    }

    @Test
    fun `a result packet without contact reports zero impedance`() {
        val frame = hex("AC 27 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 07 D6 1E")
        assertThat(IcomonAeroHandler.parseResultFrame(frame)).isEqualTo(0)
    }

    @Test
    fun `result parser rejects weight packets, stored records and bad checksums`() {
        assertThat(IcomonAeroHandler.parseResultFrame(weightFrame(80_700, stable = true))).isNull()

        // Stored record (type 0xD8) captured from the scale.
        val stored = hex("AC 27 00 6A D6 CC C3 80 8D 3C CC 00 00 00 01 02 30 07 D8 16")
        assertThat(IcomonAeroHandler.parseResultFrame(stored)).isNull()

        val bad = hex("AC 27 01 00 02 28 00 00 00 00 00 00 00 00 00 00 00 07 D6 08").also { it[19] = 0x09 }
        assertThat(IcomonAeroHandler.parseResultFrame(bad)).isNull()
    }

    private fun user(): ScaleUser = ScaleUser().apply {
        id = 1
        gender = GenderType.MALE
        bodyHeight = 174f
        birthday = Calendar.getInstance().apply { add(Calendar.YEAR, -39) }.time
    }

    @Test
    fun `plausible impedance adds body composition`() {
        val m = IcomonAeroHandler.buildMeasurement(user(), 80.7f, 552)

        assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(80.7f)
        assertThat(m[MeasurementType.IMPEDANCE]!!.value).isWithin(1e-3f).of(552f)
        val fat = m[MeasurementType.BODY_FAT]!!.value
        val water = m[MeasurementType.WATER]!!.value
        assertThat(fat).isIn(Range.closed(5f, 50f))
        assertThat(water).isIn(Range.closed(30f, 75f))
        assertThat(MeasurementType.MUSCLE in m).isTrue()
        assertThat(MeasurementType.BONE in m).isTrue()
    }

    @Test
    fun `no or implausible impedance publishes the weight alone`() {
        for (impedance in listOf(null, 0, 1500, 65_535)) {
            val m = IcomonAeroHandler.buildMeasurement(user(), 80.7f, impedance)
            assertThat(m[MeasurementType.WEIGHT]!!.value).isWithin(1e-3f).of(80.7f)
            assertThat(MeasurementType.IMPEDANCE in m).isFalse()
            assertThat(MeasurementType.BODY_FAT in m).isFalse()
        }
    }
}
