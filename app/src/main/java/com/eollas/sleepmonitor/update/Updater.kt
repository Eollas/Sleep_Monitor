package com.eollas.sleepmonitor.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import com.eollas.sleepmonitor.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour depuis GitHub. L'app ne fait que LIRE la page publique des versions
 * et télécharger l'APK : aucune donnée personnelle n'est envoyée.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Eollas/Sleep_Monitor/releases/latest"
    const val ACTION_STATUS = "com.eollas.sleepmonitor.UPDATE_STATUS"

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val size: Long)

    val currentVersion: Int get() = BuildConfig.VERSION_CODE

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SleepMonitor-app")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** Dernière version publiée, ou null si introuvable (pas de réseau…). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open(API)
            try {
                if (c.responseCode != 200) return@runCatching null
                val json = JSONObject(c.inputStream.bufferedReader().readText())
                val name = json.optString("name")
                val code = Regex("""1\.0\.(\d+)""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@runCatching null
                val assets = json.optJSONArray("assets") ?: return@runCatching null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        return@runCatching Release(code, "1.0.$code", a.getString("browser_download_url"), a.optLong("size"))
                    }
                }
                null
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** Télécharge l'APK dans le cache privé de l'app. */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "update.apk")
        val c = open(r.apkUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            check(c.responseCode == 200) { "Téléchargement impossible (${c.responseCode})" }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        f
    }

    /**
     * Lance l'installation par-dessus l'app actuelle : Android vérifie que la nouvelle
     * version est signée avec la même clé, et les données sont conservées.
     */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_STATUS)
            val status = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }
}

/** Reçoit le résultat de l'installation (et affiche la confirmation d'Android si besoin). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "Mise à jour non installée. $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
