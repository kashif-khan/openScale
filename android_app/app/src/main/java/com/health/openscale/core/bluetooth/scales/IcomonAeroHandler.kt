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
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
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
 * Only weight is decoded. The impedance (body-composition) packets use other types that have not
 * been captured yet, so no body-composition values are published.
 */
class IcomonAeroHandler : ScaleDeviceHandler() {

    private val SERVICE: UUID = uuid16(0xFFB0)
    private val CHAR_DATA: UUID = uuid16(0xFFB2)   // notify

    /** Grams of the last published weight; cleared once the scale reports an empty platform. */
    private var lastPublishedGrams: Int? = null

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
        lastPublishedGrams = null
        setNotifyOn(SERVICE, CHAR_DATA)
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        lastPublishedGrams = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_DATA) return
        val reading = parseWeightFrame(data) ?: return

        if (reading.grams == 0) {
            lastPublishedGrams = null
            return
        }
        if (!reading.stable || reading.grams == lastPublishedGrams) return
        lastPublishedGrams = reading.grams

        val weightKg = reading.weightKg
        logI("Aero I stable weight $weightKg kg (${reading.grams} g)")
        publish(
            ScaleMeasurement().apply {
                userId = user.id
                dateTime = Date()
                this[MeasurementType.WEIGHT] = Kg(weightKg)
            }
        )
    }

    /** One decoded 0xD5 weight/state packet. */
    internal data class WeightReading(val grams: Int, val stable: Boolean, val kgPrecision: Int) {
        /** Vendor rounding for kg precision 3 (0.1 kg); other indexes are reported unrounded. */
        val weightKg: Float
            get() = if (kgPrecision == PRECISION_TENTH_KG) ((grams + 50) / 100) / 10f else grams / 1000f
    }

    companion object {
        private const val FRAME_SIZE = 20
        private const val HEADER = 0xAC
        private const val VERSION = 0x27
        private const val TYPE_WEIGHT = 0xD5
        private const val PRECISION_TENTH_KG = 3

        /** Returns the weight packet in [frame], or null for other types and invalid frames. */
        internal fun parseWeightFrame(frame: ByteArray): WeightReading? {
            if (frame.size != FRAME_SIZE) return null
            if (frame.u8(0) != HEADER || frame.u8(1) != VERSION) return null
            if (frame.u8(18) != TYPE_WEIGHT) return null

            var sum = 0
            for (i in 2..18) sum += frame.u8(i)
            if ((sum and 0x1F) != (frame.u8(19) and 0x1F)) return null

            val word = (frame.u8(2) shl 24) or (frame.u8(3) shl 16) or (frame.u8(4) shl 8) or frame.u8(5)
            return WeightReading(
                grams = word and 0x3FFFF,
                stable = (word ushr 31) == 1,
                kgPrecision = (word ushr 18) and 0x7
            )
        }

        private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    }
}
