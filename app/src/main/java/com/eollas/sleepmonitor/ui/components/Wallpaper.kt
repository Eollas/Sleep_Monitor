package com.eollas.sleepmonitor.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.random.Random

/** Fonds d'écran proposés (dessinés, donc nets à toutes les tailles). */
object Wallpapers {
    val presets = listOf("nuit" to "Nuit", "crepuscule" to "Crépuscule", "ocean" to "Océan", "aurore" to "Aurore")

    private class Star(val x: Float, val y: Float, val r: Float, val a: Float)
    private val stars = Random(7).let { rnd ->
        List(70) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.8f, 0.25f + rnd.nextFloat() * 0.6f) }
    }

    fun draw(scope: DrawScope, id: String, image: ImageBitmap?, dim: Float, size: Size) = with(scope) {
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@with
        if (id == "custom" && image != null) {
            val s = max(w / image.width, h / image.height)
            val dw = image.width * s; val dh = image.height * s
            drawImage(
                image, IntOffset.Zero, IntSize(image.width, image.height),
                IntOffset(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt()), IntSize(dw.toInt(), dh.toInt()),
            )
            drawRect(Color.Black.copy(alpha = dim.coerceIn(0f, 0.85f)), size = size)
            return@with
        }
        when (id) {
            "crepuscule" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF140E2E), Color(0xFF2C1A47), Color(0xFF5A2F55)), 0f, h), size = size)
                stars(this, size, 0.7f, 0.65f)
                moon(this, Offset(w * 0.22f, h * 0.2f), w * 0.11f)
                drawRect(Brush.radialGradient(listOf(Color(0x55E98A6B), Color.Transparent), Offset(w * 0.5f, h * 1.05f), w * 0.9f), size = size)
            }
            "ocean" -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF04111B), Color(0xFF082233), Color(0xFF0D3346)), 0f, h), size = size)
                stars(this, size, 0.45f, 0.6f)
                drawRect(Brush.radialGradient(listOf(Color(0x4434B3C9), Color.Transparent), Offset(w * 0.3f, h * 0.95f), w * 1.0f), size = size)
            }
            "aurore" -> {
                drawRect(Color(0xFF060A16), size = size)
                drawRect(Brush.radialGradient(listOf(Color(0x5531D3A0), Color.Transparent), Offset(w * 0.2f, h * 0.18f), w * 0.8f), size = size)
                drawRect(Brush.radialGradient(listOf(Color(0x558B5CF6), Color.Transparent), Offset(w * 0.85f, h * 0.32f), w * 0.85f), size = size)
                stars(this, size, 0.8f, 1f)
            }
            else -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0F1F), Color(0xFF131A35), Color(0xFF0B1020)), 0f, h), size = size)
                stars(this, size, 1f, 1f)
                moon(this, Offset(w * 1.02f, h * 0.40f), w * 0.2f)
            }
        }
        drawRect(Color.Black.copy(alpha = (dim * 0.5f).coerceIn(0f, 0.6f)), size = size)
    }

    private fun stars(scope: DrawScope, size: Size, alpha: Float, part: Float) = with(scope) {
        val scale = density
        for (s in stars) {
            if (s.y > part) continue
            drawCircle(Color.White.copy(alpha = s.a * alpha), s.r * scale, Offset(s.x * size.width, s.y * size.height))
        }
    }

    private fun moon(scope: DrawScope, c: Offset, r: Float) = with(scope) {
        drawCircle(Brush.radialGradient(listOf(Color(0x33E9DDB8), Color.Transparent), c, r * 2.6f), r * 2.6f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF4ECD2), Color(0xFFD9CBA0)), c - Offset(r * 0.3f, r * 0.3f), r * 1.4f), r, c)
    }
}

/** Le fond actuel, partagé avec les cartes « vitre » qui le redessinent floutté. */
@Stable
class Backdrop {
    var id by mutableStateOf("nuit")
    var image by mutableStateOf<ImageBitmap?>(null)
    var dim by mutableStateOf(0.35f)
    var rootSize by mutableStateOf(Size.Zero)

    fun draw(scope: DrawScope) = Wallpapers.draw(scope, id, image, dim, rootSize)
}

val LocalBackdrop = staticCompositionLocalOf { Backdrop() }
