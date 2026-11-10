package com.eollas.sleepmonitor.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eollas.sleepmonitor.MainActivity
import com.eollas.sleepmonitor.R
import com.eollas.sleepmonitor.SleepApp
import com.eollas.sleepmonitor.analysis.SleepAnalyzer
import com.eollas.sleepmonitor.data.Epoch
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.Repository
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Service qui tourne toute la nuit (notification visible) : mouvements + micro. */
class SleepTrackingService : LifecycleService() {
    private lateinit var repo: Repository
    private var motion: MotionMonitor? = null
    private var audio: AudioMonitor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loop: Job? = null
    private var nightId = 0L
    private var startTime = 0L
    private var sensitivity = 1
    private var autoStop = true
    private var finishing = false

    private val times = ArrayList<Long>()
    private val acts = ArrayList<Float>()
    private val screens = ArrayList<Boolean>()
    private val liveEvents = java.util.Collections.synchronizedList(ArrayList<Triple<Long, Long, Int>>())

    // Écran allumé pendant la tranche en cours = la personne utilise son téléphone
    @Volatile private var screenUsed = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON) screenUsed = true
        }
    }
    private var restGravity: FloatArray? = null
    private var tiltSince: Long? = null

    override fun onCreate() {
        super.onCreate()
        repo = Repository.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> if (!Live.state.value.running) begin()
            ACTION_STOP -> finish(auto = false)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        startTime = System.currentTimeMillis()
        // Le service doit passer au premier plan immédiatement
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
            (if (micOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(NOTIF_ID, notification("Suivi en cours", "Bonne nuit !"), type)
        Live.state.value = LiveState(running = true, start = startTime)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepMonitor:night")
            .apply { acquire(16 * 3_600_000L) }

        lifecycleScope.launch {
            val s = repo.settings.current()
            sensitivity = s.sensitivity
            autoStop = s.autoStop
            val withAudio = s.audioEnabled && micOk
            nightId = withContext(Dispatchers.IO) {
                repo.dao.insertNight(Night(startTime = startTime, audioEnabled = withAudio))
            }
            Live.state.update { it.copy(nightId = nightId, audio = withAudio) }
            screenUsed = getSystemService(PowerManager::class.java).isInteractive
            registerReceiver(screenReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON))
            motion = MotionMonitor(this@SleepTrackingService).also { it.start() }
            if (withAudio) {
                audio = AudioMonitor(
                    this@SleepTrackingService, nightId, sensitivity,
                    onEvent = ::saveEvent,
                    onLevel = { db -> Live.state.update { it.copy(db = db) } },
                ).also { it.start() }
            }
            loop = launch { epochLoop() }
        }
    }

    private suspend fun epochLoop() {
        var next = System.currentTimeMillis() + SleepAnalyzer.EPOCH_MS
        while (lifecycleScope.isActive && !finishing) {
            delay((next - System.currentTimeMillis()).coerceAtLeast(0))
            val t = next - SleepAnalyzer.EPOCH_MS
            next += SleepAnalyzer.EPOCH_MS
            val m = motion ?: continue
            val act = m.takeEpoch()
            val (db, maxDb) = audio?.takeEpoch() ?: (0f to 0f)
            val pm = getSystemService(PowerManager::class.java)
            val screen = screenUsed || pm.isInteractive
            screenUsed = pm.isInteractive
            withContext(Dispatchers.IO) { repo.dao.insertEpoch(Epoch(nightId, t, act, db, maxDb, screenOn = screen)) }
            times += t; acts += act; screens += screen

            val res = SleepAnalyzer.analyze(
                times.toLongArray(), acts.toFloatArray(), startTime, t + SleepAnalyzer.EPOCH_MS, sensitivity, liveFlags(),
            )
            val lastStage = res.stages.lastOrNull() ?: 0
            val asleep = res.onset != null && res.wake != null && res.wake >= t && lastStage != 1
            Live.state.update { it.copy(asleepSince = res.onset, asleepNow = asleep) }
            updateNotification(res, asleep)

            if (autoStop && pickedUp(m, res)) {
                finish(auto = true)
                return
            }
        }
    }

    /** Indices pour l'analyse en direct (écran, paroles / bruits, ronflements). */
    private fun liveFlags(): SleepAnalyzer.Flags {
        val n = times.size
        val busy = BooleanArray(n)
        val snore = BooleanArray(n)
        val evs = synchronized(liveEvents) { liveEvents.toList() }
        for ((s, e, cat) in evs) {
            val from = ((s - startTime) / SleepAnalyzer.EPOCH_MS).toInt().coerceAtLeast(0)
            val to = ((e - startTime) / SleepAnalyzer.EPOCH_MS).toInt().coerceAtMost(n - 1)
            for (i in from..to) {
                when (SoundCategory.of(cat)) {
                    SoundCategory.SPEECH, SoundCategory.NOISE -> busy[i] = true
                    SoundCategory.SNORING, SoundCategory.APNEA -> snore[i] = true
                    else -> {}
                }
            }
        }
        return SleepAnalyzer.Flags(BooleanArray(n) { screens[it] }, busy, snore)
    }

    /**
     * Réveil détecté : téléphone pris en main (orientation changée + mouvements)
     * pendant 3 minutes, après au moins 3 heures de sommeil.
     */
    private fun pickedUp(m: MotionMonitor, res: SleepAnalyzer.Result): Boolean {
        val n = acts.size
        if (n >= 20) {
            var calm = true
            for (i in n - 20 until n) if (acts[i] > res.threshold) { calm = false; break }
            if (calm) restGravity = m.gravity()
        }
        val rest = restGravity ?: return false
        val tilted = MotionMonitor.angle(rest, m.gravity()) > 35f
        val now = System.currentTimeMillis()
        if (!tilted) { tiltSince = null; return false }
        val since = tiltSince ?: now.also { tiltSince = it }
        if (now - since < 3 * 60_000L || res.sleepMin < 180) return false
        var moving = 0
        for (i in (n - 6).coerceAtLeast(0) until n) if (acts[i] > res.threshold) moving++
        return moving >= 4
    }

    private fun saveEvent(ev: SoundEvent, pcm: ShortArray?) {
        liveEvents += Triple(ev.start, ev.end, ev.category)
        Live.state.update {
            when (SoundCategory.of(ev.category)) {
                SoundCategory.SNORING -> it.copy(snoreSec = it.snoreSec + ((ev.end - ev.start) / 1000).toInt(), lastEvent = ev.label)
                SoundCategory.SPEECH -> it.copy(talk = it.talk + 1, lastEvent = ev.label)
                SoundCategory.COUGH -> it.copy(cough = it.cough + 1, lastEvent = ev.label)
                SoundCategory.NOISE -> it.copy(noise = it.noise + 1, lastEvent = ev.label)
                SoundCategory.APNEA -> it.copy(apnea = it.apnea + 1, lastEvent = ev.label)
            }
        }
        (application as SleepApp).scope.launch {
            var path: String? = null
            if (pcm != null) {
                val f = repo.clipFile(ev.nightId, ev.start)
                runCatching { AacEncoder.encode(pcm, AudioMonitor.SR, f) }
                    .onSuccess { path = f.absolutePath }
                    .onFailure { f.delete() }
            }
            repo.dao.insertEvent(ev.copy(clipPath = path))
        }
    }

    private fun finish(auto: Boolean) {
        if (finishing) return
        finishing = true
        val end = System.currentTimeMillis()
        val id = nightId
        runCatching { unregisterReceiver(screenReceiver) }
        motion?.stop(); motion = null
        val app = application as SleepApp
        app.scope.launch {
            audio?.stop(); audio = null
            // Laisse le temps aux derniers extraits d'être enregistrés
            delay(1500)
            val night = if (id != 0L) repo.finish(id, end) else null
            Live.state.value = LiveState()
            if (night != null) postRecap(night, auto)
            withContext(Dispatchers.Main) {
                wakeLock?.let { if (it.isHeld) it.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun postRecap(n: Night, auto: Boolean) {
        val title = if (n.totalSleepMin > 0) "Votre nuit : ${hm(n.totalSleepMin)} · score ${n.score}"
        else "Nuit terminée"
        val text = buildString {
            if (auto) append("Réveil détecté. ")
            if (n.sleepOnset != null) append("Endormi à ${clock(n.sleepOnset)}, réveil à ${clock(n.wakeTime ?: n.endTime ?: 0)}. ")
            if (n.snoreMin > 0) append("Ronflements : ${n.snoreMin} min. ")
            if (n.apneaCount > 0) append("Pauses respiratoires possibles : ${n.apneaCount}. ")
            append("Touchez pour voir le récapitulatif.")
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_NIGHT, n.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(this, n.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, SleepApp.CH_RECAP)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(RECAP_ID, notif) }
    }

    private fun updateNotification(res: SleepAnalyzer.Result, asleep: Boolean) {
        val text = when {
            asleep && res.onset != null -> "Endormi depuis ${clock(res.onset)}"
            res.onset != null -> "Éveillé · sommeil : ${hm(res.sleepMin)}"
            else -> "En attente de l'endormissement"
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification("Suivi du sommeil en cours", text))
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SleepTrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, SleepApp.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Je suis réveillé", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        motion?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (!finishing && nightId != 0L) {
            // Arrêt imprévu : on calcule quand même le récapitulatif
            val id = nightId
            val a = audio
            (application as SleepApp).scope.launch {
                a?.stop()
                repo.finish(id, System.currentTimeMillis(), interrupted = true)
                Live.state.value = LiveState()
            }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        private const val NOTIF_ID = 1
        private const val RECAP_ID = 2

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, SleepTrackingService::class.java).setAction(ACTION_START),
        )
        fun stop(ctx: Context) = ctx.startService(
            Intent(ctx, SleepTrackingService::class.java).setAction(ACTION_STOP),
        )

        fun hm(min: Int) = "%d h %02d".format(min / 60, min % 60)
        fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
    }
}
