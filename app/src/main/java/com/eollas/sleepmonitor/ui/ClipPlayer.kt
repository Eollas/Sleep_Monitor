package com.eollas.sleepmonitor.ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lecture des extraits sonores, un à la fois. */
object ClipPlayer {
    var playingId by mutableStateOf<Long?>(null); private set
    private var player: MediaPlayer? = null

    fun toggle(id: Long, path: String) {
        if (playingId == id) { stop(); return }
        stop()
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playingId = id
        }.onFailure { stop() }
    }

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        playingId = null
    }
}
