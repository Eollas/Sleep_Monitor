package com.eollas.sleepmonitor.ui

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lecture des extraits sonores, un à la fois.
 * Lecture / pause, saut de quelques secondes et accès direct à un moment de l'extrait.
 */
object ClipPlayer {
    /** Extrait chargé (en lecture ou en pause), null si aucun. */
    var activeId by mutableStateOf<Long?>(null); private set
    var playing by mutableStateOf(false); private set
    private var player: MediaPlayer? = null

    /** Rétrocompatibilité : extrait en cours de lecture. */
    val playingId: Long? get() = if (playing) activeId else null

    private fun load(id: Long, path: String): MediaPlayer? {
        stop()
        return runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
            }
        }.onSuccess { player = it; activeId = id }.onFailure { stop() }.getOrNull()
    }

    /** Lecture / pause. Un autre extrait remplace celui en cours. */
    fun toggle(id: Long, path: String) {
        val p = player
        if (activeId == id && p != null) {
            runCatching { if (p.isPlaying) p.pause() else p.start() }
            playing = runCatching { p.isPlaying }.getOrDefault(false)
            return
        }
        load(id, path)?.let { runCatching { it.start() }; playing = true }
    }

    /** Va à la fraction [f] (0..1) de l'extrait et lance la lecture. */
    fun seekTo(id: Long, path: String, f: Float) {
        val p = if (activeId == id) player else load(id, path)
        p ?: return
        runCatching {
            p.seekTo((p.duration * f.coerceIn(0f, 1f)).toLong(), MediaPlayer.SEEK_CLOSEST)
            if (!p.isPlaying) p.start()
        }
        playing = true
    }

    /** Avance (ou recule si négatif) de [ms] millisecondes. */
    fun skip(ms: Int) {
        val p = player ?: return
        runCatching { p.seekTo((p.currentPosition + ms).coerceIn(0, p.duration).toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    /** Position et durée (ms) de l'extrait chargé. */
    fun position(): Pair<Int, Int> = player?.runCatching { currentPosition to duration }?.getOrNull() ?: (0 to 0)

    fun stop() {
        player?.runCatching { stop(); release() }
        player = null
        activeId = null
        playing = false
    }
}
