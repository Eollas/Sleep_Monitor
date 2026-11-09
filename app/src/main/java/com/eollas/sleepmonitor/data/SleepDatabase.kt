package com.eollas.sleepmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Night::class, Epoch::class, SoundEvent::class], version = 1, exportSchema = true)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun dao(): SleepDao

    companion object {
        @Volatile private var instance: SleepDatabase? = null
        fun get(ctx: Context): SleepDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, SleepDatabase::class.java, "sleep.db")
                .build().also { instance = it }
        }
    }
}
