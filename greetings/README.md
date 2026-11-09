# Editing greetings

Home greetings can be added without a new build. Minim checks [`greetings.json`](greetings.json) in this repo every 10 minutes while it's open (5.1.0 and newer; older versions about once a day), and keeps a copy for offline use. New lines join the rotation without a restart. If the file is broken it is ignored and the last good copy carries on.

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

## Listening greetings (5.1.0)

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

Festival greetings that come with a theme live in the [`themes`](../themes) folder instead; see [`themes/README.md`](../themes/README.md).
