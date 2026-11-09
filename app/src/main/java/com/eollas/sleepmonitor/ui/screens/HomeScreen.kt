package com.eollas.sleepmonitor.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eollas.sleepmonitor.tracking.SleepTrackingService
import com.eollas.sleepmonitor.ui.Fmt
import com.eollas.sleepmonitor.ui.MainViewModel
import com.eollas.sleepmonitor.ui.clickableNoRipple
import com.eollas.sleepmonitor.ui.components.GlassButton
import com.eollas.sleepmonitor.ui.components.LevelBar
import com.eollas.sleepmonitor.ui.components.ScoreRing
import com.eollas.sleepmonitor.ui.components.SoftSwitch
import com.eollas.sleepmonitor.ui.theme.Palette
import kotlinx.coroutines.delay

@SuppressLint("BatteryLife")
@Composable
fun HomeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nights by vm.nights.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val tick = vm.resumeTick

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val micOk = granted(Manifest.permission.RECORD_AUDIO)
    val notifOk = granted(Manifest.permission.POST_NOTIFICATIONS)

    var pendingStart by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onResume()
        if (pendingStart) { pendingStart = false; SleepTrackingService.start(ctx) }
    }
    fun start() {
        val missing = buildList {
            if (settings.audioEnabled && !micOk) add(Manifest.permission.RECORD_AUDIO)
            if (!notifOk) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) SleepTrackingService.start(ctx)
        else { pendingStart = true; permLauncher.launch(missing.toTypedArray()) }
    }
    var confirmStop by remember { mutableStateOf(false) }
    var showTip by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.running) {
        while (live.running) { now = System.currentTimeMillis(); delay(1000) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, top = 24.dp)) {
            Text(Fmt.greeting(), color = Palette.textFaint, fontSize = 26.sp, fontWeight = FontWeight.Light)
            Text(Fmt.day(System.currentTimeMillis()), color = Palette.textDim, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        // Les cartes sont posées en bas de l'écran, comme dans les apps de sommeil
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)
                .padding(top = 190.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
        ) {

            if (!live.running && (!vm.batteryOk || !notifOk || (settings.audioEnabled && !micOk))) {
                Card(title = "Préparer le téléphone") {
                    Hint("Pour que la nuit soit suivie sans interruption sur le Redmagic :")
                    if (settings.audioEnabled && !micOk || !notifOk) {
                        SettingRow("Micro et notifications", "Écoute des bruits et récapitulatif du matin") {
                            GlassButton("Autoriser", {
                                permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                            })
                        }
                    }
                    if (!vm.batteryOk) {
                        SettingRow("Batterie", "Empêcher Android d'arrêter l'app la nuit") {
                            GlassButton("Régler", {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
                                    )
                                }
                            })
                        }
                    }
                }
            }

            if (!live.running) {
                val last = nights.firstOrNull()
                if (last != null) {
                    Card(Modifier, null) {
                        Row(
                            Modifier.fillMaxWidth().padding(0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ScoreRing(last.score, 64.dp, 6.dp)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text("Dernière nuit", color = Palette.textFaint, fontSize = 12.sp)
                                Text(
                                    if (last.totalSleepMin > 0) Fmt.hm(last.totalSleepMin) else "Sommeil non détecté",
                                    color = Palette.text, fontSize = 20.sp,
                                )
                                Text(Fmt.nightTitle(last.startTime), color = Palette.textDim, fontSize = 13.sp)
                            }
                            GlassButton("Voir", { vm.openNight = last.id })
                        }
                    }
                }
            }

            // Carte principale, comme sur la capture : titre, interrupteur, bouton
            Card {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        if (live.running) "Suivi de votre sommeil en cours" else "Prêt à dormir ?",
                        color = Palette.text, fontSize = 26.sp, lineHeight = 32.sp,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier.padding(top = 6.dp).clickableNoRipple { showTip = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Téléphone sur le lit", color = Palette.textFaint, fontSize = 13.sp)
                        Icon(Icons.Outlined.Info, null, tint = Palette.textFaint, modifier = Modifier.padding(start = 4.dp).height(14.dp))
                    }
                }
                if (live.running) {
                    Text("Commencé à ${Fmt.clock(live.start)} · depuis ${Fmt.hm(((now - live.start) / 60_000).toInt())}", color = Palette.textDim, fontSize = 14.sp)
                    Text(
                        when {
                            live.asleepNow && live.asleepSince != null -> "Endormi depuis ${Fmt.clock(live.asleepSince)}"
                            live.asleepSince != null -> "Éveillé (endormissement détecté à ${Fmt.clock(live.asleepSince)})"
                            else -> "Détection de l'endormissement…"
                        },
                        color = Palette.text, fontSize = 15.sp,
                    )
                    if (live.audio) {
                        LevelBar(live.db)
                        Text(
                            "Ronflements ${live.snoreSec / 60} min · Paroles ${live.talk} · Toux ${live.cough} · Bruits ${live.noise}",
                            color = Palette.textFaint, fontSize = 12.sp,
                        )
                    }
                } else {
                    Hint("Lancez le suivi au coucher et posez le téléphone sur le matelas, près de l'oreiller, branché si possible.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ronflements et somniloquie", color = Palette.textDim, fontSize = 15.sp,
                        modifier = Modifier.weight(1f), lineHeight = 20.sp,
                    )
                    SoftSwitch(settings.audioEnabled) { if (!live.running) vm.setAudio(it) }
                    Spacer(Modifier.padding(6.dp))
                    if (live.running) GlassButton("Arrêter le suivi", { confirmStop = true })
                    else GlassButton("Commencer", { start() }, accent = true)
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Arrêter le suivi ?") },
            text = { Text("Le récapitulatif de la nuit sera calculé maintenant.") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; SleepTrackingService.stop(ctx) }) { Text("Arrêter") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Continuer") } },
        )
    }
    if (showTip) {
        AlertDialog(
            onDismissRequest = { showTip = false },
            confirmButton = { TextButton(onClick = { showTip = false }) { Text("OK") } },
            title = { Text("Où poser le téléphone ?") },
            text = {
                Text(
                    "Sur le matelas, à côté de l'oreiller (pas sur la table de nuit) : il sent ainsi vos mouvements. " +
                        "Micro dégagé, idéalement branché sur le chargeur.\n\n" +
                        "Le matin, prenez simplement le téléphone en main : le réveil est détecté et le suivi " +
                        "s'arrête tout seul (réglable), ou touchez « Arrêter le suivi ».",
                )
            },
        )
    }
}

