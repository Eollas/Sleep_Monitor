package com.eollas.sleepmonitor

import android.Manifest
import android.os.PowerManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.rule.GrantPermissionRule
import com.eollas.sleepmonitor.analysis.SleepAnalyzer
import com.eollas.sleepmonitor.data.Epoch
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.Repository
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import com.eollas.sleepmonitor.tracking.Live
import com.eollas.sleepmonitor.tracking.LiveState
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import kotlin.random.Random

/** Génère des captures d'écran de l'app avec des nuits fictives. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class ScreenshotTest {
    @get:Rule(order = 0)
    val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val out = "../screenshots"

    private fun seed(): Long = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(ctx.packageName, true)
        val repo = Repository.get(ctx)
        val dao = repo.dao
        var lastId = 0L
        val rnd = Random(3)
        for (d in 9 downTo 0) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -d - 1)
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, rnd.nextInt(0, 50)); set(Calendar.SECOND, 0)
            }
            val start = c.timeInMillis
            val n = 860 + rnd.nextInt(0, 140)
            val id = dao.insertNight(Night(startTime = start, audioEnabled = true))
            val epochs = (0 until n).map { i ->
                var a = 1f + rnd.nextFloat() * 0.6f
                if (i < 30 + rnd.nextInt(0, 20)) a = 5f + rnd.nextFloat() * 25f
                if (rnd.nextInt(100) < 3) a = 6f + rnd.nextFloat() * 10f
                if (i in 380..390 && d % 3 == 0) a = 10f + rnd.nextFloat() * 20f
                if (i > n - 20) a = 10f + rnd.nextFloat() * 30f
                val db = 32f + rnd.nextFloat() * 4f + if (i in 200..320) 14f * rnd.nextFloat() else 0f
                Epoch(id, start + i * SleepAnalyzer.EPOCH_MS, a, db, db + 6f)
            }
            dao.insertEpochs(epochs)
            val clip = File(ctx.filesDir, "clips/$id/demo.m4a").apply { parentFile?.mkdirs(); writeBytes(ByteArray(10)) }
            suspend fun ev(min: Int, cat: SoundCategory, label: String, sec: Int) = dao.insertEvent(
                SoundEvent(
                    nightId = id, start = start + min * 60_000L, end = start + min * 60_000L + sec * 1000L,
                    category = cat.id, label = label, confidence = 0.6f, peakDb = 60f, clipPath = clip.path,
                ),
            )
            for (k in 0 until 6) ev(100 + k * 9, SoundCategory.SNORING, "Ronflement", 60 + rnd.nextInt(0, 200))
            ev(185, SoundCategory.SPEECH, "Paroles", 7)
            ev(240, SoundCategory.COUGH, "Toux", 3)
            ev(300, SoundCategory.NOISE, "Moto", 12)
            ev(410, SoundCategory.NOISE, "Chien", 5)
            repo.finish(id, start + n * SleepAnalyzer.EPOCH_MS)
            lastId = id
        }
        lastId
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    @Test
    fun captures() {
        seed()
        settle()
        compose.onRoot().captureRoboImage("$out/1_accueil.png")

        compose.onNodeWithText("Voir").performClick(); settle()
        compose.onRoot().captureRoboImage("$out/3_recap.png")
        compose.activity.onBackPressedDispatcher.onBackPressed(); settle()

        compose.onNodeWithText("Mes nuits").performClick(); settle()
        compose.onRoot().captureRoboImage("$out/4_mes_nuits.png")

        compose.onNodeWithText("Réglages").performClick(); settle()
        compose.onRoot().captureRoboImage("$out/5_reglages.png")

        compose.onNodeWithText("Sommeil").performClick(); settle()
        val now = System.currentTimeMillis()
        Live.state.value = LiveState(
            running = true, nightId = 99, start = now - 95 * 60_000L, asleepSince = now - 70 * 60_000L,
            asleepNow = true, db = 48f, audio = true, snoreSec = 540, talk = 1, cough = 0, noise = 2,
        )
        settle()
        compose.onRoot().captureRoboImage("$out/2_suivi_en_cours.png")
        Live.state.value = LiveState()
    }
}
