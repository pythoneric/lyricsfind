package com.biteric.songfixer

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.biteric.songfixer.data.SongDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Songs from older databases come through to the latest version as they were. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SongDatabase::class.java)

    @Test
    fun v4ToLatestKeepsSongsAndReadsTheirTagsNext() = runTest {
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessLufs, trackGainDb, truePeak, loudnessChecked, replayGainInFile) " +
                    "VALUES ('content://x/a.mp3', 'a.mp3', '', 10, 20, 'OK', '[]', -9.58, -8.42, 0.988, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, LATEST, true, *SongDatabase.MIGRATIONS).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SongDatabase::class.java, name).addMigrations(*SongDatabase.MIGRATIONS).build()
        try {
            val song = db.songs().all().single()
            assertEquals(-9.58, song.loudnessLufs!!, 0.0)
            assertNull(song.gainVerdict)
            assertNull(song.existingTrackGain)
            // Tagged by the app before: still standard until its tags are read.
            assertTrue(song.volumeTagsStandard)
            // Measured already, but its tags are read in the next check.
            assertEquals(song.id, db.songs().nextUnmeasured()?.id)
            // Its year too.
            assertNull(song.year)
            assertEquals(listOf(song.id), db.songs().yearNotRead().map { it.id })
        } finally {
            db.close()
            context.deleteDatabase(name)
            // Room's lock file isn't one deleteDatabase knows about.
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }

    /** v6 keeps the year: rows from v5 have none yet and get it read once. */
    @Test
    fun v5ToV6AddsTheYear() = runTest {
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessChecked, gainVerdict) " +
                    "VALUES ('content://x/b.mp3', 'b.mp3', '', 10, 20, 'FIXED', '[]', 1, 'STANDARD')",
            )
        }
        helper.runMigrationsAndValidate(name, 6, true, *SongDatabase.MIGRATIONS).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SongDatabase::class.java, name).addMigrations(*SongDatabase.MIGRATIONS).build()
        try {
            val song = db.songs().all().single()
            assertEquals(com.biteric.songfixer.data.SongStatus.FIXED, song.status)
            assertEquals(com.biteric.songfixer.loudness.GainVerdict.STANDARD, song.gainVerdict)
            assertNull(song.year)
            assertEquals(false, song.yearRead)
        } finally {
            db.close()
            context.deleteDatabase(name)
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }

    /** v7 keeps the silence at the ends: rows from v6 (loudness measured, tags read) get it measured next. */
    @Test
    fun v6ToV7MeasuresTheSilenceNext() = runTest {
        helper.createDatabase(name, 6).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessLufs, loudnessChecked, gainVerdict, year, yearRead) " +
                    "VALUES ('content://x/c.mp3', 'c.mp3', '', 10, 20, 'OK', '[]', -9.0, 1, 'STANDARD', 2017, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 7, true, *SongDatabase.MIGRATIONS).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SongDatabase::class.java, name).addMigrations(*SongDatabase.MIGRATIONS).build()
        try {
            val song = db.songs().all().single()
            assertEquals(2017, song.year)
            assertEquals(-9.0, song.loudnessLufs!!, 0.0)
            assertNull(song.silenceStartMs)
            assertEquals(false, song.silenceChecked)
            assertEquals(false, song.hasSilenceToTrim)
            assertEquals(song.id, db.songs().nextUnmeasured()?.id)
        } finally {
            db.close()
            context.deleteDatabase(name)
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }

    /** v8 remembers why a trim failed: rows from v7 haven't failed, and keep their silence to trim. */
    @Test
    fun v7ToV8KeepsTheSilenceToTrim() = runTest {
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessChecked, yearRead, silenceStartMs, silenceEndMs, silenceChecked) " +
                    "VALUES ('content://x/d.mp3', 'd.mp3', '', 10, 20, 'OK', '[]', 1, 1, 2000, 3000, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 8, true, *SongDatabase.MIGRATIONS).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SongDatabase::class.java, name).addMigrations(*SongDatabase.MIGRATIONS).build()
        try {
            val song = db.songs().all().single()
            assertNull(song.trimError)
            assertEquals(2000L, song.silenceStartMs)
            assertTrue(song.hasSilenceToTrim)
        } finally {
            db.close()
            context.deleteDatabase(name)
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }

    /** v9 adds exact silence positions and kept originals: rows from v8 have neither, and nothing else changes. */
    @Test
    fun v8ToV9KeepsTheSongs() = runTest {
        helper.createDatabase(name, 8).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessChecked, yearRead, silenceStartMs, silenceEndMs, silenceChecked) " +
                    "VALUES ('content://x/e.mp3', 'e.mp3', '', 10, 20, 'OK', '[]', 1, 1, 2000, 3000, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 9, true, *SongDatabase.MIGRATIONS).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, SongDatabase::class.java, name).addMigrations(*SongDatabase.MIGRATIONS).build()
        try {
            val song = db.songs().all().single()
            assertNull(song.silenceMark)
            assertNull(song.originalPath)
            assertTrue(song.hasSilenceToTrim)
        } finally {
            db.close()
            context.deleteDatabase(name)
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }

    private companion object {
        const val LATEST = 9
    }
}
