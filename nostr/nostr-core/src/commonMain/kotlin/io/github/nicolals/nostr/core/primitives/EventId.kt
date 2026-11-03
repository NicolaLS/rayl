package io.github.nicolals.nostr.core.primitives

import kotlin.jvm.JvmInline
import okio.ByteString
import okio.ByteString.Companion.decodeHex

@JvmInline
value class EventId(val bytes: ByteString) {
    init {
        require(bytes.size == 32) { "EventId must be 32 bytes." }
    }

    val hex: String get() = bytes.hex()

    companion object {
        fun fromHex(hex: String) = EventId(hex.decodeHex())

        fun fromBytes(bytes: ByteString) = EventId(bytes)
    }
}
