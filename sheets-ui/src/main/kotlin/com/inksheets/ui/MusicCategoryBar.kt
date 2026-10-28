package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private typealias CategoryContent = @Composable () -> Unit
internal class InlineMusicPages {
    internal class Page(val title:String,val close:State<()->Unit>,val body:State<CategoryContent>,
                        val buttons:State<CategoryContent?>,val scroll:Boolean)
    val pages=mutableStateMapOf<Any,Page>()
    val order=mutableStateListOf<Any>()
}
internal val LocalMusicCategoryContent=staticCompositionLocalOf<InlineMusicPages?>{null}

/** Registers a category page in the same bar. Earlier pages stay composed, preserving drafts. */
@Composable internal fun InlineMusicPage(title:String,close:()->Unit,buttons:CategoryContent?,scroll:Boolean=false,content:CategoryContent) {
    val host=LocalMusicCategoryContent.current ?: return
    val id=remember(title){Any()}
    val latestClose=rememberUpdatedState(close)
    val latestBody=rememberUpdatedState(content)
    val latestButtons=rememberUpdatedState(buttons)
    val page=remember(id){InlineMusicPages.Page(title,latestClose,latestBody,latestButtons,scroll)}
    DisposableEffect(host,id) {
        host.pages[id]=page;host.order.add(id)
        onDispose {host.pages.remove(id);host.order.remove(id)}
    }
}

@Composable internal fun MusicCategoryBar(state:SheetsState,position:Long) {
    val category=state.expandedMusicCategory ?: return
    val host=remember(state,category,state.musicCategoryPage){InlineMusicPages()}
    val categories=state.shelf.order.filter{it!=ShelfWidget.SESSION || state.companion.leading || state.companion.following!=null}
    fun back() {
        val top=host.order.lastOrNull()?.let{host.pages[it]}
        when {
            host.order.size>1 -> top?.close?.value?.invoke()
            state.musicCategoryPage!=null -> state.musicCategoryPage=null
            else -> state.expandedMusicCategory=null
        }
    }
    state.platform.readerPanelBackHandler(true,::back)
    val focus=remember(host){FocusRequester()}
    LaunchedEffect(host){focus.requestFocus()}
    Column(Modifier.fillMaxSize().onKeyEvent {
        if(it.type==KeyEventType.KeyDown && it.key==Key.Escape){back();true}else false
    }.focusRequester(focus).focusable().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            IconButton(onClick=::back,modifier=Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,if(state.musicCategoryPage==null)"Compact categories"else"Back to category")
            }
            categories.forEach { item ->
                TextButton(onClick={state.expandMusicCategory(item)},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=2.dp)) {
                    Text(item.label,style=MaterialTheme.typography.labelSmall,color=if(item==category)widgetColor(item)else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                }
            }
        }
        HorizontalDivider(color=widgetColor(category).copy(alpha=.35f))
        if(category==ShelfWidget.DEVICES) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick={state.musicCategoryPage=null}){Text("Connections")}
            TextButton(onClick={state.musicCategoryPage="controllers"}){Text("Pedals & watch")}
            TextButton(onClick={state.remote.remoteOpen=true}){Text("Use as remote")}
        }
        if(category==ShelfWidget.TIMING) Row {
            TextButton(onClick={state.musicCategoryPage=null}){Text("Metronome")}
            TextButton(onClick={state.musicCategoryPage="tuner"}){Text("Tuner")}
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalMusicCategoryContent provides host) {
                val close={state.expandedMusicCategory=null}
                when(category) {
                    ShelfWidget.TIMING -> if(state.musicCategoryPage=="tuner")TunerDialog(state,close)else MetronomeDialog(state,close)
                    ShelfWidget.RECORDING -> state.current?.let{AudioDialog(state,it,onClose=close)}
                    ShelfWidget.SCORE -> if(state.musicCategoryPage=="reading")ReadMusicPanel(state,close)else ScoreToolsPanel(state,close)
                    ShelfWidget.DEVICES -> if(state.musicCategoryPage=="controllers")SheetDialog("Pedals & watch",onDismiss={state.musicCategoryPage=null}) {
                        Column(Modifier.verticalScroll(rememberScrollState())){ControllerSettings(state);WatchSettings(state)}
                    }else CompanionDialog(state,close)
                    ShelfWidget.SESSION -> if(state.musicCategoryPage=="message")SendNoteDialog(state){state.musicCategoryPage=null}else CompanionDialog(state,close)
                }
                host.order.toList().forEachIndexed { index,id -> host.pages[id]?.let { page ->
                    val top=id==host.order.lastOrNull()
                    val accessibility=if(top)Modifier else Modifier.clearAndSetSemantics{}
                    Surface(modifier=Modifier.fillMaxSize().zIndex(index.toFloat()).alpha(if(top)1f else 0f).then(accessibility).clickable{}) {
                        Column(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
                            Text(page.title,style=MaterialTheme.typography.titleSmall,color=widgetColor(category),maxLines=1,
                                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.padding(vertical=5.dp))
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if(page.scroll)Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())){page.body.value()}
                                else page.body.value()
                            }
                            page.buttons.value?.let { buttons -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){buttons()} }
                        }
                    }
                } }
            }
        }
    }
}
