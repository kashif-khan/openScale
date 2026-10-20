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

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Urikar "Aero I" / "Aero I Pro" (Fitdays app, ICOMON "ScaleNew2" protocol, advertisement
 * version nibble 7).
 *
 * Shares service 0xFFB0 with [MGBHandler], but speaks a different, 20-byte framed protocol on the
 * notify characteristic 0xFFB2. [MGBHandler] ignores these frames, so the scale connects and then
 * stays silent; this handler decodes them instead. Nothing has to be written to the scale to make
 * it stream weight.
 *
 * Frame, derived from the vendor SDK's `ICBleScaleNew20Protocol` and checked against captured
 * idle frames:
 * ```
 * AC 27 [16 payload bytes] [type] [seq<<5 | checksum]
 * checksum = sum(bytes[2..18]) & 0x1F
 * ```
 * Type 0xD5 is the weight/state packet. Its payload starts with a big-endian 32-bit word:
 * ```
 * bit 31      stable (weight locked); 0 while idle or settling
 * bits 21-23  lb precision index
 * bits 18-20  kg precision index (3 = 0.1 kg steps, the Aero I Pro's display division)
 * bits 0-17   weight in grams
 * ```
 *
 * About three seconds after the weight locks the scale sends the result packet, type 0xD6:
 * ```
 * [flag 0x01] [0x00] [impedance, 16-bit big-endian, ohms] … [type 0xD6] [checksum]
 * ```
 * The impedance is 0 when the feet did not touch the electrodes (socks, or stepping off early). The
 * scale repeats this packet until the app acknowledges it, so only the first one counts.
 *
 * The weight is therefore held until the result packet arrives, with a fallback that publishes the
 * weight alone, and body composition is derived from weight and impedance with
 * [StandardImpedanceLib] — the scale itself sends no derived values. Packet type 0xD8 carries
 * records stored on the scale (with the scale's own, unsynchronised clock) and is ignored.
 */
class IcomonAeroHandler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_DATA: UUID = uuid16(0xFFB2)   // notify

    /** Grams of the last published weight; cleared once the scale reports an empty platform. */
    private var lastPublishedGrams: Int? = null

    /** A locked weight waiting for the result packet that carries the impedance. */
    private var pending: PendingWeight? = null
    private var fallbackJob: Job? = null

    private class PendingWeight(val reading: WeightReading, val user: ScaleUser)

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        if (!name.startsWith("aero i")) return null
        if (device.serviceUuids.none { it == SERVICE }) return null

        val caps = setOf(DeviceCapability.LIVE_WEIGHT_STREAM)
        return DeviceSupport(
            displayName = "Urikar Aero I (Fitdays / ICOMON)",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        resetSession()
        setNotifyOn(SERVICE, CHAR_DATA)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        resetSession()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_DATA) return

        parseResultFrame(data)?.let { impedance ->
            // Only the first result packet after a locked weight counts; the scale repeats it.
            pending?.let { publishPending(impedance.takeIf { it > 0 }) }
            return
        }

        val reading = parseWeightFrame(data) ?: return
        if (reading.grams == 0) {
            lastPublishedGrams = null
            return
        }
        if (!reading.stable || reading.grams == lastPublishedGrams || pending != null) return

        logI("Aero I stable weight ${reading.weightKg} kg (${reading.grams} g), waiting for impedance")
        pending = PendingWeight(reading, user)
        fallbackJob = scope.launch {
            delay(RESULT_WAIT_MS)
            logW("Aero I: no result packet within $RESULT_WAIT_MS ms, publishing weight only")
            publishPending(null)
        }
    }

    private fun publishPending(impedanceOhm: Int?) {
        val waiting = pending ?: return
        pending = null
        fallbackJob?.cancel()
        fallbackJob = null
        lastPublishedGrams = waiting.reading.grams

        logI("Aero I publishing ${waiting.reading.weightKg} kg, impedance=${impedanceOhm ?: "none"}")
        publish(buildMeasurement(waiting.user, waiting.reading.weightKg, impedanceOhm))
    }

    private fun resetSession() {
        fallbackJob?.cancel()
        fallbackJob = null
        pending = null
        lastPublishedGrams = null
    }

    /** One decoded 0xD5 weight/state packet. */
    internal data class WeightReading(val grams: Int, val stable: Boolean, val kgPrecision: Int) {
        /** Vendor rounding for kg precision 3 (0.1 kg); other indexes are reported unrounded. */
        val weightKg: Float
            get() = if (kgPrecision == PRECISION_TENTH_KG) ((grams + 50) / 100) / 10f else grams / 1000f
    }

    companion object {
        /** How long a locked weight waits for the result packet; it normally follows within ~3 s. */
        private const val RESULT_WAIT_MS = 8_000L

        /** Impedance range in which the standard body-composition formulas are meaningful. */
        private val IMPEDANCE_RANGE = 1..1499

        private const val TYPE_RESULT = 0xD6
        private const val FRAME_SIZE = 20
        private const val HEADER = 0xAC
        private const val VERSION = 0x27
        private const val TYPE_WEIGHT = 0xD5
        private const val PRECISION_TENTH_KG = 3

        /** Returns the weight packet in [frame], or null for other types and invalid frames. */
        internal fun parseWeightFrame(frame: ByteArray): WeightReading? {
            if (!hasValidEnvelope(frame) || frame.u8(18) != TYPE_WEIGHT) return null

            val word = (frame.u8(2) shl 24) or (frame.u8(3) shl 16) or (frame.u8(4) shl 8) or frame.u8(5)
            return WeightReading(
                grams = word and 0x3FFFF,
                stable = (word ushr 31) == 1,
                kgPrecision = (word ushr 18) and 0x7
            )
        }

        /**
         * Returns the impedance in ohms from a result packet (0 when the scale measured none), or
         * null when [frame] is not a valid result packet.
         */
        internal fun parseResultFrame(frame: ByteArray): Int? {
            if (!hasValidEnvelope(frame) || frame.u8(18) != TYPE_RESULT) return null
            return (frame.u8(4) shl 8) or frame.u8(5)
        }

        /** Builds the measurement; body composition is added when [impedanceOhm] is plausible. */
        internal fun buildMeasurement(user: ScaleUser, weightKg: Float, impedanceOhm: Int?): ScaleMeasurement =
            ScaleMeasurement().apply {
                userId = user.id
                dateTime = Date()
                this[MeasurementType.WEIGHT] = Kg(weightKg)

                if (impedanceOhm == null || impedanceOhm !in IMPEDANCE_RANGE) return@apply
                this[MeasurementType.IMPEDANCE] = Ohm(impedanceOhm.toFloat())
                val lib = StandardImpedanceLib(
                    gender = user.gender,
                    age = user.age,
                    weightKg = weightKg.toDouble(),
                    heightM = user.bodyHeight / 100.0,
                    impedance = impedanceOhm.toDouble(),
                )
                this[MeasurementType.BODY_FAT] = Percent(lib.totalFatPercentage.toFloat())
                this[MeasurementType.WATER] = Percent(lib.totalBodyWaterPercentage.toFloat())
                this[MeasurementType.MUSCLE] = Percent(lib.skeletalMusclePercentage.toFloat())
                this[MeasurementType.BONE] = Kg(lib.boneMassKg.toFloat())
                this[MeasurementType.BMR] = Kcal(lib.basalMetabolicRate.toFloat())
            }

        /** Header, protocol byte and the checksum over bytes 2..18, shared by every packet type. */
        private fun hasValidEnvelope(frame: ByteArray): Boolean {
            if (frame.size != FRAME_SIZE) return false
            if (frame.u8(0) != HEADER || frame.u8(1) != VERSION) return false
            var sum = 0
            for (i in 2..18) sum += frame.u8(i)
            return (sum and 0x1F) == (frame.u8(19) and 0x1F)
        }

        private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    }
}
