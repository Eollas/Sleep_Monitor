package com.eollas.sleepmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eollas.sleepmonitor.data.Epoch
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import com.eollas.sleepmonitor.data.Stage
import com.eollas.sleepmonitor.ui.Fmt
import com.eollas.sleepmonitor.ui.theme.Palette
import java.util.Calendar

/** Anneau de score (0 à 100). */
@Composable
fun ScoreRing(score: Int, size: Dp, stroke: Dp = 10.dp, label: Boolean = true) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val s = Size(this.size.width - sw, this.size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(Color.White.copy(alpha = 0.10f), -90f, 360f, false, tl, s, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(Palette.accentDeep, Palette.accent, Palette.moon, Palette.accentDeep)),
                -90f, 360f * score.coerceIn(0, 100) / 100f, false, tl, s, style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        if (label) Text("$score", color = Palette.text, fontSize = (size.value / 3.2f).sp, fontWeight = FontWeight.Light)
    }
}

private fun hourTicks(start: Long, end: Long): List<Long> {
    val c = Calendar.getInstance().apply {
        timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.HOUR_OF_DAY, 1)
    }
    val out = mutableListOf<Long>()
    val step = if (end - start > 10 * 3_600_000L) 2 else 1
    while (c.timeInMillis < end) { out += c.timeInMillis; c.add(Calendar.HOUR_OF_DAY, step) }
    return out
}

/** Hypnogramme : éveil / léger / profond au fil de la nuit. */
@Composable
fun Hypnogram(night: Night, epochs: List<Epoch>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val start = night.startTime
    val end = night.endTime ?: (epochs.lastOrNull()?.t ?: start)
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val labelW = 52.dp.toPx()
        val axisH = 20.dp.toPx()
        val w = size.width - labelW
        val rowH = (size.height - axisH) / 3f
        val span = (end - start).coerceAtLeast(1)
        val names = listOf("Éveil" to Palette.awake, "Léger" to Palette.light, "Profond" to Palette.deep)
        names.forEachIndexed { i, (n, col) ->
            drawText(tm, n, Offset(0f, rowH * i + rowH / 2 - 8.dp.toPx()), TextStyle(color = col.copy(alpha = 0.9f), fontSize = 11.sp))
            drawLine(Color.White.copy(alpha = 0.06f), Offset(labelW, rowH * (i + 1)), Offset(size.width, rowH * (i + 1)))
        }
        for (e in epochs) {
            val row = when (e.stage) { Stage.DEEP -> 2; Stage.LIGHT -> 1; else -> 0 }
            val col = when (row) { 2 -> Palette.deep; 1 -> Palette.light; else -> Palette.awake }
            val x = labelW + (e.t - start).toFloat() / span * w
            val bw = (30_000f / span * w).coerceAtLeast(1f) + 0.6f
            drawRoundRect(col, Offset(x, rowH * row + rowH * 0.18f), Size(bw, rowH * 0.64f), CornerRadius(2f))
        }
        for (t in hourTicks(start, end)) {
            val x = labelW + (t - start).toFloat() / span * w
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x, 0f), Offset(x, size.height - axisH))
            val lx = (x - 14.dp.toPx()).coerceAtMost(size.width - 30.dp.toPx())
            drawText(tm, Fmt.clock(t), Offset(lx, size.height - axisH + 4.dp.toPx()), TextStyle(color = Palette.textFaint, fontSize = 10.sp))
        }
    }
}

/** Mouvements (zone violette), niveau sonore (ligne bleue) et bruits détectés (points). */
@Composable
fun ActivityChart(night: Night, epochs: List<Epoch>, events: List<SoundEvent>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val start = night.startTime
    val end = night.endTime ?: (epochs.lastOrNull()?.t ?: start)
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val axisH = 20.dp.toPx()
        val dotsH = 14.dp.toPx()
        val h = size.height - axisH - dotsH
        val w = size.width
        val span = (end - start).coerceAtLeast(1)
        if (epochs.isNotEmpty()) {
            val sorted = epochs.map { it.activity }.sorted()
            val top = sorted[(sorted.size * 0.97f).toInt().coerceIn(0, sorted.size - 1)].coerceAtLeast(5f)
            val area = Path().apply {
                moveTo(0f, h)
                for (e in epochs) lineTo((e.t - start).toFloat() / span * w, h - (e.activity / top).coerceIn(0f, 1f) * h)
                lineTo((epochs.last().t - start).toFloat() / span * w, h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(Palette.accent.copy(alpha = 0.7f), Palette.accent.copy(alpha = 0.08f)), 0f, h))
            if (night.audioEnabled) {
                val line = Path()
                var first = true
                for (e in epochs) {
                    if (e.db <= 0f) continue
                    val x = (e.t - start).toFloat() / span * w
                    val y = h - ((e.db - 25f) / 60f).coerceIn(0f, 1f) * h
                    if (first) { line.moveTo(x, y); first = false } else line.lineTo(x, y)
                }
                drawPath(line, Palette.sound.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
            }
        }
        for (ev in events) {
            val x = ((ev.start - start).toFloat() / span * w).coerceIn(0f, w)
            drawCircle(Color(SoundCategory.of(ev.category).color), 3.dp.toPx(), Offset(x, h + dotsH / 2))
        }
        for (t in hourTicks(start, end)) {
            val x = ((t - start).toFloat() / span * w).coerceAtMost(w - 16.dp.toPx())
            drawText(tm, Fmt.clock(t), Offset(x - 14.dp.toPx(), size.height - axisH + 4.dp.toPx()), TextStyle(color = Palette.textFaint, fontSize = 10.sp))
        }
    }
}

/** Durée de sommeil des dernières nuits (barres). */
@Composable
fun DurationBars(nights: List<Night>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val list = nights.take(14).reversed()
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        if (list.isEmpty()) return@Canvas
        val axisH = 18.dp.toPx()
        val h = size.height - axisH
        val slot = size.width / 14f
        val bw = slot * 0.55f
        val maxMin = 10 * 60f
        // Repère des 8 h
        val y8 = h - 480f / maxMin * h
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y8), Offset(size.width, y8), strokeWidth = 1f)
        drawText(tm, "8 h", Offset(size.width - 22.dp.toPx(), y8 - 14.dp.toPx()), TextStyle(color = Palette.textFaint, fontSize = 9.sp))
        list.forEachIndexed { i, n ->
            val bh = (n.totalSleepMin / maxMin).coerceIn(0.02f, 1f) * h
            val x = slot * (i + 14 - list.size) + (slot - bw) / 2
            drawRoundRect(
                Brush.verticalGradient(listOf(Palette.accent, Palette.accentDeep.copy(alpha = 0.6f)), h - bh, h),
                Offset(x, h - bh), Size(bw, bh), CornerRadius(bw / 2),
            )
            drawText(tm, Fmt.dayShort(n.endTime ?: n.startTime), Offset(x + bw / 2 - 4.dp.toPx(), h + 3.dp.toPx()), TextStyle(color = Palette.textFaint, fontSize = 10.sp))
        }
    }
}

/** Petite barre de niveau sonore en direct. */
@Composable
fun LevelBar(db: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val f = ((db - 25f) / 60f).coerceIn(0f, 1f)
        drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Palette.sound, Palette.accent)),
            size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height / 2),
        )
    }
}
