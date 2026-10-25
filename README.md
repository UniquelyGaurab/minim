# Minim

Minim is a lightweight, minimal YouTube Music client for Android. It keeps only what you need to listen: search, a clean player, your library and a few fun extras, with no clutter.

It runs on Android 9 (API 28) and newer, and the app is about 8.5 MB.

## Features

- **Dynamic colours:** the whole app takes its colours from the cover of the song that's playing. A **Default** mode in Settings keeps it black and white instead.
- **Light and dark:** both themes, in Dynamic and Default.
- **Rotating greetings:** Home greets you with lines that change every few seconds, including ones based on what you're playing, your recent songs and your liked songs.
- **Synced lyrics:** time-synced lyrics from LRCLib, with a timing adjustment in Settings. Tap the lyric line on Now Playing for full screen lyrics over a blurred cover, lit up word by word (Flow) or line by line (Lyrics style in Settings).
- **Smooth motion:** colours fade between songs, tabs slide, lists slide in, Up next glides, covers fade in, pills respond to presses and Stats numbers count up. Turn it all off with Remove animations in Settings.
- **Discover:** shelves of songs picked from what you listen to, plus a radio queue that keeps playing similar songs.
- **Artist pages:** a full-bleed artist photo, monthly listeners, top songs, albums and a shuffle button. Open one from search or by tapping the artist on Now Playing.
- **Stats and daily recap:** today's listening time, songs, artists and your streak, plus a daily recap story with a card you can share or save.
- **Volume boost:** Off, Normal or Loud, for songs that play too quietly.
- **HD artwork:** covers load at the size each spot needs, up to full screen width.
- **Optional Google sign-in (5.0):** Minim works fully without an account. Sign in from Settings if you want your music backed up and on more than one phone.
- **Cloud sync (5.0):** while you're signed in, your likes, playlists, stats and play history sync, so Discover and your recommendations carry over to a new phone. The first sign-in merges what's on the phone with what's in your account, signing in with a different Google account asks whether to Merge or Start fresh, and changes made offline sync when you're back online. Settings stay on the phone.
- **Festivals and announcements (5.0):** festival banners, colours and greetings, one-time announcements and feature switches can be set from a config document in Firestore, without a new build. See [FIREBASE_SETUP.md](FIREBASE_SETUP.md).
- **Updates in the app:** when Minim opens or comes back after a few hours, it checks this repo's latest release and offers to download and install a newer version. You can also check any time in Settings.
- **Library:** liked songs, playlists, recents and a song menu on long-press (play next, add to queue, add to playlist, go to artist, share).

## Privacy

Signing in is optional. Minim only gets your name, profile picture and email. It never sees your password and can't access your Gmail, Drive, contacts or anything else in your Google account. Sync stores only song IDs, song details (title, artist, cover link, length) and times under your own account, and **Delete account** in Settings removes all of it.

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

### Firebase (optional)

Sign-in, sync and the config document need your own Firebase project. Without it the app builds and runs the same, and the Account section in Settings stays hidden. To turn it on, follow [FIREBASE_SETUP.md](FIREBASE_SETUP.md): create a Firebase project, put its `google-services.json` in `app/` (see `app/google-services.json.example`; the real file is ignored by git and must never be committed), and publish the rules in [`firestore.rules`](firestore.rules).

### Signing

By default, debug builds are signed with the standard Android debug key on your own machine. To sign with a specific key, create a `keystore.properties` file in the project root (it is ignored by git and must never be committed):

```properties
storeFile=minim.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Android only installs an update over an existing app when both are signed with the same key.

## Editing greetings

Home greetings can be added without a new build. Minim downloads [`greetings/greetings.json`](greetings/greetings.json) from this repo about once a day and keeps a copy for offline use. If the file is broken it is ignored and the built-in greetings carry on.

To edit it from your phone:

1. Open this repo on github.com in your browser and sign in.
2. Tap the `greetings` folder, then `greetings.json`.
3. Tap the pencil icon (Edit). If you don't see it, switch to the desktop site in your browser's menu.
4. Make your change, tap **Commit changes**, and commit straight to `main`.

Phones pick it up the next time Minim checks, usually within a day.

Each greeting is one line in the list:

```json
{ "text": "Volume up.\nWorries down." },
{ "name": "Diwali 2026", "text": "Happy Diwali, {name}!\nMake it sparkle.", "from": "11-06", "to": "11-10", "day": "11-08", "year": 2026 },
```

- `text` (required): `\n` splits the two lines. Keep each line under 40 characters (80 in total). `{name}` is replaced with your name, and a greeting that uses it only shows when a name is set.
- `from` and `to` (optional): dates as `MM-DD`. A greeting with dates only shows on those days. Leave `to` out for a single day. A range like `12-31` to `01-01` runs over New Year. A greeting without dates is an "anytime" one that mixes into the normal rotation.
- `day` (optional): the main day inside the range. Festival greetings come up about every 6th greeting inside their dates and every 3rd on the main day. Without it, the main day is `from`.
- `year` (optional): only that year. Festivals that move, like Diwali, Holi and Eid, need a new entry with the new dates each year.
- `name` (optional): a label for you. Minim ignores it, along with any other field it doesn't know.

Rules that keep the file working: keep `"version": 1` at the top, put a comma between greetings but not after the last one, and use straight double quotes. A greeting with a mistake in it (no text, too long, a date that doesn't exist like `02-30`) is skipped and the rest still work. A mistake in the file's structure, like a missing bracket, makes Minim ignore the whole new file and keep the last good copy. Pasting the file into [jsonlint.com](https://jsonlint.com) before committing catches those.

## Credits

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) for search and streams (GPL-3.0)
- [LRCLib](https://lrclib.net) for synced lyrics
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) for playback
- [OkHttp](https://github.com/square/okhttp) for networking
- [Firebase](https://firebase.google.com) Authentication and Cloud Firestore for the optional sign-in and sync
- [Poppins](https://fonts.google.com/specimen/Poppins) font (SIL Open Font License)

From the app's own credits: designed by Gaurab, built by Charlie (Sesame AI), early code by Google Gemini.

Minim is not affiliated with YouTube or Google.

## License

Minim is free software under the [GNU General Public License v3.0](LICENSE).

Author: [UniquelyGaurab](https://github.com/UniquelyGaurab)
