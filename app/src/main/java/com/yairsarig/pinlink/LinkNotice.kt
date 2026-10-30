package com.yairsarig.pinlink

import com.yairsarig.pinlink.ble.LinkState

/** What the persistent notification says. Pure, so the wording and the backoff are unit-tested. */
data class LinkNotice(val text: String, val paused: Boolean = false) {
    companion object {
        fun of(paused: Boolean, bluetoothOn: Boolean, link: LinkState, nextTryInMs: Long?): LinkNotice = when {
            paused -> LinkNotice("Paused · tap to resume", paused = true)
            !bluetoothOn -> LinkNotice("Bluetooth is off")
            link == LinkState.READY -> LinkNotice("Connected to pin")
            link == LinkState.CONNECTING -> LinkNotice("Connecting to pin…")
            nextTryInMs != null -> LinkNotice("Looking for pin (next try in ${span(nextTryInMs)})")
            else -> LinkNotice("Looking for pin…")
        }

        /** Wait before try number [attempt] (0-based); stays at the last delay from then on. */
        fun retryDelayMs(attempt: Int): Long =
            PinConfig.RECONNECT_DELAYS_MS[attempt.coerceIn(0, PinConfig.RECONNECT_DELAYS_MS.lastIndex)]

        private fun span(ms: Long) = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"
    }
}
