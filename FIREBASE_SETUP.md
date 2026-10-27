# Firebase setup for Minim

Minim's Google sign-in and sync (from 5.0) run on Firebase. Signing in is optional: without it, Minim
works exactly the same, and builds without Firebase simply hide the Account section in Settings.

Minim only asks Google for the basic profile: name, profile picture and email. No passwords, no Gmail,
Drive or contacts.

## 1. Create the Firestore database (once)

Skip this if Firestore already shows a database.

1. Open the Firebase console and pick the project.
2. In the left menu, open **Build → Firestore Database**.
3. Tap **Create database**.
4. Location: **asia-south1 (Mumbai)**. This can't be changed later.
5. Choose **Start in production mode** and tap **Create**.

## 2. Paste the security rules

The rules make sure each person can only read and write their own data, under `users/{their id}`, and
that the app config under `config/` can be read by anyone but changed by no one from the app.

1. In **Firestore Database**, open the **Rules** tab.
2. Delete everything in the editor.
3. Paste the whole contents of `firestore.rules` from this project.
4. Tap **Publish**.

## 3. Turn on Google sign-in (once)

**Build → Authentication → Sign-in method → Google → Enable → Save.**

## 4. Turn on built-in Gemini for the chat (optional, 5.2.0)

The Gemini chat can run without anyone adding their own key, through Firebase AI Logic.
**Build → AI Logic → Get started → choose the Gemini Developer API → Enable.** It's free to start.
Until this is on, the chat says built-in Gemini is off and asks for a free key from Google AI Studio
(Settings → Gemini → Add key). The app finds your project from `google-services.json`, so there is
nothing to put in the code. Free-tier chat messages may be used by Google to improve its models.

**If App Check is enforced on AI Logic (5.3.2):** Minim uses App Check's debug provider. Each phone
makes its own debug token; copy it from Settings → Gemini → Your device token (Copy), then in the
Firebase console open **App Check → Apps → your app → Manage debug tokens → Add**, paste it and save.
Until a phone's token is added, the chat says the phone isn't approved yet and offers your own key
instead. The token stays on the phone: it's never synced, backed up or logged.

## What Minim stores in the cloud

Sync only runs while you're signed in. Everything lives under `users/{uid}`, where `{uid}` is your
Firebase sign-in ID, so the rules above cover it as they are. Settings never leave the phone, and
nothing personal (email, contacts, location) is stored: only song IDs, the song's title, artist,
cover link and length, and times. Delete account removes all four collections, then the sign-in.

Every document also gets `u`, the server time of its last write. A phone reads only the documents
whose `u` is newer than its last pull (`u > marker`, ordered by `u`, 300 at a time). That query only
needs Firestore's automatic single-field index, so there's nothing to set up.

Song details are packed into one string with fields separated by the unit separator character
(U+001F, shown here as ␟): `title␟artist␟cover link␟length in seconds`. Times are milliseconds since 1970.

| Path | What it holds |
| --- | --- |
| `users/{uid}/likes/{songId}` | `l`: liked (true) or unliked (false). `at`: when that last changed. `m`: song details. |
| `users/{uid}/hidden/{songId}` | A song marked "Not interested". `at`: when. `m`: song details. Only ever added. |
| `users/{uid}/playlists/{playlistId}` | `n`: name. `c`: created. `h`: last change to the name or delete flag. `b`: when it was made again after a delete. `del`: deleted. `t`: a map of songId → `position␟changed␟removed(1/0)␟song details`. |
| `users/{uid}/history/{yyyyMMdd}` | One document per day. `d`: the day. `p`: plays, a map of playId → `songId␟started␟ms heard␟tracked(1/0)`. `s`: songId → song details. `l`: listening time, a map of phone key → {`hour_songId`: ms}. `r`: Recents, a map of phone key → {songId: last played}. |

IDs:

- `playlistId` is `pl_` plus the first 20 hex characters of the SHA-1 of the trimmed playlist name, so
  the same playlist name on two phones is the same playlist.
- `playId` is the start time in base 36, `_`, then the songId. The same play gets the same ID on any
  phone, so it's never counted twice.
- The phone key is `d` plus 12 hex characters of a SHA-1 hash of Android's per-app device ID. It only
  tells your phones apart and can't be traced back to a phone.

How merging works: for likes and playlist songs, the newest change wins, and a tie goes to unliked or
removed. A deleted playlist beats older changes, and a playlist made again after a delete starts empty.
Plays are merged by ID. Listening time is kept per phone, and merging takes the higher number.

