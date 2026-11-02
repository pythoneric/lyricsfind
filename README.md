# Song Fixer

Android app that finds the right title, artist, album and genre for the songs in a folder, so
lyrics apps can find their lyrics. Files with junk names (`track01.mp3`,
`artist_-_title_(official_video).mp3`), wrong tags or no tags at all.

## How it works

1. You pick a music folder (Android folder permission, read + write).
2. A background job (notification while it runs) checks each song:
   - **By sound**: decodes the first 2 minutes with Android's codecs, computes a Chromaprint
     fingerprint (`fingerprint/Chromaprint.kt`, a Kotlin port of Chromaprint 1.5.1, checked
     against Chromaprint's own test data) and looks it up on [AcoustID](https://acoustid.org).
     Needs a free AcoustID application key (Settings).
   - **By name** (no key, or the sound isn't known): cleans the file name and tags
     (`match/NameGuess.kt`) and searches MusicBrainz and LRCLIB, ranking by name similarity and
     length.
   - Missing genres come from MusicBrainz; LRCLIB is asked whether lyrics exist under the
     suggested name.
3. You review suggestions ("Sure match" / "Check it"), edit if needed, and fix: tags are written
   (jaudiotagger, into a verified copy that then replaces the file) and the file is renamed to
   `Artist - Title.ext`. "Fix N sure ones" applies every sure suggestion at once.
4. Lyrics: when fixing, the lyrics are fetched from LRCLIB under the final names (synced LRC
   when available, else plain) and written into the file's lyrics tag (players that understand
   LRC, like BitEric, scroll along). Optionally also as `Artist - Title.lrc` next to the song
   (always for formats without a lyrics tag). Lyrics already in a file are never replaced.
   "Add lyrics to N songs" does the same for songs whose names were already right.

## Build

```
.\gradlew.bat assembleDebug          # app/build/outputs/apk/debug/SongFixer-debug.apk
.\gradlew.bat testDebugUnitTest      # fingerprint, name parsing, matching (fake server)
```

Device tests (install app + test APK, then):

```
adb shell am instrument -w -e class com.biteric.songfixer.DeviceTest com.biteric.songfixer.test/androidx.test.runner.AndroidJUnitRunner
```

`DeviceTest` compares the on-device fingerprint of Chromaprint's `test.mp3` with fpcalc's
(98.9% equal bits on a Galaxy S26 Ultra) and writes tags. `LiveCheckTest` is opt-in
(`-e live 1`): it checks the folder chosen in the app against the real services and logs the results.

## Credits

Chromaprint (MIT; its resampler is from FFmpeg, LGPL 2.1), AcoustID, MusicBrainz (CC0 data),
LRCLIB, jaudiotagger (LGPL 2.1). Chromaprint test files in `app/src/test/resources` and
`app/src/androidTest/assets` come from the Chromaprint repository.
