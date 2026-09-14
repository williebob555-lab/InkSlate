package com.inkslate.core.peer

/**
 * Whether this device is linked, in the form both apps show it.
 *
 * One wording for both, so the tablet and the laptop cannot describe the same link two ways.
 */
data class LinkSummary(
    /** Paired devices with a live connection right now. */
    val linkedTo: List<String>,
    /** Every paired device. */
    val paired: List<String>
) {
    val linked: Boolean get() = linkedTo.isNotEmpty()

    /** Short enough for a title bar: "Linked to Tablet", "Not linked". */
    val label: String
        get() = when {
            linkedTo.size == 1 -> "Linked to ${linkedTo.single()}"
            linkedTo.size > 1 -> "Linked to ${linkedTo.size} devices"
            else -> "Not linked"
        }
}
