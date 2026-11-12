package com.eollas.sleepmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Night::class, Epoch::class, SoundEvent::class], version = 3, exportSchema = true)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun dao(): SleepDao

    companion object {
        /** v2 : écran allumé, moments forts, pauses respiratoires (les nuits existantes sont gardées). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nights ADD COLUMN apneaCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nights ADD COLUMN analysisVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE epochs ADD COLUMN screenOn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sound_events ADD COLUMN highlight INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 : 3 catégories les plus probables et écart au bruit de fond de chaque son. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sound_events ADD COLUMN top3 TEXT")
                db.execSQL("ALTER TABLE sound_events ADD COLUMN aboveDb REAL NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: SleepDatabase? = null
        fun get(ctx: Context): SleepDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, SleepDatabase::class.java, "sleep.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
