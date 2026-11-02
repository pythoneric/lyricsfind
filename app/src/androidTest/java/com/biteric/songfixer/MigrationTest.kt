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

/** Songs from a v4 database (measured and tagged before tags were read) come through v5 as they were. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SongDatabase::class.java)

    @Test
    fun v4ToV5KeepsSongsAndReadsTheirTagsNext() = runTest {
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO songs (uri, fileName, folder, size, modified, status, candidates, loudnessLufs, trackGainDb, truePeak, loudnessChecked, replayGainInFile) " +
                    "VALUES ('content://x/a.mp3', 'a.mp3', '', 10, 20, 'OK', '[]', -9.58, -8.42, 0.988, 1, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 5, true, *SongDatabase.MIGRATIONS).close()

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
        } finally {
            db.close()
            context.deleteDatabase(name)
            // Room's lock file isn't one deleteDatabase knows about.
            java.io.File(context.getDatabasePath(name).path + ".lck").delete()
        }
    }
}
