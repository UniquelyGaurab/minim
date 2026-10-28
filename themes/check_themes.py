#!/usr/bin/env python3
"""Checks themes/themes.json and its wallpapers. Run from the repo root:

    python3 themes/check_themes.py

GitHub runs it on every change under themes/ (.github/workflows/themes.yml). Errors fail the
check; warnings only print. Needs Python 3.8+; uses the jsonschema package when it's installed.
"""
import datetime
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THEMES = os.path.join(ROOT, "themes", "themes.json")
SCHEMA = os.path.join(ROOT, "themes", "themes.schema.json")

MAX_FILE = 64 * 1024          # same cap as greetings.json
WALL_SOFT = 300 * 1024        # target size
WALL_HARD = 1024 * 1024       # Minim won't download a bigger wallpaper
MAX_LINE, MAX_TEXT = 40, 80   # same as greetings.json

# The placeholders Minim knows (GreetingTokens in the app)
WORDS = {"song", "artist", "last", "lastartist", "topsong", "topartist", "likedsong",
         "likedartist", "playlist", "bigplaylist", "weekday"}
COUNTS = {"plays", "today", "liked", "topsongplays", "topartistplays", "artists",
          "likedartistsongs", "playlists", "bigplaylistsongs"}
KNOWN = WORDS | COUNTS | {"name"}
TOKEN = re.compile(r"\{([A-Za-z_]+)\}")

errors, warnings = [], []
GH = os.environ.get("GITHUB_ACTIONS") == "true"


def err(msg):
    errors.append(msg)
    print(f"::error file=themes/themes.json::{msg}" if GH else f"ERROR: {msg}")


def warn(msg):
    warnings.append(msg)
    print(f"::warning file=themes/themes.json::{msg}" if GH else f"warning: {msg}")


def webp_size(path):
    """(width, height) of a WebP file, or None when it isn't one."""
    with open(path, "rb") as f:
        h = f.read(40)
    if len(h) < 30 or h[0:4] != b"RIFF" or h[8:12] != b"WEBP":
        return None
    kind = h[12:16]
    if kind == b"VP8 ":
        w, ht = struct.unpack("<HH", h[26:30])
        return w & 0x3FFF, ht & 0x3FFF
    if kind == b"VP8L":
        b0, b1, b2, b3 = h[21:25]
        return 1 + (((b1 & 0x3F) << 8) | b0), 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6))
    if kind == b"VP8X":
        return 1 + int.from_bytes(h[24:27], "little"), 1 + int.from_bytes(h[27:30], "little")
    return None


def clean_lines(text):
    """The two Home lines, roughly as Minim splits them (None when too long or empty)."""
    parts = [re.sub(r"[ \t\u00A0]+", " ", p).strip() for p in text.replace("\r", "").split("\n")]
    parts = [p for p in parts if p]
    if not parts:
        return None
    if sum(len(p) for p in parts) + len(parts) - 1 > MAX_TEXT:
        return None
    l1, l2 = (parts[0], " ".join(parts[1:])) if len(parts) > 1 else (parts[0], "")
    if len(l1) > MAX_LINE or len(l2) > MAX_LINE:
        return None
    return l1, l2


def check_greeting(fid, group, i, g):
    where = f"{fid}: greetings.{group}[{i}]"
    if not isinstance(g, dict) or not isinstance(g.get("text"), str):
        err(f"{where} needs a \"text\"")
        return
    for k in ("from", "to", "day", "year"):
        if k in g:
            err(f"{where} has \"{k}\": festival greetings use the festival's start and end, so remove it")
    if clean_lines(g["text"]) is None:
        err(f"{where} is too long: keep each line under {MAX_LINE} characters ({MAX_TEXT} in total)")
    bad = [t for t in TOKEN.findall(g["text"]) if t not in KNOWN]
    if bad:
        err(f"{where} uses unknown placeholder(s) {', '.join('{' + b + '}' for b in bad)}; Minim would skip it")
    stats = [t for t in TOKEN.findall(g["text"]) if t in WORDS | COUNTS]
    if group == "anytime" and (stats or any(k in g for k in ("min", "max", "needs", "playing", "liked"))):
        warn(f"{where} talks about the listening; it works, but it belongs in \"stats\"")


