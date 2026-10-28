package com.inkslate.core

import kotlinx.serialization.Serializable

/** A reversible viewport in normalized full-page coordinates. Full bounds also record resets. */
@Serializable
data class PageCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val updatedUtc: Long = 0
) {
    companion object {
        /** Deterministic last-writer-wins, including two edits in the same millisecond. */
        fun newest(a: PageCrop?, b: PageCrop?): PageCrop? = listOfNotNull(a, b).maxWithOrNull(
            compareBy<PageCrop>({ it.updatedUtc }, { it.left }, { it.top }, { it.right }, { it.bottom })
        )
    }

    val valid: Boolean get() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right - left >= 0.05f && bottom - top >= 0.05f
    fun box(width: Float, height: Float): Box? = if (valid && width > 0f && height > 0f)
        Box(left * width, top * height, right * width, bottom * height) else null
    fun turned(turns: Int): PageCrop = when (((turns % 4) + 4) % 4) {
        1 -> copy(left = 1f - bottom, top = left, right = 1f - top, bottom = right)
        2 -> copy(left = 1f - right, top = 1f - bottom, right = 1f - left, bottom = 1f - top)
        3 -> copy(left = top, top = 1f - right, right = bottom, bottom = 1f - left)
        else -> this
    }
}
