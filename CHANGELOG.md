# Changelog

Every Minim release, newest first. Download any of them from the [Releases](https://github.com/UniquelyGaurab/minim/releases) page.

## 5.5.2 (2026-10-30)

- **Festival themes:** during a festival, Home gets a full-screen wallpaper, colours taken from it, the festival's own greetings and a festive like burst. First up is **Diwali, 1 to 10 November**, with gold and orange sparks when you like a song
- **Bass boost:** a new row in Settings under Volume boost, with Off, Warm, Punchy and Heavy. It brings the kick and bassline forward without muddying voices, and won't crackle even with Volume boost on Loud
- **Diwali greetings:** during Diwali only the Diwali lines rotate, every 7 seconds, and the first one you see matches the time of day. Diwali day (8 November) has its own lines, and the top artist takeover gets Diwali lines too
- **Wallpaper edge to edge:** on Home the wallpaper goes right behind the clock and battery and behind the navigation bar, and those icons turn light or dark to stay readable
- **Other screens keep their colours:** Settings, Now Playing, Search, Artist pages and lists opened from Home show their own colour behind the clock and navigation bar, fading in as they open, so the wallpaper never flashes through
- **Preview a festival early:** in Settings, tap the version number 7 times to turn the next festival on now. Long-press the version during the preview to see the festival day's lines. Tap 7 times again to turn it off

Themes come from the `themes` folder in this repo, so new festivals arrive without an update. If a theme can't be downloaded, Home stays as it is and the usual greetings carry on.

## 5.5.1 (2026-10-28)

- **Mini player to Now Playing:** the pill now turns into Now Playing. Swipe up and it follows your finger, swipe down or press Back and it shrinks back into the pill
- **Up next:** the pill stretches into the Up next screen. Hold ≡ to drag a song to a new place, tap × to remove it. Up next now starts at the song that's playing
- **Heart:** liking a song bounces the heart and bursts small dots in the cover's colour. Double-tap the cover to like it with a big floating heart (when double tap is set to Like in Settings)
- **Settings** grows out of your profile picture and shrinks back into it
- **Seek bar:** it thickens under your finger, shows a time bubble while you drag and dips the sound softly when you let go
- **Search:** the search pill stretches up into the search bar and goes back into the pill on Back

This release includes everything from 5.5.0 (the mini player morph, heart, seek bar and search animations) and 5.5.1 (the Up next morph, drag to reorder, and Settings from your profile picture).

**Good to know:** Up next now starts at the song that's playing, so songs you've already played aren't listed there anymore. Previous still goes back to them.

## 5.4.1 (2026-10-27)

- The **Spotify, YouTube Music and YouTube logos** under imported playlists now come out the same size: the same icon height, and letters as tall as the "from" next to them
- **Save offline** is now in the Now Playing menu (the three dots, top right), right under Add to playlist. It shows a small ring while the song saves, then changes to Remove from offline

## 5.4.0 (2026-10-27)

- **Save offline:** long-press a song, a playlist or Liked Songs and tap Save offline. Songs save in the best quality YouTube offers, with their lyrics and cover, on Wi-Fi or mobile data, and play with no signal. Find them under **Offline** in Library
- Long-pressing a playlist now opens a small menu (Save offline, Delete playlist) instead of going straight to delete
- Settings shows how much space offline songs use, with **Remove all**
- The **Download Now** button in the update pop-up is now a solid pill that stands out on any colour
- Updates keep downloading on mobile data when you leave the app, and pick up where they stopped if the signal drops
- Imported playlists show the full **Spotify, YouTube Music or YouTube logo**, in the app's colours

## 5.3.2 (2026-10-27)

- **+ Add songs** on every playlist: search, tap + to add, and keep going. Songs already in the playlist show a tick, and with an empty search box you get your recent and liked songs
- **Rename playlists:** tap a playlist's name, type the new one and tap Done (Back keeps the old name)
- Imported playlists now show a small **Spotify, YouTube Music or YouTube logo** under the name, in the app's colours
- **Built-in Gemini:** Settings → Gemini now shows your device token with a Copy button. Once it's added in the Firebase console, built-in Gemini works on that phone. Until then the chat says the phone isn't approved yet and offers your own key

## 5.3.1 (2026-10-26)

- Fixes the crash when importing a Spotify playlist or a list of songs (seen on Android 9)
- Whatever goes wrong during an import now ends as a failed import on the Library card, never a crash

What happened: one of the patterns Minim uses to tidy song titles (dropping things like "(Live)" or "[Official Video]" before searching) was written in a way that Android's text engine refuses, even though it worked in testing. Every Spotify and song-list import hit it. YouTube playlist imports didn't use it, so they kept working.

- That pattern is fixed, and every other pattern in the app (lyrics, greetings, update notes, Gemini settings, links) was checked against Android's engine and tidied the same way.
- A new test checks every pattern in the app against Android's engine, so this can't slip through again.
- If an import still fails for any reason, tap the card to see why.

## 5.3.0 (2026-10-26)

- Import playlists from YouTube, YouTube Music, Spotify or a list of songs
- Share a playlist link to Minim to import it
- Review the matches before saving, and pick another version for any song
- Swiping in the Gemini chat no longer opens Discover

New in Library: tap **Add playlist**, then **Import Playlist**.

- Paste a YouTube or YouTube Music playlist, album or mix link, a Spotify playlist or album link (spotify.link short links too), or a plain list of songs, one per line. The line under the box says what Minim found.
- Or share a playlist link from Spotify or YouTube Music and pick Minim. The import box opens with the link filled in.
- Spotify playlists are read without a login or key, up to 1,000 songs. Private playlists need to be made public first.
- The import runs in the background with a live count on Library, so you can keep listening. Tap the card when it's done, or ✕ to cancel it.
- On the review screen, songs Minim isn't sure about are marked. Tap one to pick another version or leave it out, then Save. It becomes a normal playlist with "from Spotify" (or YouTube, or your list) under its name.
- While the Gemini chat is open or closing, a swipe up on Home no longer opens Discover.

## 5.2.1 (2026-10-25)

- Gemini replies no longer freeze faded out or go missing
- The chat's input bar drops back down when the keyboard closes
- Taps on the mini player, tabs and Settings work after closing the chat
- A calmer thinking animation: the sparkle breathes instead of spinning

Fixes for the Gemini chat from 5.2.0:

- Replies always finish fully visible and complete, even when they stream in fast, when you close and reopen the chat, or when Minim goes to the background.
- After you close the keyboard (with Back or by tapping outside it), the input bar goes back to the bottom of the sheet. Tapping anywhere in the chat outside the input bar now puts the keyboard away.
- A closed chat can no longer stay on screen invisibly and swallow your taps, even if you close it the instant it opens.
- New chat in the middle of a reply no longer lets the old reply leak into the new chat.
- If Gemini opens an artist page after you've already closed the chat, it no longer pulls you away from the screen you went to.
- The sheet keeps the right size when the screen turns.

## 5.2.0 (2026-10-25)

- Ask Gemini: a chat that finds, plays and queues songs for you
- Open it from Ask Gemini on Home or Ask on Now Playing
- It can translate lyrics, roast your stats and build playlists
- Pick the model and add your own free key in Settings

The chat can search and play songs, add them to Up next, like the song that's playing (with Undo), open artist pages, explain or translate the lyrics of the current song, look at your recent songs, liked songs and listening stats, and put together a playlist you can rename and trim before saving. Replies are short, in any language. The chat is kept while Minim is open and wiped when you close it.

Settings → Gemini has the model (Gemini 3.5 Flash-Lite by default, Gemini 3.1 Flash-Lite, or Custom for any model ID from Google AI Studio) and a place to add your own free key from https://aistudio.google.com/apikey. The key is encrypted on your phone, left out of Android backups, and never synced or logged.

Privacy: chat messages go to Google Gemini; on the free tier Google may use them to improve its models.

## 5.1.0 (2026-10-25)

- Tap any line in full screen lyrics to jump the song there
- Home greetings change every 7 seconds, with 88 new lines
- New greetings about your listening, like your most played song
- New greetings arrive from GitHub without an update

In full screen lyrics, a tap jumps to that line (your Lyrics timing setting is taken into account) and a double tap still plays or pauses.

Apart from the time-of-day greetings, every Home greeting now comes from greetings/greetings.json in this repo. Minim checks it every 10 minutes while it's open, and new lines join without a restart. Listening greetings only show when there's something to say, so play a song a few times to see them.

## 5.0.0 (2026-10-25)

- Sign in with Google (optional) from Settings to back up your music
- Likes, playlists, stats and history sync across your phones
- Changes made offline sync when you're back online
- Includes the 4.9.10 crash fix and crash report card

Signing in is optional and Minim works the same without it. Your first sign-in merges this phone's music with your account, so nothing is lost. If you sign in with a different Google account, Minim asks whether to Merge, Start fresh or Cancel.

Minim only gets your name, profile picture and email. It never sees your password and can't access your Gmail, Drive, contacts or anything else in your Google account.

## 4.9.10 (2026-10-25)

- Fixes a crash in 4.9.9 when a song has no lyrics ("Lyrics not available"), which could close Minim on opening or after playing a song
- If Minim ever crashes again, the next time you open it a small card shows the crash report with a Copy button

## 4.9.9 (2026-10-24)

- Flow on the Now Playing lyric line, with narrower lines that slide
- Colours fade smoothly from one song to the next
- Smooth tab switches with a gliding underline
- Lists slide in, Up next glides and covers fade in
- Pills respond when you press them, and Stats numbers count up

## 4.9.8 (2026-10-24)

- Sharper HD artwork everywhere
- Update pop-up now shows up on its own when a new version is out
- Up Next pill on Now Playing replaces swipe up and hold
- Tap the lyric line for full screen lyrics over a blurred cover
- Flow: word by word lyrics, with a Lyrics style setting (Flow or Line)
- "Lyrics not available" when a song has no lyrics

## 4.9.7 (2026-10-24)

- Home player buttons now sit in three circles below the song
- Up next uses the same clean rows as search and playlists
- New menu on Now Playing: add to playlist, artist, share, sleep timer
- Settings pills no longer get cut off at the top
- Music pauses before an update installs

## 4.9.6 (2026-10-23)

- Artist photos are perfectly round everywhere now, no more cut edges
- Songs in search and playlists sit in clean new filled rows
- Festival greetings for Bihu, Diwali, Eid and more, fresh from GitHub
- Minim now tells you when an update is out and installs it for you
