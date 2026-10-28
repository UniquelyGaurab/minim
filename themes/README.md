# Festival themes

During a festival, Minim can dress up Home with four things from this folder, with no app update:

- **Dates:** when the festival starts and ends
- **Greetings:** Home greetings with the festival's flavour
- **Wallpaper:** a picture behind Home, with Home's colours taken from it
- **Like burst:** what bursts out of the heart when you like a song

Everything lives in [`themes.json`](themes.json), plus one folder per festival for its wallpaper and greetings, like [`themes/diwali/`](diwali/).

**Live now:** Diwali, 1 to 10 November 2026, with its own [greetings file](diwali/greetings.json) and special lines on 8 November.

**Heads-up:** phones only use themes once they have the Minim update that reads this folder. Until then these files do nothing.

## Add a festival from your phone

Open the repo on github.com in your browser and sign in. If you don't see **Add file** or the pencil icon, switch to the desktop site in your browser's menu.

**1. Make the festival's folder.** GitHub can't make an empty folder, so you make it with a small note:

1. Tap the `themes` folder, then **Add file** → **Create new file**.
2. In the name box type `holi-2027/notes.md`. Typing the `/` turns `holi-2027` into a folder.
3. Write anything in the note, like "Holi 2027", then tap **Commit changes** and commit straight to `main`.

**2. Upload the wallpaper.**

