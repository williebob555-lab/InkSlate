package com.inkslate.library

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction

/**
 * Enter does what the dialog's button does - Make, Rename - so a name can be typed and done
 * without reaching for the mouse. Covers a hardware keyboard; [enterKeyboard] covers the
 * on-screen one.
 */
fun Modifier.onEnter(enabled: Boolean = true, action: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
        if (enabled) action()
        true
    } else false
}

/** The on-screen keyboard shows Done, and Done does the same as Enter. */
val DoneKey = KeyboardOptions(imeAction = ImeAction.Done)

fun doneAction(enabled: Boolean = true, action: () -> Unit) = KeyboardActions(onDone = { if (enabled) action() })