Rough use: under 100 writes on a heavy listening day, a few reads each time the app opens, plus one
read per changed document. A first sign-in or a new phone reads each document once. That's far
inside Firestore's free tier.

## Festivals, announcements and switches (config/app)

Minim reads one document, `config/app`, to change its look and messages without an app update. It
needs no sign-in, Minim never writes to it, and it isn't part of anyone's account (Delete account
never touches it). The rules above already allow reading it; nothing else needs publishing.

The document is optional. If it doesn't exist, can't be read (offline, rules) or has a mistake in it,
Minim simply shows no festival or announcement. A broken entry is skipped and the rest still work.
Minim reads it each time the app opens (at most every 30 minutes), and keeps an offline copy.

To create it: Firestore Database → Data → Start collection → Collection ID `config` → Document ID
`app`, then add the fields below. Every field is optional.

- **switches** (map of booleans). A missing switch counts as on.
  - `festivals`: festival banners, colours and greetings
  - `announcements`: announcement sheets
  - `greetings`: the extra greetings in this document
  - `sync`: cloud sync. Off pauses all syncing; changes wait on each phone and go up when it's back on.
  - `update_prompt`: the pop-up that offers a new version (Settings → Check for updates still works)
- **events** (array of maps), one per festival:
  - `id` (string, letters, digits, `-`, `_` or `.`; made from `name` if missing)
  - `name` (string, up to 40 characters)
  - `from`, `to` (strings): `MM-DD` repeats every year, `YYYY-MM-DD` is that year only. A range can
    cross the new year (`12-31` to `01-01`). A Firestore timestamp also works. `to` defaults to `from`.
  - `accent` (string `#RRGGBB`): the app's colour while nothing is playing, with Colours set to
    Dynamic. Default B&W stays black and white, and a playing song's colours always win.
  - `banner` (string, up to 60 characters): a small pill under the search pill on Home. It has a close
    button; closed, it stays closed for that festival this year.
  - `link` (string, `https://` only): what tapping the banner opens.
  - `greetings` (array of strings): Home greetings during the festival. A line break splits it into
    the greeting's two lines; without one, Minim splits it near the middle itself.
- **announcements** (array of maps). Each one shows once, as a sheet on Home:
  - `id` (string, required; change it to show a new announcement)
  - `title` (string, up to 60 characters, required)
  - `text` (string, up to 400 characters; blank lines are kept)
  - `link` (string, `https://` only) and `button` (its label, up to 24 characters; "Open" if missing)
  - `from`, `to` (same date format as events): only shown between these days
  - `min_version`, `max_version` (string like `5.0.0`, or a number like `27` for the version code):
    only versions inside these see it
- **greetings** (array): extra Home greetings, the same as greetings.json entries: a string
  `"Line one\nLine two"`, or a map with `text` and optional `from`, `to`, `day`, `year`.
- **update_lock** (map): `min_version` (`"5.0.0"` or a version code) and optional `message`. Versions
  older than this get the update pop-up with no Later button; it can't be closed until they update.
  To keep anyone from getting stuck, the lock only shows when GitHub really has a newer release. If
  GitHub has nothing newer or can't be reached, there's no lock that time.

Example (as JSON; in the console each key is a field of the matching type):

```json
{
  "switches": { "festivals": true, "announcements": true },
  "events": [
    {
      "id": "bohag-bihu",
      "name": "Bohag Bihu",
      "from": "04-13",
      "to": "04-15",
      "accent": "#E8A33D",
      "banner": "Happy Bohag Bihu!",
      "greetings": ["Happy Bihu!\nDhol on, volume up."]
    }
  ],
  "announcements": [
    {
      "id": "sync-is-here",
      "title": "Your music, on every phone",
      "text": "Sign in with Google in Settings to sync likes, playlists and stats.",
      "min_version": "5.0.0"
    }
  ]
}
```

## Building Minim yourself with your own Firebase project

1. Create a Firebase project, then add an **Android app** with the package name `com.minim.music.debug`
   (debug builds) and/or `com.minim.music` (release builds).
2. Add your signing key's **SHA-1** to that Android app (Project settings → Your apps). Google sign-in
   fails without it. `./gradlew signingReport` prints it.
3. Download **google-services.json** and put it in `app/`. It's in `.gitignore`: never commit it.
   `app/google-services.json.example` shows what it looks like.
4. Do steps 1 to 3 above in your project (and step 4 if you want built-in Gemini).

Without `app/google-services.json`, the build skips the Google services plugin and the app runs with
the Account section hidden.
