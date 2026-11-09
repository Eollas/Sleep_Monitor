package com.eollas.sleepmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.eollas.sleepmonitor.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SleepApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "Suivi du sommeil", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Affichée pendant que la nuit est suivie"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECAP, "Récapitulatif du matin", NotificationManager.IMPORTANCE_DEFAULT),
        )
        scope.launch {
            val repo = Repository.get(this@SleepApp)
            repo.cleanClips(repo.settings.current().retentionDays)
        }
    }

    companion object {
        const val CH_TRACKING = "tracking"
        const val CH_RECAP = "recap"
    }
}
