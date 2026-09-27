package com.inkslate.core

/**
 * Web links on the page: typed onto text in InkSlate, or already in a PDF.
 */
object Links {

    /**
     * An address as typed made into one a browser opens: "example.com" becomes
     * "https://example.com", an e-mail address a mailto: link. Null when there is nothing to link.
     */
    fun normalise(typed: String?): String? {
        val t = typed?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:""").containsMatchIn(t)) return t
        if (Regex("""^[^@\s/]+@[^@\s/]+\.[^@\s/]+$""").matches(t)) return "mailto:$t"
        return "https://$t"
    }

    /** Whether a link is one a browser or mail program should be handed; not a script or a file. */
    fun safe(link: String): Boolean {
        val scheme = link.substringBefore(':').lowercase()
        return scheme in setOf("http", "https", "mailto", "tel")
    }

    /** A link as a person reads it: without "https://" and a trailing slash. */
    fun shown(link: String): String = link.removePrefix("https://").removePrefix("http://").removePrefix("mailto:").trimEnd('/')
}

/** A link already in a PDF page: where it is on the page (points, top-left origin) and where it goes. */
data class PageLink(val left: Float, val top: Float, val right: Float, val bottom: Float, val uri: String) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}
