package com.eollas.sleepmonitor.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

data class AppSettings(
    val retentionDays: Int = 14,      // 0 = toujours
    val audioEnabled: Boolean = true,
    val autoStop: Boolean = true,
    val sensitivity: Int = 1,         // mouvements : 0 faible, 1 normale, 2 élevée
    val soundSensitivity: Float = 0.5f, // sons : 0 = moins sensible … 1 = plus sensible
    val wallpaper: String = "nuit",   // nuit, crepuscule, ocean, aurore, custom
    val dim: Float = 0.35f,
    val hcWrite: Boolean = true,
)

class SettingsRepo(private val ctx: Context) {
    private object K {
        val retention = intPreferencesKey("retention")
        val audio = booleanPreferencesKey("audio")
        val autoStop = booleanPreferencesKey("autoStop")
        val sens = intPreferencesKey("sens")
        val soundSens = floatPreferencesKey("soundSens")
        val wallpaper = stringPreferencesKey("wallpaper")
        val dim = floatPreferencesKey("dim")
        val hcWrite = booleanPreferencesKey("hcWrite")
    }

    val flow: Flow<AppSettings> = ctx.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            retentionDays = p[K.retention] ?: d.retentionDays,
            audioEnabled = p[K.audio] ?: d.audioEnabled,
            autoStop = p[K.autoStop] ?: d.autoStop,
            sensitivity = p[K.sens] ?: d.sensitivity,
            // Avant le curseur, un seul réglage (faible / normale / élevée) servait aussi aux sons
            soundSensitivity = p[K.soundSens] ?: when (p[K.sens]) { 0 -> 0.25f; 2 -> 0.75f; else -> d.soundSensitivity },
            wallpaper = p[K.wallpaper] ?: d.wallpaper,
            dim = p[K.dim] ?: d.dim,
            hcWrite = p[K.hcWrite] ?: d.hcWrite,
        )
    }

    suspend fun current() = flow.first()

    private suspend fun <T> set(key: Preferences.Key<T>, v: T) { ctx.store.edit { it[key] = v } }
    suspend fun setRetention(days: Int) = set(K.retention, days)
    suspend fun setAudio(on: Boolean) = set(K.audio, on)
    suspend fun setAutoStop(on: Boolean) = set(K.autoStop, on)
    suspend fun setSensitivity(v: Int) = set(K.sens, v)
    suspend fun setSoundSensitivity(v: Float) = set(K.soundSens, v.coerceIn(0f, 1f))
    suspend fun setWallpaper(v: String) = set(K.wallpaper, v)
    suspend fun setDim(v: Float) = set(K.dim, v)
    suspend fun setHcWrite(on: Boolean) = set(K.hcWrite, on)
}
