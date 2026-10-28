package com.inksheets.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.CompanionLink

@Composable internal fun SessionWidget(state:SheetsState) {
    val group=state.companion
    Text(if(group.leading)"Leading the group"else"Following ${group.following.orEmpty()}",style=MaterialTheme.typography.labelMedium,color=widgetColor(ShelfWidget.SESSION),maxLines=1)
    Text(if(group.leading)"${group.followers} connected"else group.connectionDetail,style=MaterialTheme.typography.labelSmall,maxLines=2)
    SessionQuickActions(state)
    if(group.leading) Row(Modifier.horizontalScroll(rememberScrollState())) {
        state.presets.forEach {preset->TextButton(onClick={state.sendPreset(preset)},contentPadding=PaddingValues(horizontal=5.dp)) {
            Text(preset.text,style=MaterialTheme.typography.labelSmall,maxLines=1)
        }}
    } else TextButton(onClick={state.companionOpen=true},contentPadding=PaddingValues(0.dp)){Text("Following options",style=MaterialTheme.typography.labelSmall)}
}

/** Active group actions stay outside widget scrolling without changing the saved arrangement. */
@Composable internal fun SessionQuickActions(state:SheetsState, quick:Boolean=false) {
    val group=state.companion
    Column {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
        if(group.leading) {
            DockAction(Icons.Default.Campaign,"Message",if(quick)"Quick group message"else"Message"){state.groupMessageOpen=true}
            DockAction(Icons.Default.Edit,"Marks",if(quick)"Quick share marks"else"Marks",active=group.shareInk){group.shareInk=!group.shareInk}
            DockAction(Icons.Default.Settings,"Group",if(quick)"Quick group settings"else"Group"){state.companionOpen=true}
        } else {
            DockAction(Icons.Default.MyLocation,"Return",if(quick)"Quick return to leader"else"Return to leader"){group.goToLeader()}
            val modeName=when(group.follow) {
                CompanionLink.Follow.AUTO -> "Auto"
                CompanionLink.Follow.SAME_PAGE -> "Same page"
                CompanionLink.Follow.NEXT_PAGE -> "Next page"
                CompanionLink.Follow.SONG_ONLY -> "Song only"
            }
            DockAction(Icons.Default.Sync,modeName,if(quick)"Quick change following mode"else"Change following mode") {
                val modes=CompanionLink.Follow.entries;group.follow=modes[(modes.indexOf(group.follow)+1)%modes.size]
            }
            DockAction(Icons.Default.ExitToApp,"Leave",if(quick)"Quick leave group"else"Leave"){group.stopFollowing()}
        }
    }
    if(quick && group.leading && state.presets.any{it.onStrip}) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        state.presets.filter{it.onStrip}.forEach { preset ->
            TextButton(onClick={state.sendPreset(preset)},contentPadding=PaddingValues(horizontal=6.dp)) {
                Text(preset.text,style=MaterialTheme.typography.labelSmall,maxLines=1)
            }
        }
    }
    }
}
