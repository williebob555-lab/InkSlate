package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.ConnectionStatus
import kotlinx.coroutines.delay

/** The current rehearsal role first; pairing and connection administration are separate pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun TogetherCategory(state:SheetsState,onClose:()->Unit) {
    val group=state.companion
    var followChoice by remember {mutableStateOf(group.following!=null)}
    var details by remember {mutableStateOf<String?>(null)}
    var problem by remember {mutableStateOf<String?>(null)}
    var now by remember {mutableStateOf(System.currentTimeMillis())}
    LaunchedEffect(Unit) {while(true){delay(1000);now=System.currentTimeMillis()}}
    val active=group.leading || group.following!=null
    fun followLast() {
        group.lastLeader?.let { leader -> group.followLeader(leader) {ok->
            problem=if(ok)null else "Still trying ${leader.name}. Open Connection options to check the link."
        } }
    }
    SheetDialog("Play together",onDismiss=onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(!active) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                FilterChip(selected=!followChoice,onClick={followChoice=false},label={Text("Lead")},
                    leadingIcon={Icon(Icons.Default.Campaign,null,Modifier.size(16.dp))})
                FilterChip(selected=followChoice,onClick={followChoice=true},label={Text("Follow")},
                    leadingIcon={Icon(Icons.Default.Groups,null,Modifier.size(16.dp))})
            }
            when {
                group.leading -> {
                    Text("Leading · ${group.followers} connected",style=MaterialTheme.typography.titleSmall,color=widgetColor(ShelfWidget.DEVICES))
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick={state.groupMessageOpen=true}) {Icon(Icons.Default.Campaign,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Message")}
                        TextButton(onClick={group.shareInk=!group.shareInk}) {Icon(if(group.shareInk)Icons.Default.Check else Icons.Default.Edit,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(if(group.shareInk)"Sharing marks"else"Share marks")}
                        TextButton(onClick={details="group"}) {Text("Invite players")}
                    }
                    state.presets.forEach {preset->
                        TextButton(onClick={state.sendPreset(preset)},contentPadding=PaddingValues(horizontal=4.dp)) {
                            Text(preset.text,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                group.following!=null -> {
                    Text("Following ${group.following}",style=MaterialTheme.typography.titleSmall,color=widgetColor(ShelfWidget.DEVICES))
                    Text(group.connectionDetail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick={group.goToLeader()},enabled=group.offLeader) {Icon(Icons.Default.MyLocation,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Return to leader")}
                        TextButton(onClick={details="group"}) {Text("Following options")}
                    }
                }
                followChoice -> {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={details="group"}) {Icon(Icons.Default.Search,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Find leader")}
                        group.lastLeader?.let { last -> TextButton(onClick=::followLast) {Text("Rejoin ${last.name}",maxLines=1,overflow=TextOverflow.Ellipsis)} }
                    }
                }
                else -> {
                    Button(onClick={problem=if(group.lead())null else "Could not start. Check Connection options or choose Bluetooth."}) {
                        Icon(Icons.Default.Campaign,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Start leading")
                    }
                }
            }
            val peers=group.connectedDevices+state.remote.connectedDevices
            peers.forEach {peer->
                Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    val good=peer.status==ConnectionStatus.CONNECTED
                    Icon(if(peer.transport.contains("Bluetooth"))Icons.Default.Bluetooth else Icons.Default.Devices,null,Modifier.size(18.dp),
                        tint=if(good)widgetColor(ShelfWidget.DEVICES)else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(peer.name,style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${peer.transport} · ${peer.status.name.lowercase().replaceFirstChar{it.uppercase()}} · heartbeat ${((now-peer.lastHeard).coerceAtLeast(0)/1000)}s ago",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            problem?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={details="group"}) {Text("Connection options")}
                TextButton(onClick={details="remote"}) {Icon(Icons.Default.SettingsRemote,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text("Remotes")}
                if(group.leading) TextButton(onClick=group::stopLeading) {Text("Stop leading")}
                if(group.following!=null) TextButton(onClick=group::stopFollowing) {Text("Leave group")}
            }
        }
    }
    // Registered after the landing page so Back returns to it, preserving the chosen role.
    when(details) {
        "group" -> CompanionDialog(state){details=null}
        "remote" -> SheetDialog("Connect a remote",onDismiss={details=null}) {
            Column(Modifier.verticalScroll(rememberScrollState())) {HostSection(state)}
        }
    }
}
