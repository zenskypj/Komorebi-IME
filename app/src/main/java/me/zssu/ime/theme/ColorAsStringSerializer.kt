package me.zssu.ime.theme

import android.graphics.Color
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads/writes ARGB ints as `#RRGGBB` or `#AARRGGBB`, so theme JSON stays hand-editable.
 * Round-trips through the 8-digit form to avoid silently dropping a non-opaque alpha.
 */
object ColorAsStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("me.zssu.ime.theme.Color", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeString(String.format("#%08X", value))
    }

    override fun deserialize(decoder: Decoder): Int {
        val text = decoder.decodeString()
        val hex = text.takeIf { it.startsWith('#') }?.substring(1)
        if (hex != null && hex.length in setOf(6, 8)) {
            val value = hex.toLongOrNull(16)
            if (value != null && value >= 0) return (if (hex.length == 6) value or 0xFF000000 else value).toInt()
        }
        return Color.parseColor(text)
    }
}
