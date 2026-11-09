package com.eollas.sleepmonitor.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eollas.sleepmonitor.data.AppSettings
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.Repository
import com.eollas.sleepmonitor.tracking.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository.get(app)
    val settings = repo.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val nights = repo.dao.nightsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val live = Live.state

    var customWallpaper by mutableStateOf<ImageBitmap?>(null); private set
    var hcAvailable by mutableStateOf(false); private set
    var hcGranted by mutableStateOf<Set<String>>(emptySet()); private set
    var batteryOk by mutableStateOf(true); private set
    var clipsSize by mutableLongStateOf(0L); private set
    var resumeTick by mutableIntStateOf(0); private set
    var openNight by mutableStateOf<Long?>(null)

    private val wallpaperFile get() = File(getApplication<Application>().filesDir, "wallpaper.jpg")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.recoverInterrupted()
            loadWallpaper()
        }
    }

    /** Appelé à chaque retour dans l'app : met à jour les autorisations. */
    fun onResume() {
        resumeTick++
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
        batteryOk = pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
        viewModelScope.launch {
            hcAvailable = repo.health.available
            hcGranted = repo.health.granted()
            clipsSize = withContext(Dispatchers.IO) { repo.clipsSize() }
        }
    }

    fun setRetention(d: Int) = viewModelScope.launch {
        repo.settings.setRetention(d)
        withContext(Dispatchers.IO) { repo.cleanClips(d); clipsSize = repo.clipsSize() }
    }
    fun setAudio(on: Boolean) = viewModelScope.launch { repo.settings.setAudio(on) }
    fun setAutoStop(on: Boolean) = viewModelScope.launch { repo.settings.setAutoStop(on) }
    fun setSensitivity(v: Int) = viewModelScope.launch { repo.settings.setSensitivity(v) }
    fun setWallpaper(id: String) = viewModelScope.launch { repo.settings.setWallpaper(id) }
    fun setDim(v: Float) = viewModelScope.launch { repo.settings.setDim(v) }
    fun setHcWrite(on: Boolean) = viewModelScope.launch { repo.settings.setHcWrite(on) }

    /** Copie la photo choisie dans l'app (privée), réduite à la taille de l'écran. */
    fun setCustomWallpaper(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        runCatching {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 2600) {
                    val s = 2600f / longest
                    dec.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            wallpaperFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            customWallpaper = bmp.asImageBitmap()
            repo.settings.setWallpaper("custom")
        }
    }

    private fun loadWallpaper() {
        val f = wallpaperFile
        if (f.exists()) customWallpaper = BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }

    fun refreshHc() = viewModelScope.launch { hcGranted = repo.health.granted() }

    fun refreshExternal(n: Night) = viewModelScope.launch(Dispatchers.IO) { repo.refreshExternal(n) }

    fun deleteNight(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteNight(id)
        clipsSize = repo.clipsSize()
    }

    fun deleteAllClips() = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteAllClips(); clipsSize = 0
    }

    fun deleteEverything() = viewModelScope.launch(Dispatchers.IO) {
        repo.deleteEverything(); clipsSize = 0
    }
}