1. Name the picture `wallpaper.webp` on your phone first (see [Wallpaper](#wallpaper) for the size). If you can't rename it, keep its name, as long as it has no spaces and ends in `.webp`, and use that name in the `wallpaper` path in step 3.
2. Open `themes/holi-2027`, tap **Add file** → **Upload files**, pick the picture, and commit to `main`.

**3. Add the festival to `themes.json`.**

1. Open `themes/themes.json` and tap the pencil icon.
2. Copy the whole template entry: from the `{` just above `"id": "_template"` down to its closing `}`.
3. Paste it on a new line right after `"festivals": [`, and put a comma after the `}` of what you pasted. New festivals go on top, and the template stays last with no comma after it.
4. In your copy, change:
   - `id`: short, lowercase, with hyphens, and the same as the folder name: `holi-2027`
   - `name`: the name you'd say, like `Holi`
   - `enabled`: change `false` to `true`, or delete the line
   - `start` and `end`: the first and last day, as `YYYY-MM-DD`. Both days count. For one day, use the same date twice.
   - `wallpaper`: `themes/holi-2027/wallpaper.webp`
   - `burst`: the shape and colours (see below)
   - `greetings`: your festival lines, written right in the entry or as a path to their own file (see [Edit a festival's greetings](#edit-a-festivals-greetings))
5. Tap **Commit changes** and commit to `main`.

After a minute, a green tick next to your commit means everything checks out. A red X means something would be skipped: tap it, then **Details**, to see which line and why.

A finished entry looks like this:

```json
{
  "id": "christmas-2026",
  "name": "Christmas",
  "start": "2026-12-24",
  "end": "2026-12-26",
  "priority": 0,
  "wallpaper": "themes/christmas-2026/wallpaper.webp",
  "colors": {},
  "burst": { "shape": "snow", "colors": ["#FFFFFF", "#D6ECFF"] },
  "greetings": {
    "anytime": [
      { "text": "Merry Christmas, {name}!\nSleigh it." },
      { "text": "Cosy blanket,\ncosy playlist." }
    ],
    "stats": [
      { "text": "{song} by the tree?\nGood call.", "playing": true }
    ]
  }
},
```

Rules that keep the file working: straight double quotes, a comma between entries but not after the last one, and `"version": 1` stays at the top.

## The fields

- `id` (required): lowercase letters, numbers and hyphens, unique. An id starting with `_` is a template and never shows.
- `name` (required): the festival's name.
- `enabled` (optional): `false` switches a festival off without deleting it. Leaving it out means on.
- `start` and `end` (required): `YYYY-MM-DD`, both days included, read in the phone's own date (IST in India). Festivals that move, like Diwali, Holi and Eid, need new dates each year: update `start` and `end` (and the special `days`), or add a new entry with the year in its id.
- `priority` (optional, default 0): when two festivals overlap, the higher number wins. A tie goes to the one higher up in the list. Only one festival is on at a time.
- `wallpaper` (optional): the path to the picture, starting with `themes/`.
- `colors` (optional): backup colours for Home, as `#RRGGBB`. Leave it as `{}` and Minim picks the colours from the wallpaper. Only fill it in if the automatic colours look off. Any colour you set replaces the automatic one:
  - `text`: the greeting, the minim logo and the top tab labels
  - `pill`: the search pill
  - `pillText`: the text and icons on the search pill
- `burst` (optional):
  - `shape`: one of `dots`, `sparks`, `petals`, `snow` or `hearts`. Anything else becomes `dots`.
  - `colors`: 1 to 6 colours as `#RRGGBB`, used in turn. Leave it out to use the cover's colour, like today.
- `greetings` (optional): either the lines themselves (see [Greetings](#greetings)) or the path to the festival's own greetings file, like `"greetings": "themes/diwali/greetings.json"` (see [Edit a festival's greetings](#edit-a-festivals-greetings)).

Minim ignores any field it doesn't know, so you can add your own notes.

## Greetings

They work just like [`greetings/greetings.json`](../greetings/greetings.json), in the same two groups:

- `anytime`: plain lines
- `stats`: lines about the listening, using placeholders

Every line has a `text`, where `\n` splits the two lines. Keep each line under 40 characters (80 in total).

- `{name}` is the saved name. A line that uses it only shows when a name is set.
- Word placeholders: `{song}`, `{artist}`, `{last}`, `{lastartist}`, `{topsong}`, `{topartist}`, `{likedsong}`, `{likedartist}`, `{playlist}`, `{bigplaylist}` and `{weekday}`.
- Number placeholders: `{plays}`, `{today}`, `{liked}`, `{topsongplays}`, `{topartistplays}`, `{artists}`, `{likedartistsongs}`, `{playlists}` and `{bigplaylistsongs}`.
- Conditions work too: `min`, `max`, `needs`, `hours`, `days`, `playing` and `liked`. The main [README](../README.md#listening-greetings-510) explains each one.
- Don't add `from`, `to`, `day` or `year`. The festival's own dates apply to all its lines.

While the festival is on, only its lines rotate. The regular, time-of-day and listening greetings pause. Use `hours` for morning or night lines, like `"hours": "5-11"`. Put a good number of `anytime` lines in, because `stats` lines only show when their data is there. If none of the festival's lines can show at that moment, or it has none, the regular greetings carry on.

## Edit a festival's greetings

A big festival gets its own file, like [`diwali/greetings.json`](diwali/greetings.json), so `themes.json` stays short. Its entry points at it with `"greetings": "themes/diwali/greetings.json"`.

**To change a line from your phone:** open the file on github.com, tap the pencil icon, edit the text between the quotes, then **Commit changes** to `main`. Phones pick it up the next time Minim checks (about every 10 minutes while it's open). A green tick next to your commit means every line is fine.

The file has four groups:

```json
{
  "version": 1,
  "anytime": [
    { "text": "Good morning.\nRangoli first, playlist second.", "hours": "5-8" },
    { "text": "Soan papdi incoming.\nRegift responsibly." }
  ],
  "stats": [
    { "text": "{song} hits harder\nthan a sutli bomb." }
  ],
  "days": [
    { "date": "2026-11-08", "greetings": [ { "text": "Happy Diwali, {name}!\nLight it up." } ] }
  ],
  "topArtist": [
    { "text": "{topartist} is today's\nbrightest diya." }
  ]
}
```

- `anytime`: plain lines. Add `"hours"` for time-of-day lines. The hours are whole hours and both ends count, so 5 to 9am is `"5-8"` (5:00 to 8:59), and 10pm to 1am is `"22-0"`. While the festival is on, the first greeting when Minim opens is one of these time-of-day lines.
- `stats`: lines with listening placeholders like `{song}`, `{artist}`, `{topsong}` or `{today}`. Each one only shows when its data is there, for example `{song}` only while something is playing.
- `days`: lines for one special day only, like Diwali itself. The `date` must be inside the festival's dates. On that day they come up every third greeting.
- `topArtist`: the lines for the top artist takeover, the photo that fills Home once you've played one artist a lot that day. Use `{topartist}` for the artist, and optionally `{name}` or `{minutes}` (today's minutes). No other placeholders work here. During a festival the takeover lasts 7 seconds instead of 10.

The same rules as above apply: `\n` is the line break, each line under 40 characters, a line with `{name}` only shows when a name is saved, and no `from`, `to`, `day` or `year`. Keep `"version": 1` at the top. If the file is broken or never downloads, the regular greetings keep running, so Home is never blank.

**Preview without waiting:** in Minim, open Settings and tap the version number 7 times. That turns on the next festival right away, so you can see its wallpaper and greetings. Long-press the version during a preview to see the special day's lines today. Tap 7 times again to turn the preview off.

## Wallpaper

- **Size:** 1080 × 2400 (9:20). A 9:16 picture, which most AI image tools make, is fine too. Minim scales it to fill the screen, which trims a little off each side.
- **File:** WebP, under 300 KB. Minim won't download one over 1 MB. If you have a PNG or JPG, convert it to WebP first. Renaming the file doesn't convert it.
- **Layout:** keep the top half calm, because the greeting, the minim logo, the tabs and the search pill sit there. Put the busy parts, like diyas, rangoli or lights, in the bottom third. Keep faces and details at least 80 px from the edges.

[`_template/layout-guide.png`](_template/layout-guide.png) shows these zones on a sample wallpaper.

**AI prompt tip:** describe the layout, not just the festival. For example:

> Vertical phone wallpaper, 9:16, Diwali night. Top half: calm, plain, softly glowing deep blue sky with nothing in it. Bottom third: rows of glowing diyas and a rangoli, warm gold and orange light. Soft and slightly blurred so text on top stays readable. Nothing important near the edges. No text, no logos, no people.

## How Minim uses it

- Minim checks `themes.json` the same way it checks the greetings file, and keeps the last good copy for offline use.
- About 2 days before a festival starts, it downloads the wallpaper and keeps it on the phone. If the festival has already started, it downloads it as soon as it can.
- During the festival's dates, only Home takes its colours from the wallpaper: the greeting, the minim logo, the top tabs and the search pill. Text, the clock and the battery icons turn light or dark depending on how bright the wallpaper is. The Home mini player pill keeps the song's colours, because it grows into Now Playing. Every other screen keeps the album colours, and the wallpaper fades out when you switch tabs.
- Every like during the festival uses its burst instead of the six dots.
- If anything is broken or missing, Minim falls back: no wallpaper or a bad one means normal Home, a bad burst means the usual dots, and no greetings means the regular ones. If the whole file is broken, Minim keeps the last good copy, or normal Home if it has none.

## For developers

- The app fetches `https://raw.githubusercontent.com/UniquelyGaurab/minim/main/themes/themes.json`, and each wallpaper and greetings file from the same base plus its path (`themes/<folder>/<name>.json`, at most 128 KB).
- [`themes.schema.json`](themes.schema.json) describes the format (JSON Schema 2020-12). Unknown fields are allowed. The app reads `"version": 1`, and treats a higher version as a change it can't read.
- [`check_themes.py`](check_themes.py) runs the schema plus extra checks (real dates, unique ids, known placeholders, line lengths, the wallpaper's presence, size and dimensions, and each festival's greetings file: its four groups, special days inside the festival, and the takeover placeholders). The greetings file format is `$defs/greetingsFile` in the schema. Run it with `python3 themes/check_themes.py`. GitHub runs it on every change under `themes/` (`.github/workflows/themes.yml`).
- This is separate from the Firestore `events` config in [FIREBASE_SETUP.md](../FIREBASE_SETUP.md).
