package com.eollas.sleepmonitor.tracking

import kotlinx.coroutines.flow.MutableStateFlow

/** État du suivi en cours, affiché en direct dans l'application. */
data class LiveState(
    val running: Boolean = false,
    val nightId: Long = 0,
    val start: Long = 0,
    val asleepSince: Long? = null,
    val asleepNow: Boolean = false,
    val db: Float = 0f,
    val audio: Boolean = false,
    val snoreSec: Int = 0,
    val talk: Int = 0,
    val cough: Int = 0,
    val noise: Int = 0,
    val lastEvent: String? = null,
)

object Live {
    val state = MutableStateFlow(LiveState())
}