def main():
    if not os.path.exists(THEMES):
        err("themes/themes.json is missing")
        return
    size = os.path.getsize(THEMES)
    if size > MAX_FILE:
        err(f"themes.json is {size // 1024} KB; Minim ignores it above {MAX_FILE // 1024} KB. Delete old festivals.")
    try:
        with open(THEMES, encoding="utf-8") as f:
            data = json.load(f)
    except ValueError as e:
        err(f"themes.json isn't valid JSON: {e}. Look for a missing comma, bracket or quote near there.")
        return

    try:
        import jsonschema
        with open(SCHEMA, encoding="utf-8") as f:
            schema = json.load(f)
        v = jsonschema.Draft202012Validator(schema)
        for e in sorted(v.iter_errors(data), key=lambda e: list(e.absolute_path)):
            path = "/".join(str(p) for p in e.absolute_path) or "(top)"
            err(f"{path}: {e.message}")
    except (ImportError, AttributeError):
        warn("jsonschema isn't installed, so only the basic checks ran (pip install jsonschema)")

    if not isinstance(data, dict) or not isinstance(data.get("festivals"), list):
        err("themes.json needs a \"festivals\" list")
        return

    today = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=5, minutes=30)).date()
    seen, live = set(), []
    for n, fest in enumerate(data["festivals"]):
        if not isinstance(fest, dict):
            err(f"festivals[{n}] isn't an object")
            continue
        fid = str(fest.get("id", f"festivals[{n}]"))
        if fid in seen:
            err(f"two festivals have the id \"{fid}\"")
        seen.add(fid)
        try:
            start = datetime.date.fromisoformat(fest.get("start", ""))
            end = datetime.date.fromisoformat(fest.get("end", ""))
        except (TypeError, ValueError):
            err(f"{fid}: start and end must be real dates like 2026-11-08")
            continue
        if end < start:
            err(f"{fid}: end ({end}) is before start ({start})")
        elif (end - start).days > 45:
            warn(f"{fid}: runs {(end - start).days + 1} days, is that right?")
        enabled = fest.get("enabled", True) is not False and not fid.startswith("_")
        if enabled and end < today:
            warn(f"{fid}: already over ({end}); you can delete it and its folder")

        wp = fest.get("wallpaper")
        if isinstance(wp, str):
            full = os.path.join(ROOT, *wp.split("/"))
            if not os.path.isfile(full):
                (err if enabled else warn)(f"{fid}: wallpaper {wp} isn't in the repo (check the folder and file name, they're case-sensitive)")
            else:
                ws = os.path.getsize(full)
                if ws > WALL_HARD:
                    err(f"{fid}: wallpaper is {ws // 1024} KB; Minim won't download one over {WALL_HARD // 1024} KB")
                elif ws > WALL_SOFT:
                    warn(f"{fid}: wallpaper is {ws // 1024} KB; aim for under {WALL_SOFT // 1024} KB")
                dims = webp_size(full)
                if dims is None:
                    err(f"{fid}: {wp} isn't a real WebP file (renaming a .png or .jpg doesn't convert it)")
                elif dims != (1080, 2400):
                    ratio = dims[0] / dims[1]
                    if not 0.5 <= ratio <= 0.6:
                        warn(f"{fid}: wallpaper is {dims[0]}x{dims[1]}; it should be portrait, 1080x2400 (9:20) or 9:16")
                    elif dims[1] < 1600:
                        warn(f"{fid}: wallpaper is only {dims[0]}x{dims[1]}; it may look soft. 1080x2400 is best.")
            folder = wp.split("/")[1] if wp.count("/") >= 2 else ""
            if folder != fid:
                warn(f"{fid}: wallpaper is in themes/{folder}/; keeping it in themes/{fid}/ is tidier")
        elif enabled:
            warn(f"{fid}: no wallpaper, so Home stays normal (greetings and burst still work)")

        greetings = fest.get("greetings") or {}
        if isinstance(greetings, dict):
            for group in ("anytime", "stats"):
                for i, g in enumerate(greetings.get(group) or []):
                    check_greeting(fid, group, i, g)
            if enabled and not greetings.get("anytime"):
                warn(f"{fid}: no \"anytime\" greetings; when no stats line fits, the regular greetings show")

        if enabled:
            live.append((fid, start, end, int(fest.get("priority", 0) or 0)))

    for i, a in enumerate(live):
        for b in live[i + 1:]:
            if a[1] <= b[2] and b[1] <= a[2] and a[3] == b[3]:
                warn(f"{a[0]} and {b[0]} overlap with the same priority; the one higher in the list wins")

    on = sorted((f for f in live if f[1] <= today <= f[2]), key=lambda f: -f[3])
    soon = sorted((f for f in live if today < f[1] <= today + datetime.timedelta(days=30)), key=lambda f: f[1])
    print(f"Today in IST: {today}. On now: {on[0][0] if on else 'none'}."
          + (f" Coming up: {', '.join(f'{f[0]} ({f[1]})' for f in soon)}." if soon else ""))


main()
print(f"{len(errors)} error(s), {len(warnings)} warning(s)")
sys.exit(1 if errors else 0)
