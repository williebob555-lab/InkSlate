package com.inksheets.ui

import kotlin.math.abs
import kotlin.math.tanh

/** Leave normal levels alone and smoothly compress shared-output peaks into speaker headroom. */
internal fun smoothSoundMix(value: Float): Float {
    val level=abs(value)
    if(level<=.9f)return value
    val limited=.9f+.1f*tanh((level-.9f)/.1f)
    return if(value<0f)-limited else limited
}
