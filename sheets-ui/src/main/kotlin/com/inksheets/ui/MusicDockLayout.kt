package com.inksheets.ui

internal enum class MusicDockView(val label: String) { AUTO("Auto"), SHELF("Music shelf"), STUDIO("Studio rail") }
internal enum class StudioRailSide(val label:String) { LEFT("Left"), RIGHT("Right") }
internal fun chooseMusicDock(view: MusicDockView, width: Float, height: Float, pageAspect: Float,
                             shelfHeight: Float, railWidth: Float = 236f): MusicDockView {
    if (width < 560 || height < 260) return MusicDockView.SHELF
    if (view != MusicDockView.AUTO) return view
    val aspect=pageAspect.coerceIn(.25f,4f)
    val shelfPaper=minOf(width,(height-shelfHeight-44).coerceAtLeast(1f)*aspect)
    val railPaper=minOf(width-railWidth,(height-44).coerceAtLeast(1f)*aspect)
    return if (railPaper>shelfPaper*1.08f) MusicDockView.STUDIO else MusicDockView.SHELF
}
