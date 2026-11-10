package com.eollas.sleepmonitor

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.eollas.sleepmonitor.data.SleepDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Une base créée par la version 1 de l'app doit s'ouvrir sans perte avec la version 2. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test
    fun nuitsConserveesApresMiseAJour() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val schema = JSONObject(File("schemas/com.eollas.sleepmonitor.data.SleepDatabase/1.json").readText()).getJSONObject("database")
        val f = ctx.getDatabasePath("sleep.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                e.optJSONArray("indices")?.let { idx ->
                    for (k in 0 until idx.length()) db.execSQL(idx.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO nights (id, startTime, endTime, totalSleepMin, deepMin, lightMin, awakeMin, latencyMin, awakenings, efficiency, score, snoreMin, talkCount, coughCount, noiseCount, avgDb, audioEnabled, finished, interrupted, hcExported) VALUES (7, 1000, 2000, 360, 0, 0, 0, 0, 0, 90, 84, 10, 0, 0, 0, 30, 1, 1, 0, 0)")
            db.execSQL("INSERT INTO epochs (nightId, t, activity, db, maxDb, stage) VALUES (7, 1000, 1.0, 30, 35, 2)")
            db.execSQL("INSERT INTO sound_events (nightId, start, `end`, category, label, confidence, peakDb, clipPath) VALUES (7, 1000, 2000, 1, 'Ronflement', 0.5, 50, NULL)")
            db.version = 1
        }
        val dao = SleepDatabase.get(ctx).dao()
        val n = dao.night(7)!!
        assertEquals(84, n.score)
        assertEquals(1, n.analysisVersion)
        assertEquals(1, dao.epochs(7).size)
        assertEquals(1, dao.events(7).size)
        assertEquals(1, dao.outdated(2).size)
    }
}
