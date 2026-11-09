package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import java.io.File

internal enum class ShelfWidget(val label: String) {
    PAGES("Pages"), PARTS("Part"), PLAYBACK("Playback"), SCORE("Reading"), ANNOTATION("Annotate"),
    TIMING("Tempo & tuner"), RECORDING("Recording"), DEVICES("Connections"), SESSION("Group")
}

/** Device-local layout preferences. A missing/corrupt preference always leaves a usable shelf. */
internal class MusicShelfPreferences(private val platform: SheetsPlatform) {
    var portraitHeight by mutableFloatStateOf(platform.pref("sheets_dock_portrait_height")?.toFloatOrNull()?.coerceIn(132f,420f) ?: 174f)
        private set
    fun resizePortrait(by:Float) {
        portraitHeight=(portraitHeight+by).coerceIn(132f,420f)
        platform.setPref("sheets_dock_portrait_height",portraitHeight.toInt().toString())
    }
    var landscapeWidth by mutableFloatStateOf(platform.pref("sheets_dock_landscape_width")?.toFloatOrNull()?.coerceIn(220f,480f) ?: 236f)
        private set
    fun resizeLandscape(by:Float) {
        landscapeWidth=(landscapeWidth+by).coerceIn(220f,480f)
        platform.setPref("sheets_dock_landscape_width",landscapeWidth.toInt().toString())
    }
    var railSide by mutableStateOf(StudioRailSide.entries.firstOrNull{it.name==platform.pref("sheets_dock_side")} ?: StudioRailSide.LEFT)
        private set
    fun chooseRailSide(side:StudioRailSide){railSide=side;platform.setPref("sheets_dock_side",side.name)}
    var view by mutableStateOf(MusicDockView.entries.firstOrNull { it.name == platform.pref("sheets_dock_view") } ?: MusicDockView.AUTO)
        private set
    fun chooseView(value: MusicDockView) { view=value;platform.setPref("sheets_dock_view",value.name) }
    var order by mutableStateOf(decode(platform.pref("sheets_shelf_order")))
        private set
    var hidden by mutableStateOf(platform.pref("sheets_shelf_hidden").orEmpty().split(',').mapNotNull { value -> ShelfWidget.entries.firstOrNull { it.name == value } }.toSet())
        private set
    fun move(widget: ShelfWidget, by: Int) {
        val next = order.toMutableList(); val i = next.indexOf(widget); val to = (i + by).coerceIn(0, next.lastIndex)
        if (i == to) return
        next.add(to, next.removeAt(i)); order = next
        platform.setPref("sheets_shelf_order", next.joinToString(",") { it.name })
    }
    fun show(widget: ShelfWidget, on: Boolean) {
        hidden = if (on) hidden - widget else hidden + widget
        platform.setPref("sheets_shelf_hidden", hidden.joinToString(",") { it.name })
    }
    fun reset() { order = ShelfWidget.entries.toList(); hidden = emptySet(); platform.setPref("sheets_shelf_order", null); platform.setPref("sheets_shelf_hidden", null) }
    companion object {
        fun decode(raw: String?): List<ShelfWidget> {
            val saved=raw.orEmpty().split(',').mapNotNull { v -> ShelfWidget.entries.firstOrNull { it.name == v } }
            val migrated=(saved+ShelfWidget.entries).distinct()
            // Pages and Playback did not exist in older layouts. Put the new primary tasks first
            // once, while retaining every deliberate ordering choice for the existing tabs.
            return if(raw!=null && ShelfWidget.PAGES !in saved)
                listOf(ShelfWidget.PAGES,ShelfWidget.PARTS,ShelfWidget.PLAYBACK)+migrated.filterNot {
                    it==ShelfWidget.PAGES || it==ShelfWidget.PARTS || it==ShelfWidget.PLAYBACK
                }
            else migrated
        }
    }
}
