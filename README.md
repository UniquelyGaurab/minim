# Minim

Minim is a lightweight, minimal YouTube Music client for Android. It keeps only what you need to listen: search, a clean player, your library and a few fun extras, with no clutter.

It runs on Android 9 (API 28) and newer, and the app is about 8.9 MB.

## Features

- **Dynamic colours:** the whole app takes its colours from the cover of the song that's playing. A **Default** mode in Settings keeps it black and white instead.
- **Light and dark:** both themes, in Dynamic and Default.
- **Rotating greetings:** Home greets you with a new line every 7 seconds, including ones about what you're playing, your recent songs, your liked songs and how much you've listened. Apart from the time-of-day greetings, they all come from [`greetings/greetings.json`](greetings/greetings.json) in this repo, so new lines arrive without an update.
- **Synced lyrics:** time-synced lyrics from LRCLib, with a timing adjustment in Settings. Tap the lyric line on Now Playing for full screen lyrics over a blurred cover, lit up word by word (Flow) or line by line (Lyrics style in Settings). In full screen lyrics, tap any line to jump the song there.
- **Smooth motion:** colours fade between songs, tabs slide, lists slide in, Up next glides, covers fade in, pills respond to presses and Stats numbers count up. Turn it all off with Remove animations in Settings.
- **Discover:** shelves of songs picked from what you listen to, plus a radio queue that keeps playing similar songs.
- **Artist pages:** a full-bleed artist photo, monthly listeners, top songs, albums and a shuffle button. Open one from search or by tapping the artist on Now Playing.
- **Stats and daily recap:** today's listening time, songs, artists and your streak, plus a daily recap story with a card you can share or save.
- **Volume boost:** Off, Normal or Loud, for songs that play too quietly.
- **Bass boost (5.5.2):** Off, Warm, Punchy or Heavy in Settings, right under Volume boost. Warm adds 4 dB, Punchy 7 dB and Heavy 10 dB, centred on the kick and the bassline, while voices and mids stay put. It's built into Minim (not Android's bass booster), a limiter keeps it from crackling even with Volume boost on Loud, and switching stages slides in over half a second with no click.
- **HD artwork:** covers load at the size each spot needs, up to full screen width.
- **Optional Google sign-in (5.0):** Minim works fully without an account. Sign in from Settings if you want your music backed up and on more than one phone.
- **Cloud sync (5.0):** while you're signed in, your likes, playlists, stats and play history sync, so Discover and your recommendations carry over to a new phone. The first sign-in merges what's on the phone with what's in your account, signing in with a different Google account asks whether to Merge or Start fresh, and changes made offline sync when you're back online. Settings stay on the phone.
- **Festivals and announcements (5.0):** festival banners, colours and greetings, one-time announcements and feature switches can be set from a config document in Firestore, without a new build. See [FIREBASE_SETUP.md](FIREBASE_SETUP.md).
- **Ask Gemini (5.2.0):** a chat with Google Gemini, opened from **Ask Gemini** under Search on Home or **Ask** next to Up next on Now Playing. It can find and play songs, queue them, like the song that's playing, open artist pages, translate or explain the lyrics, roast your listening stats and put together a playlist you can edit before saving. Replies are short and in any language. The chat is only kept while the app is open. Pick the model in Settings (Gemini 3.5 Flash-Lite by default, Gemini 3.1 Flash-Lite, or any model ID from Google AI Studio) and add your own free AI Studio key there if you like.
- **Playlist import (5.3.0):** tap **Add playlist** in Library, then **Import Playlist**, and paste a YouTube or YouTube Music playlist, album or mix link, a Spotify playlist or album link, or a plain list of songs (one per line). You can also share a link to Minim from Spotify or YouTube Music. Spotify playlists are read from Spotify's public pages, with no login or key, up to 1,000 songs. The import runs in the background while you listen. Then you review the matches, pick another version for any song Minim isn't sure about or leave it out, and save it as a normal playlist.
- **Editing playlists (5.3.2):** tap **+ Add songs** at the top of a playlist to search and add several songs in a row, and tap a playlist's name to rename it. Imported playlists show a small Spotify, YouTube Music or YouTube logo under the name, in the app's colours.
- **Save offline (5.4.0):** long-press a song, a playlist or Liked Songs and tap **Save offline**. Minim saves the best audio YouTube offers (usually Opus at about 160 kbps, exactly as sent, with no re-encoding), 3 songs at a time, each in 4 parts at once, on Wi-Fi or mobile data. The synced lyrics and the cover are saved too, so a saved song plays with its lyrics and cover with no signal. Saved songs live in Minim's private storage, so clearing the cache keeps them. Find them under **Offline** in Library, and see the space they use, or remove them all, in Settings. Nothing is saved automatically.
- **Player motion (5.5.0):** the mini player turns into Now Playing instead of being swapped for it. Swipe up on the pill and it opens as far as you drag; let go past a third, or flick, and it springs open, otherwise it springs back. Swipe down on Now Playing (anywhere but the seek bar), or press Back, and it shrinks back into the pill. Liking a song squishes and bounces the heart, fills it from the centre and bursts six small dots in the cover's colour; double-tap the cover to like it with a big floating heart (when the cover's double tap is set to Like in Settings). The seek bar thickens and grows a handle under your finger, shows a time bubble while you drag and dips the sound softly when you let go. The search pill stretches up into the search bar and goes back into the pill on Back.
- **Up next and Settings motion (5.5.1):** tap the Up next pill on Now Playing and it stretches up into the Up next screen; swipe it down, or press Back or ×, and it shrinks back into the pill. Hold a song's ≡ handle to drag it to a new place, or tap × to remove it. Up next starts at the song that's playing, which stays pinned at the top; Previous still goes back to songs you've played. Tap your profile picture and Settings grows out of it, then shrinks back into it on Back.
- **Festival themes (5.5.2):** during a festival, Home gets a full-screen wallpaper (right behind the clock and the navigation bar), colours picked from that wallpaper, the festival's own greetings and a festive like burst. Every other screen keeps the cover's colours. Themes come from the [`themes`](themes) folder in this repo, so a new festival needs no update. Diwali runs from 1 to 10 November 2026. To see a theme early, tap the version number in Settings 7 times.
- **Updates in the app:** when Minim opens or comes back after a few hours, it checks this repo's latest release and offers to download and install a newer version. You can also check any time in Settings.
- **Library:** liked songs, playlists, recents, songs saved offline, a song menu on long-press (play next, add to queue, add to playlist, save offline, go to artist, share) and a playlist menu on long-press (save offline, delete).

