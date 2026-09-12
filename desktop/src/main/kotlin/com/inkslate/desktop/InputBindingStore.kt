package com.inkslate.desktop

import androidx.compose.runtime.mutableStateOf
import com.inkslate.core.InputBindings

/**
 * The one copy of what each device and button does.
 *
 * One copy because a binding is about the machine rather than about a document: the settings
 * screen and whatever editor happens to be open have to be looking at the same table, or changing
 * a binding would appear to do nothing until the document was closed and opened again.
 */
object InputBindingStore {

    private const val KEY = "input_bindings"

    private val state = mutableStateOf(InputBindings.fromText(DesktopPrefs.get(KEY)))

    var bindings: InputBindings
        get() = state.value
        set(value) {
            state.value = value
            DesktopPrefs.put(KEY, value.toText())
        }
}
