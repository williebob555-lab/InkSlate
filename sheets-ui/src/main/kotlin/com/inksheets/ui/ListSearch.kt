package com.inksheets.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instrument
import com.inksheets.core.InstrumentReader

/** Lists longer than this get a search box. */
internal const val SEARCH_ABOVE = 10

/**
 * A search box over a list - only when the list is long enough to need one ([count] over
 * [SEARCH_ABOVE]), or while something is typed in it.
 */
@Composable
internal fun ListSearch(count: Int, query: String, onQuery: (String) -> Unit, hint: String = "Search", modifier: Modifier = Modifier) {
    if (count <= SEARCH_ABOVE && query.isEmpty()) return
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(hint) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear") }
        },
        modifier = modifier.fillMaxWidth().widthIn(min = 200.dp).padding(vertical = 4.dp)
    )
}

/**
 * Whether [texts] together have every word of [query] in them, in any order - ignoring case,
 * accents ("dvorak" finds Dvořák) and which apostrophe was typed.
 */
internal fun matches(query: String, vararg texts: String?): Boolean {
    val words = fold(query).split(Regex("""\s+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = fold(texts.filterNotNull().joinToString(" "))
    return words.all { it in hay }
}

/** Text as search compares it: lower case, accents off, one kind of apostrophe. */
internal fun fold(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        .replace("’", "").replace("‘", "").replace("`", "").replace("'", "").lowercase().trim()

/** An instrument found by its name, a name it is printed as, or a short form: "tpt", "euph". */
internal fun matches(query: String, instrument: Instrument): Boolean {
    if (query.isBlank()) return true
    if (matches(query, instrument.name, *instrument.names.toTypedArray())) return true
    return InstrumentReader.read(query)?.instrument?.id == instrument.id
}
