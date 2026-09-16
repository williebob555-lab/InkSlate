package com.inkslate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the shapes tray remembers: each stamp's own settings, which were used lately, which are
 * pinned, and which one was in hand last.
 *
 * In `:core` so the tablet and the laptop remember the same things in the same shape; each
 * platform only decides where the text is kept. Every change returns a new shelf, so a caller
 * holding one in Compose state recomposes on it.
 */
@Serializable
data class StampShelf(
    /** Settings per stamp, by kind name. */
    val options: Map<String, Stamps.StampOptions> = emptyMap(),
    val recent: List<String> = emptyList(),
    val pinned: List<String> = emptyList(),
    val last: String? = null
) {
    fun optionsFor(kind: Stamps.Kind): Stamps.StampOptions =
        options[kind.name]?.let { Stamps.sanitise(kind, it) } ?: kind.defaults

    fun withOptions(kind: Stamps.Kind, o: Stamps.StampOptions): StampShelf =
        copy(options = options + (kind.name to Stamps.sanitise(kind, o)))

    /** Placed: to the front of the recents, and remembered as the one to offer next time. */
    fun used(kind: Stamps.Kind): StampShelf = copy(
        recent = (listOf(kind.name) + recent).distinct().take(RECENT),
        last = kind.name
    )

    fun isPinned(kind: Stamps.Kind) = kind.name in pinned

    fun togglePin(kind: Stamps.Kind): StampShelf =
        copy(pinned = if (isPinned(kind)) pinned - kind.name else pinned + kind.name)

    val lastKind: Stamps.Kind? get() = last?.let(::kindNamed)

    /**
     * What the tray shows after the four shapes: pinned first, in the order they were pinned, then
     * recent ones not already there.
     */
    fun trayKinds(): List<Stamps.Kind> =
        (pinned + recent).distinct().mapNotNull(::kindNamed).filterNot { it.isShape }.take(TRAY)

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val RECENT = 8
        const val TRAY = 10

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): StampShelf =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
                ?: StampShelf()

        private fun kindNamed(name: String): Stamps.Kind? =
            Stamps.Kind.entries.firstOrNull { it.name == name }
    }
}
