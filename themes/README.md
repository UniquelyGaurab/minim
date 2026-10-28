# Festival themes

During a festival, Minim can dress up Home with four things from this folder, with no app update:

- **Dates:** when the festival starts and ends
- **Greetings:** Home greetings with the festival's flavour
- **Wallpaper:** a picture behind Home, with Home's colours taken from it
- **Like burst:** what bursts out of the heart when you like a song

Everything lives in [`themes.json`](themes.json), plus one folder per festival for its wallpaper, like `themes/diwali-2026/wallpaper.webp`.

**Heads-up:** phones only use themes once they have the Minim update that reads this folder. Until then these files do nothing.

## Add a festival from your phone

Open the repo on github.com in your browser and sign in. If you don't see **Add file** or the pencil icon, switch to the desktop site in your browser's menu.

**1. Make the festival's folder.** GitHub can't make an empty folder, so you make it with a small note:

1. Tap the `themes` folder, then **Add file** → **Create new file**.
2. In the name box type `diwali-2026/notes.md`. Typing the `/` turns `diwali-2026` into a folder.
3. Write anything in the note, like "Diwali 2026", then tap **Commit changes** and commit straight to `main`.

**2. Upload the wallpaper.**

1. Name the picture `wallpaper.webp` on your phone first (see [Wallpaper](#wallpaper) for the size). If you can't rename it, keep its name, as long as it has no spaces and ends in `.webp`, and use that name in the `wallpaper` path in step 3.
2. Open `themes/diwali-2026`, tap **Add file** → **Upload files**, pick the picture, and commit to `main`.

**3. Add the festival to `themes.json`.**

1. Open `themes/themes.json` and tap the pencil icon.
2. Copy the whole template entry: from the `{` just above `"id": "_template"` down to its closing `}`.
3. Paste it on a new line right after `"festivals": [`, and put a comma after the `}` of what you pasted. New festivals go on top, and the template stays last with no comma after it.
4. In your copy, change:
   - `id`: short, lowercase, with hyphens, and the same as the folder name: `diwali-2026`
   - `name`: the name you'd say, like `Diwali`
   - `enabled`: change `false` to `true`, or delete the line
   - `start` and `end`: the first and last day, as `YYYY-MM-DD`. Both days count. For one day, use the same date twice.
   - `wallpaper`: `themes/diwali-2026/wallpaper.webp`
   - `burst`: the shape and colours (see below)
   - `greetings`: your festival lines (see below)
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
- `start` and `end` (required): `YYYY-MM-DD`, both days included, read in the phone's own date (IST in India). Festivals that move, like Diwali, Holi and Eid, need new dates each year, so put the year in the id.
- `priority` (optional, default 0): when two festivals overlap, the higher number wins. A tie goes to the one higher up in the list. Only one festival is on at a time.
- `wallpaper` (optional): the path to the picture, starting with `themes/`.
- `colors` (optional): backup colours for Home, as `#RRGGBB`. Leave it as `{}` and Minim picks the colours from the wallpaper. Only fill it in if the automatic colours look off. Any colour you set replaces the automatic one:
  - `text`: the greeting, the minim logo and the top tab labels
  - `pill`: the search pill and the Home mini player pill
  - `pillText`: the text and icons on those pills
- `burst` (optional):
  - `shape`: one of `dots`, `sparks`, `petals`, `snow` or `hearts`. Anything else becomes `dots`.
  - `colors`: 1 to 6 colours as `#RRGGBB`, used in turn. Leave it out to use the cover's colour, like today.
- `greetings` (optional): see below.

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
- During the festival's dates, only Home takes its colours from the wallpaper: the greeting, the minim logo, the top tabs, the search pill and the Home mini player pill. Text turns light or dark depending on how bright the wallpaper is behind it. Every other screen keeps the album colours.
- Every like during the festival uses its burst instead of the six dots.
- If anything is broken or missing, Minim falls back: no wallpaper or a bad one means normal Home, a bad burst means the usual dots, and no greetings means the regular ones. If the whole file is broken, Minim keeps the last good copy, or normal Home if it has none.

## For developers

- The app fetches `https://raw.githubusercontent.com/UniquelyGaurab/minim/main/themes/themes.json`, and each wallpaper from the same base plus its `wallpaper` path.
- [`themes.schema.json`](themes.schema.json) describes the format (JSON Schema 2020-12). Unknown fields are allowed. The app reads `"version": 1`, and treats a higher version as a change it can't read.
- [`check_themes.py`](check_themes.py) runs the schema plus extra checks (real dates, unique ids, known placeholders, line lengths, the wallpaper's presence, size and dimensions). Run it with `python3 themes/check_themes.py`. GitHub runs it on every change under `themes/` (`.github/workflows/themes.yml`).
- This is separate from the Firestore `events` config in [FIREBASE_SETUP.md](../FIREBASE_SETUP.md).
