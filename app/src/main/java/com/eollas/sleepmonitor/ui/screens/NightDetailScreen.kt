package com.eollas.sleepmonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eollas.sleepmonitor.analysis.SleepAnalyzer
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import com.eollas.sleepmonitor.ui.ClipPlayer
import com.eollas.sleepmonitor.ui.Fmt
import com.eollas.sleepmonitor.ui.MainViewModel
import com.eollas.sleepmonitor.ui.components.ActivityChart
import com.eollas.sleepmonitor.ui.components.Glass
import com.eollas.sleepmonitor.ui.components.GlassButton
import com.eollas.sleepmonitor.ui.components.GlassChip
import com.eollas.sleepmonitor.ui.components.Hypnogram
import com.eollas.sleepmonitor.ui.components.ScoreRing
import com.eollas.sleepmonitor.ui.theme.Palette
import java.io.File

@Composable
fun NightDetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val night by remember(id) { vm.repo.dao.nightFlow(id) }.collectAsState(null)
    val epochs by remember(id) { vm.repo.dao.epochsFlow(id) }.collectAsState(emptyList())
    val events by remember(id) { vm.repo.dao.eventsFlow(id) }.collectAsState(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    DisposableEffect(id) { onDispose { ClipPlayer.stop() } }
    // Va chercher les données Mi Fitness (elles arrivent parfois après le réveil)
    LaunchedEffect(id, night?.finished) { night?.let { if (it.finished) vm.refreshExternal(it) } }

    val n = night
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glass(Modifier.size(46.dp), shape = CircleShape, onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Retour", tint = Palette.text, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                if (n != null) Fmt.nightTitle(n.startTime) else "",
                color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Medium,
            )
        }
        if (n == null) return@Column

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(n.score, 110.dp, 10.dp)
                Column(Modifier.padding(start = 20.dp)) {
                    Text(SleepAnalyzer.verdict(n.score), color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Light)
                    Text(
                        if (n.totalSleepMin > 0) "${Fmt.hm(n.totalSleepMin)} de sommeil" else "Pas assez de calme pour détecter le sommeil",
                        color = Palette.textDim, fontSize = 14.sp,
                    )
                    if (n.interrupted) Text("Suivi interrompu avant la fin", color = Palette.warn, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Coucher", Fmt.clock(n.startTime), Modifier.weight(1f))
                Stat("Endormi", Fmt.clock(n.sleepOnset), Modifier.weight(1f))
                Stat("Réveil", Fmt.clock(n.wakeTime ?: n.endTime), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Endormissement", Fmt.hm(n.latencyMin), Modifier.weight(1f))
                Stat("Efficacité", "${n.efficiency} %", Modifier.weight(1f))
                Stat("Réveils", "${n.awakenings}", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Profond", Fmt.hm(n.deepMin), Modifier.weight(1f))
                Stat("Léger", Fmt.hm(n.lightMin), Modifier.weight(1f))
                Stat("Éveillé", Fmt.hm(n.awakeMin), Modifier.weight(1f))
            }
        }

        if (epochs.isNotEmpty()) {
            Card(title = "Phases de sommeil") {
                Hypnogram(n, epochs)
                Hint("Estimation à partir des mouvements. Les montres mesurent aussi le cœur : plus précis.")
            }
            Card(title = "Mouvements et sons") {
                ActivityChart(n, epochs, events)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Legend(Palette.accent, "Mouvements")
                    if (n.audioEnabled) Legend(Palette.sound, "Niveau sonore")
                }
            }
        }

        ExternalCard(n)

        if (n.audioEnabled) SoundsCard(n, events, showAll) { showAll = !showAll }

        GlassButton("Supprimer cette nuit", { confirmDelete = true }, Modifier.fillMaxWidth())
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette nuit ?") },
            text = { Text("Les statistiques et les extraits sonores de cette nuit seront effacés.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteNight(id); onBack() }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Text(label, color = Palette.textFaint, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ExternalCard(n: Night) {
    if (n.extSource == null && n.hrAvg == null) return
    Card(title = n.extSource?.let { "Selon $it" } ?: "Rythme cardiaque") {
        if (n.extSleepMin != null) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Sommeil", Fmt.hm(n.extSleepMin), Modifier.weight(1f))
                Stat("Horaires", "${Fmt.clock(n.extStart)}–${Fmt.clock(n.extEnd)}", Modifier.weight(1.4f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Profond", Fmt.hm(n.extDeepMin ?: 0), Modifier.weight(1f))
                Stat("Léger", Fmt.hm(n.extLightMin ?: 0), Modifier.weight(1f))
                Stat("Paradoxal", Fmt.hm(n.extRemMin ?: 0), Modifier.weight(1f))
            }
        }
        if (n.hrAvg != null) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Cœur moyen", "${n.hrAvg} bpm", Modifier.weight(1f))
                Stat("Cœur minimum", "${n.hrMin ?: "–"} bpm", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SoundsCard(n: Night, events: List<SoundEvent>, showAll: Boolean, onToggle: () -> Unit) {
    Card(title = "Bruits de la nuit") {
        Row(Modifier.fillMaxWidth()) {
            Stat("Ronflements", Fmt.hm(n.snoreMin), Modifier.weight(1.1f))
            Stat("Pauses resp.", "${n.apneaCount}", Modifier.weight(1f))
            Stat("Somniloquie", "${n.talkCount}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Toux", "${n.coughCount}", Modifier.weight(1.1f))
            Stat("Bruits", "${n.noiseCount}", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        if (n.apneaCount > 0) {
            Hint(
                "Pause respiratoire possible = ronflement, silence d'au moins 10 s puis reprise brusque du souffle. " +
                    "Ce n'est pas un diagnostic ; si cela revient souvent, parlez-en à un médecin.",
            )
        }
    }
    val highlights = events.filter { it.highlight }
    Card(title = "Moments de la nuit") {
        if (highlights.isEmpty()) Hint("Aucun moment marquant pendant le sommeil. Nuit calme !")
        for (e in highlights) EventRow(e)
    }
    val others = events.filter { !it.highlight }
    if (others.isNotEmpty()) {
        GlassButton(
            if (showAll) "Masquer les autres sons" else "Voir tous les sons détectés (${others.size})",
            onToggle, Modifier.fillMaxWidth(),
        )
        if (showAll) {
            Card {
                Hint("Sons sans extrait conservé (heure, type, durée).")
                for (e in others.take(300)) EventRow(e)
                if (others.size > 300) Hint("… et ${others.size - 300} autres")
            }
        }
    }
}

@Composable
private fun EventRow(e: SoundEvent) {
    val cat = SoundCategory.of(e.category)
    val path = e.clipPath?.takeIf { File(it).exists() }
    val playing = ClipPlayer.playingId == e.id
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Color(cat.color), CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${Fmt.clock(e.start)}  ${e.label}", color = Palette.text, fontSize = 15.sp)
            Text(
                if (cat == SoundCategory.APNEA) cat.label else "${cat.label} · ${Fmt.duration(e.end - e.start)}",
                color = Palette.textFaint, fontSize = 12.sp,
            )
        }
        if (path != null) {
            Glass(
                Modifier.size(42.dp), shape = CircleShape,
                tint = if (playing) Palette.accentDeep.copy(alpha = 0.6f) else Color(0x22101530),
                onClick = { ClipPlayer.toggle(e.id, path) },
            ) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (playing) "Pause" else "Écouter",
                    tint = Palette.text, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
