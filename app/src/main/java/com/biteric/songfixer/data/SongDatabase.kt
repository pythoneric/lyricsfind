package com.biteric.songfixer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

data class StatusCount(val status: SongStatus, val count: Int)

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY folder, fileName COLLATE NOCASE")
    fun observeAll(): Flow<List<Song>>

    @Query("SELECT status, COUNT(*) AS count FROM songs GROUP BY status")
    fun observeCounts(): Flow<List<StatusCount>>

    @Query("SELECT * FROM songs")
    suspend fun all(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: Long): Song?

    @Query("SELECT * FROM songs WHERE status = 'NEW' ORDER BY folder, fileName COLLATE NOCASE LIMIT 1")
    suspend fun nextUnchecked(): Song?

    @Query("SELECT COUNT(*) FROM songs WHERE status = 'NEW'")
    suspend fun uncheckedCount(): Int

    @Insert
    suspend fun insert(songs: List<Song>)

    @Update
    suspend fun update(song: Song)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun clear()

    /** Check again: everything except songs the user already decided on. */
    @Query("UPDATE songs SET status = 'NEW' WHERE status NOT IN ('FIXED', 'KEPT')")
    suspend fun markAllUnchecked()

    @Query("UPDATE songs SET status = 'NEW' WHERE status = 'FAILED'")
    suspend fun markFailedUnchecked()

    /** Not measured yet, or its volume tags not read yet (read again after the file changed). */
    @Query("SELECT * FROM songs WHERE loudnessChecked = 0 OR gainVerdict IS NULL ORDER BY folder, fileName COLLATE NOCASE LIMIT 1")
    suspend fun nextUnmeasured(): Song?

    @Query("SELECT COUNT(*) FROM songs WHERE loudnessChecked = 0 OR gainVerdict IS NULL")
    suspend fun unmeasuredCount(): Int
}

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromCandidates(list: List<Candidate>): String = json.encodeToString(list)

    @TypeConverter
    fun toCandidates(s: String): List<Candidate> = runCatching { json.decodeFromString<List<Candidate>>(s) }.getOrDefault(emptyList())
}

@Database(entities = [Song::class], version = 5)
@TypeConverters(Converters::class)
abstract class SongDatabase : RoomDatabase() {
    abstract fun songs(): SongDao

    companion object {
        /** v2: lyricsInFile. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE songs ADD COLUMN lyricsInFile INTEGER DEFAULT NULL")
        }

        /** v3: hasCover. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE songs ADD COLUMN hasCover INTEGER DEFAULT NULL")
        }

        /** v4: loudness (ReplayGain). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN loudnessLufs REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN trackGainDb REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN truePeak REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN loudnessChecked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE songs ADD COLUMN replayGainInFile INTEGER DEFAULT NULL")
            }
        }

        /** v5: the volume tags already in the file, and whether they follow ReplayGain 2.0. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN existingTrackGain TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN existingTrackPeak TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN existingReference TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE songs ADD COLUMN gainVerdict TEXT DEFAULT NULL")
            }
        }

        fun create(context: Context) = Room.databaseBuilder(context, SongDatabase::class.java, "songfixer.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }
}
