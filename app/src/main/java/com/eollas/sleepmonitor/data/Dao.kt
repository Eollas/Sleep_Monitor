package com.eollas.sleepmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {
    @Insert suspend fun insertNight(n: Night): Long
    @Update suspend fun updateNight(n: Night)
    @Query("SELECT * FROM nights WHERE id = :id") suspend fun night(id: Long): Night?
    @Query("SELECT * FROM nights WHERE id = :id") fun nightFlow(id: Long): Flow<Night?>
    @Query("SELECT * FROM nights WHERE finished = 1 ORDER BY startTime DESC") fun nightsFlow(): Flow<List<Night>>
    @Query("SELECT * FROM nights WHERE finished = 0") suspend fun unfinished(): List<Night>
    @Query("DELETE FROM nights WHERE id = :id") suspend fun deleteNight(id: Long)
    @Query("DELETE FROM nights") suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpoch(e: Epoch)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpochs(e: List<Epoch>)
    @Query("SELECT * FROM epochs WHERE nightId = :id ORDER BY t") suspend fun epochs(id: Long): List<Epoch>
    @Query("SELECT * FROM epochs WHERE nightId = :id ORDER BY t") fun epochsFlow(id: Long): Flow<List<Epoch>>

    @Insert suspend fun insertEvent(e: SoundEvent): Long
    @Query("SELECT * FROM sound_events WHERE nightId = :id ORDER BY start") suspend fun events(id: Long): List<SoundEvent>
    @Query("SELECT * FROM sound_events WHERE nightId = :id ORDER BY start") fun eventsFlow(id: Long): Flow<List<SoundEvent>>
    @Query("SELECT * FROM sound_events WHERE clipPath IS NOT NULL AND start < :before") suspend fun clipsBefore(before: Long): List<SoundEvent>
    @Query("SELECT * FROM sound_events WHERE clipPath IS NOT NULL") suspend fun allClips(): List<SoundEvent>
    @Query("UPDATE sound_events SET clipPath = NULL WHERE id = :id") suspend fun clearClip(id: Long)
}
