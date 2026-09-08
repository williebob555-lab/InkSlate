package com.inkslate.data

/**
 * The sidecar format lives in `:core`, shared byte-for-byte with the desktop app.
 * This alias keeps the existing `com.inkslate.data.InkDocument` references working.
 */
typealias InkDocument = com.inkslate.core.InkDocument

typealias InkPageSize = com.inkslate.core.InkDocument.PageSize
typealias InkBookmark = com.inkslate.core.InkDocument.Bookmark
typealias InkSourceRef = com.inkslate.core.InkDocument.SourceRef
