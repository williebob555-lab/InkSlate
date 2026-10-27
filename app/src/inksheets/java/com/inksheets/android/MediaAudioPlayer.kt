package com.inksheets.android

import android.content.Context
import com.inksheets.ui.AudioPlayer

/** The shared player API, backed by an Android focus-aware, screen-off recording session. */
class MediaAudioPlayer(context: Context) : AudioPlayer by RecordingAudioSession(context.applicationContext)
