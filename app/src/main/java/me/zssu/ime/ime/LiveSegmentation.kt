package me.zssu.ime.ime

import me.zssu.ime.mozc.MozcEngine
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request

/** A short-lived session never alters the active composition, manual boundary or learning. */
internal object LiveSegmentation {
    data class Result(val text: String, val readings: List<String>)

    fun analyze(engine: MozcEngine, reading: String): Result? {
        if (reading.codePointCount(0, reading.length) !in 3..80) return null
        val created = engine.eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION)) ?: return null
        if (!created.hasId()) return null
        val id = created.id
        try {
            engine.eval(Input.newBuilder().setType(Input.CommandType.SET_REQUEST).setId(id)
                .setRequest(Request.newBuilder().setMixedConversion(true)))
            val sent = engine.eval(Input.newBuilder().setType(Input.CommandType.SEND_KEY).setId(id)
                .setKey(KeyEvent.newBuilder().setKeyString(reading).setInputStyle(KeyEvent.InputStyle.AS_IS)
                    .setMode(CompositionMode.HIRAGANA).setActivated(true)))
                ?: return null
            if (!sent.hasPreedit()) return null
            val result = engine.eval(Input.newBuilder().setType(Input.CommandType.SEND_KEY).setId(id)
                .setKey(KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.HENKAN))) ?: return null
            if (!result.hasPreedit()) return null
            val segments = result.preedit.segmentList
            if (segments.size < 2) return null
            val readings = segments.map { if (it.hasKey()) it.key else "" }
            if (readings.any { it.isEmpty() } || readings.joinToString("") != reading) return null
            val text = segments.joinToString("") { it.value }
            return Result(text, readings).takeIf { text.isNotBlank() && text != reading }
        } finally {
            engine.eval(Input.newBuilder().setType(Input.CommandType.DELETE_SESSION).setId(id))
        }
    }
}