## Privacy

Signing in is optional. Minim only gets your name, profile picture and email. It never sees your password and can't access your Gmail, Drive, contacts or anything else in your Google account. Sync stores only song IDs, song details (title, artist, cover link, length) and times under your own account, and **Delete account** in Settings removes all of it.

**Gemini chat:** chat messages go to Google Gemini; on the free tier Google may use them to improve its models. Only what you type, the song you attach and what Gemini asks Minim for (song details, lyrics, your likes, recent songs and listening stats) are sent. The chat isn't saved and is wiped when you close the app. Your own API key, if you add one, is encrypted on your phone, left out of Android backups, and never synced or logged.

## Download

Get the latest APK from the [Releases](https://github.com/UniquelyGaurab/minim/releases) page. What changed in each version is in [CHANGELOG.md](CHANGELOG.md).

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

The Gemini chat's built-in route uses Firebase AI Logic in the same project (step 4 in FIREBASE_SETUP.md). Without it, or without Firebase at all, the chat still works with your own key from [Google AI Studio](https://aistudio.google.com/apikey), added in Settings → Gemini. If your Firebase project enforces App Check on Firebase AI Logic, each phone needs its debug token added: copy it from Settings → Gemini → Your device token, then in the Firebase console go to App Check → Apps → your app → Manage debug tokens → Add. The token is kept on the phone only, never synced, backed up or logged.

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

Home greetings can be added without a new build. Minim checks [`greetings/greetings.json`](greetings/greetings.json) in this repo every 10 minutes while it's open (5.1.0 and newer; older versions about once a day), and keeps a copy for offline use. New lines join the rotation without a restart. If the file is broken it is ignored and the last good copy carries on.

To edit it from your phone:

1. Open this repo on github.com in your browser and sign in.
2. Tap the `greetings` folder, then `greetings.json`.
3. Tap the pencil icon (Edit). If you don't see it, switch to the desktop site in your browser's menu.
4. Make your change, tap **Commit changes**, and commit straight to `main`.

Phones on 5.1.0 pick it up within about 10 minutes while Minim is open; older versions within about a day.

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

Rules that keep the file working: keep `"version": 2` at the top, put a comma between greetings but not after the last one, and use straight double quotes. A greeting with a mistake in it (no text, too long, a date that doesn't exist like `02-30`) is skipped and the rest still work. A mistake in the file's structure, like a missing bracket, makes Minim ignore the whole new file and keep the last good copy. Pasting the file into [jsonlint.com](https://jsonlint.com) before committing catches those.

### Listening greetings (5.1.0)

Lines about your listening go in a separate `"stats"` list after `"greetings"`. Versions before 5.1.0 don't read it, so they never show a line with an unfilled placeholder.

```json
"stats": [
  { "text": "{song}, again?\nAt this point it pays rent.", "min": { "plays": 3 } },
  { "text": "{song} at this hour?\nNo judgement. Okay, a little.", "hours": "0-4" }
]
```

- Placeholders for words: `{song}` and `{artist}` (what's in the player), `{last}` and `{lastartist}` (the song before), `{topsong}` and `{topartist}` (most played this week), `{likedsong}` (newest like), `{likedartist}`, `{playlist}` (newest), `{bigplaylist}` (biggest) and `{weekday}`.
- Placeholders for numbers: `{plays}` (plays of the current song this week), `{today}`, `{liked}`, `{topsongplays}`, `{topartistplays}`, `{artists}` (this week), `{likedartistsongs}`, `{playlists}` and `{bigplaylistsongs}`. A line that uses a number needs at least 1 unless `min` says otherwise.
- A line only shows when every placeholder in it has data. `{name}` works here too.
- Conditions (all optional, every one that's set must hold): `min` and `max` (for example `{ "plays": 5 }`), `needs` (placeholders that must have data even if the text doesn't use them), `hours` (`"22-3"` wraps past midnight, or a single hour like `8`), `days` (`["sat", "sun"]`), `playing` (`true` while a song plays, `false` while paused) and `liked` (whether the song in the player is liked).
- A line with an unknown placeholder or a broken condition is skipped, and the rest still work.

## Festival themes

Festival wallpapers, Home colours, greetings and like bursts live in the [`themes`](themes) folder, so a new festival is a file swap, not an app update. [`themes/README.md`](themes/README.md) shows how to add one from GitHub's website. Phones use them from Minim 5.5.2 on.

## Credits

- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) for search and streams (GPL-3.0)
- [LRCLib](https://lrclib.net) for synced lyrics
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) for playback
- [OkHttp](https://github.com/square/okhttp) for networking
- [Firebase](https://firebase.google.com) Authentication and Cloud Firestore for the optional sign-in and sync
- [Google Gemini](https://ai.google.dev) (Gemini API and Firebase AI Logic) for the chat
- [Poppins](https://fonts.google.com/specimen/Poppins) font (SIL Open Font License)
- [Simple Icons](https://simpleicons.org) for the one-colour Spotify, YouTube Music and YouTube logos (CC0; the logos are their owners' trademarks)

From the app's own credits: designed by Gaurab, built by Charlie (Sesame AI), early code by Google Gemini.

Minim is not affiliated with YouTube or Google.

## License

Minim is free software under the [GNU General Public License v3.0](LICENSE).

Author: [UniquelyGaurab](https://github.com/UniquelyGaurab)
