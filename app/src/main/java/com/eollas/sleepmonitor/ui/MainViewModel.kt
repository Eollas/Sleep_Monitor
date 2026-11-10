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
import com.eollas.sleepmonitor.update.Updater
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
    var update by mutableStateOf(UpdateUi()); private set

    private val wallpaperFile get() = File(getApplication<Application>().filesDir, "wallpaper.jpg")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.recoverInterrupted()
            loadWallpaper()
        }
        checkUpdate()
    }

    /** Vérifie sur GitHub s'il existe une version plus récente (lecture seule). */
    fun checkUpdate() = viewModelScope.launch {
        update = update.copy(checking = true, error = null)
        val r = Updater.latest()
        update = when {
            r == null -> update.copy(checking = false, checked = true, release = null, error = "Impossible de joindre GitHub")
            r.versionCode > Updater.currentVersion -> update.copy(checking = false, checked = true, release = r)
            else -> update.copy(checking = false, checked = true, release = null)
        }
    }

    /** Télécharge puis installe la nouvelle version (les données sont conservées). */
    fun installUpdate() {
        val r = update.release ?: return
        val ctx = getApplication<Application>()
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // Android demande d'autoriser l'app à installer des mises à jour (une seule fois)
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        viewModelScope.launch {
            update = update.copy(progress = 0f, error = null)
            runCatching {
                val f = Updater.download(ctx, r) { p -> update = update.copy(progress = p) }
                withContext(Dispatchers.IO) { Updater.install(ctx, f) }
            }.onFailure { update = update.copy(progress = null, error = it.message ?: "Échec du téléchargement") }
                .onSuccess { update = update.copy(progress = null) }
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

data class UpdateUi(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val release: Updater.Release? = null,
    val progress: Float? = null,
    val error: String? = null,
)
