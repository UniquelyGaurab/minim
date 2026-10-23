# Minim

Minim is a lightweight, minimal YouTube Music client for Android. It keeps only what you need to listen: search, a clean player, your library and a few fun extras, with no clutter.

It runs on Android 9 (API 28) and newer, and the app is about 7 MB.

## Features

- **Dynamic colours:** the whole app takes its colours from the cover of the song that's playing. A **Default** mode in Settings keeps it black and white instead.
- **Light and dark:** both themes, in Dynamic and Default.
- **Rotating greetings:** Home greets you with lines that change every few seconds, including ones based on what you're playing, your recent songs and your liked songs.
- **Synced lyrics:** time-synced lyrics from LRCLib, with a timing adjustment in Settings.
- **Discover:** shelves of songs picked from what you listen to, plus a radio queue that keeps playing similar songs.
- **Artist pages:** a full-bleed artist photo, monthly listeners, top songs, albums and a shuffle button. Open one from search or by tapping the artist on Now Playing.
- **Stats and daily recap:** today's listening time, songs, artists and your streak, plus a daily recap story with a card you can share or save.
- **Volume boost:** Off, Normal or Loud, for songs that play too quietly.
- **Library:** liked songs, playlists, recents and a song menu on long-press (play next, add to queue, add to playlist, go to artist, share).

## Download

Get the latest APK from the [Releases](https://github.com/UniquelyGaurab/minim/releases) page.

## Building

You need:

- JDK 17
- Android SDK with platform 34 and build-tools 34.0.0

Then:

```bash
git clone https://github.com/UniquelyGaurab/minim.git
cd minim
echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Run the unit tests with `./gradlew testDebugUnitTest`.

### Signing

By default, debug builds are signed with the standard Android debug key on your own machine. To sign with a specific key, create a `keystore.properties` file in the project root (it is ignored by git and must never be committed):

```properties
storeFile=minim.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Android only installs an update over an existing app when both are signed with the same key.

## Credits

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) for search and streams (GPL-3.0)
- [LRCLib](https://lrclib.net) for synced lyrics
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) for playback
- [OkHttp](https://github.com/square/okhttp) for networking
- [Poppins](https://fonts.google.com/specimen/Poppins) font (SIL Open Font License)

From the app's own credits: designed by Gaurab, built by Charlie (Sesame AI), early code by Google Gemini.

Minim is not affiliated with YouTube or Google.

## License

Minim is free software under the [GNU General Public License v3.0](LICENSE).

Author: [UniquelyGaurab](https://github.com/UniquelyGaurab)
