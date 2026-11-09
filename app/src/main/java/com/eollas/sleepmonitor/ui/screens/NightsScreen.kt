package com.eollas.sleepmonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.ui.Fmt
import com.eollas.sleepmonitor.ui.MainViewModel
import com.eollas.sleepmonitor.ui.components.DurationBars
import com.eollas.sleepmonitor.ui.components.Glass
import com.eollas.sleepmonitor.ui.components.ScoreRing
import com.eollas.sleepmonitor.ui.theme.Palette
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding

@Composable
fun NightsScreen(vm: MainViewModel) {
    val nights by vm.nights.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Mes nuits", color = Palette.text, fontSize = 30.sp, fontWeight = FontWeight.Light) }
        if (nights.isEmpty()) {
            item {
                Card {
                    Text("Aucune nuit pour l'instant", color = Palette.text, fontSize = 17.sp)
                    Hint("Lancez le suivi ce soir depuis l'onglet Sommeil : le récapitulatif apparaîtra ici demain matin.")
                }
            }
        } else {
            item { Averages(nights) }
            items(nights, key = { it.id }) { n -> NightRow(n) { vm.openNight = n.id } }
        }
    }
}

@Composable
private fun Averages(nights: List<Night>) {
    val now = System.currentTimeMillis()
    fun avg(days: Int): Night? {
        val l = nights.filter { now - it.startTime < days * 86_400_000L && it.totalSleepMin > 0 }
        if (l.isEmpty()) return null
        return Night(
            startTime = 0,
            totalSleepMin = l.sumOf { it.totalSleepMin } / l.size,
            score = l.sumOf { it.score } / l.size,
            latencyMin = l.sumOf { it.latencyMin } / l.size,
            snoreMin = l.sumOf { it.snoreMin } / l.size,
            deepMin = l.sumOf { it.deepMin } / l.size,
            sleepOnset = l.mapNotNull { it.sleepOnset }.let { o -> if (o.isEmpty()) null else o.size.toLong() },
        )
    }
    Card(title = "Statistiques") {
        DurationBars(nights)
        for ((label, days) in listOf("7 derniers jours" to 7, "30 derniers jours" to 30)) {
            val a = avg(days) ?: continue
            Text(label, color = Palette.textDim, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth()) {
                Stat("Sommeil moyen", Fmt.hm(a.totalSleepMin), Modifier.weight(1f))
                Stat("Score", "${a.score}", Modifier.weight(0.7f))
                Stat("Endormissement", Fmt.hm(a.latencyMin), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Profond", Fmt.hm(a.deepMin), Modifier.weight(1f))
                Stat("Ronflements", Fmt.hm(a.snoreMin), Modifier.weight(0.7f))
                Stat("Nuits", "${a.sleepOnset ?: 0}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NightRow(n: Night, onClick: () -> Unit) {
    Glass(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(n.score, 52.dp, 5.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(Fmt.nightTitle(n.startTime), color = Palette.text, fontSize = 15.sp)
                Text(
                    buildString {
                        append(if (n.totalSleepMin > 0) Fmt.hm(n.totalSleepMin) else "Sommeil non détecté")
                        if (n.sleepOnset != null) append(" · ${Fmt.clock(n.sleepOnset)} → ${Fmt.clock(n.wakeTime)}")
                    },
                    color = Palette.textDim, fontSize = 13.sp,
                )
                val sounds = buildList {
                    if (n.snoreMin > 0) add("ronflements ${n.snoreMin} min")
                    if (n.talkCount > 0) add("paroles ${n.talkCount}")
                    if (n.noiseCount > 0) add("bruits ${n.noiseCount}")
                }
                if (sounds.isNotEmpty()) Text(sounds.joinToString(" · ").replaceFirstChar { it.uppercase() }, color = Palette.textFaint, fontSize = 12.sp)
            }
        }
    }
}
