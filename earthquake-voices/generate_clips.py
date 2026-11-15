#!/usr/bin/env python3
"""Generate earthquake-alert voice clips with the ElevenLabs text-to-speech API.

The mesh network only carries short codes (BT Probe packets hold 12 bytes of
text), so every phone ships with these clips pre-generated and plays them
offline when a code arrives.

Usage:
    export ELEVENLABS_API_KEY=...            # https://elevenlabs.io -> API keys
    python3 generate_clips.py --list-voices  # pick a voice ID
    python3 generate_clips.py --voice-id <ID>
    python3 generate_clips.py --dry-run      # show what would be generated, no API calls

Output (default ./clips): one MP3 per clip plus manifest.json, which maps mesh
codes to files and on-screen text for the app. Existing files are skipped, so
re-running only generates what's missing (saves credits). Use --force to redo.

Only Python's standard library is used.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.elevenlabs.io/v1"
DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"  # "Rachel", an ElevenLabs premade voice
DEFAULT_MODEL = "eleven_v3"              # understands [urgent]/[calm] audio tags
# v3 is unreliable on very short text, so single words/numbers use a steadier model.
DEFAULT_WORDS_MODEL = "eleven_multilingual_v2"

# ---------------------------------------------------------------------------
# Alert scripts (reviewed against USGS ShakeAlert, NOAA and Cal OES guidance).
# Action comes first; no spoken countdowns (show those on screen instead).
# ---------------------------------------------------------------------------
ALERTS = {
    "EQW": ("urgent", "Shaking expected",
            "[urgent] Earthquake! Drop, cover, hold on. Protect your head and neck."),
    "EQN": ("urgent", "Shaking now",
            "[urgent] Earthquake! Drop, cover, hold on. Stay put until the shaking stops."),
    "EQA": ("serious", "Possible light shaking",
            "[serious] Earthquake detected nearby. Light shaking is possible. "
            "If you feel shaking, drop, cover, and hold on."),
    "AFT": ("serious", "Aftershocks",
            "[serious] Aftershocks are possible. If shaking starts again, drop, cover, "
            "and hold on. Stay away from damaged buildings and windows."),
    "TSU": ("urgent", "Tsunami",
            "[urgent] Tsunami danger. If you are near the coast, go to high ground or "
            "inland now. Do not wait for official instructions. Stay away from the "
            "shore until officials say it is safe."),
    "CLR": ("calm", "After the shaking",
            "[calm] If the shaking has stopped, check yourself and others for injuries. "
            "Expect aftershocks. If you smell gas, leave right away and do not use "
            "flames or light switches. Use stairs, not elevators."),
    "CHK": ("calm", "Check-in",
            "[calm] Community safety check. If you are safe, tap \"I'm OK\" to tell "
            "people nearby."),
    "SOS": ("urgent", "Help needed",
            "[urgent] Someone nearby needs help. Only go if it is safe for you."),
    "CXL": ("calm", "False alarm",
            "[calm] Cancel. The earlier earthquake alert was a false alarm. "
            "No action is needed."),
    "RLY": ("calm", "Unverified source",
            "[calm] This alert came from nearby phones and could not be verified. "
            "Follow official alerts if you receive them."),
    "TEST": ("calm", "Test",
             "[calm] Test. This is only a test of the earthquake mesh alert system. "
             "No action is needed. Test."),
}

# Urgent clips are kept short; the extra advice is shown on screen instead.
SCREEN_EXTRA = {
    "EQW": "Strong shaking is coming. Get under a sturdy table. "
           "Wheelchair or walker users: lock, cover, hold on.",
    "EQN": "Stay where you are until the shaking stops. Do not run outside. "
           "Wheelchair or walker users: lock, cover, hold on.",
}

# Short pieces used only for the optional details after EQA
# (e.g. "magnitude four point eight, about one hundred twenty kilometres away").
WORDS = {
    "magnitude": "Magnitude",
    "point": "point",
    "about": "about",
    "hundred": "hundred",
    "km_away": "kilometres away.",
}
ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen"]
TENS = {20: "twenty", 30: "thirty", 40: "forty", 50: "fifty", 60: "sixty",
        70: "seventy", 80: "eighty", 90: "ninety"}
NUMBERS = {**{n: w for n, w in enumerate(ONES)}, **TENS}


def all_clips():
    """Return {filename_stem: (text, tone)} for every clip to generate."""
    clips = {f"alert_{code.lower()}": (text, tone)
             for code, (tone, _title, text) in ALERTS.items()}
    clips.update({f"word_{k}": (v, "serious") for k, v in WORDS.items()})
    clips.update({f"num_{n}": (w, "serious") for n, w in NUMBERS.items()})
    return clips


# ---------------------------------------------------------------------------
# Reference playlist logic for the app: mesh code -> ordered list of clip files.
# ---------------------------------------------------------------------------
def number_clips(n):
    """Integer 0-999 -> clip stems, e.g. 120 -> num_1, word_hundred, num_20."""
    if not 0 <= n <= 999:
        raise ValueError(f"number out of range: {n}")
    out = []
    if n >= 100:
        out += [f"num_{n // 100}", "word_hundred"]
        n %= 100
        if n == 0:
            return out
    if n < 20:
        out.append(f"num_{n}")
    else:
        out.append(f"num_{n - n % 10}")
        if n % 10:
            out.append(f"num_{n % 10}")
    return out


def playlist(message):
    """Mesh message -> list of MP3 filenames to play in order.

    "EQW" -> [alert_eqw.mp3]
    "EQA:4.8:120" -> [alert_eqa, magnitude, four, point, eight, about,
                      one, hundred, twenty, kilometres away]
    Unknown codes return [] so the app never plays something unexpected.
    """
    code, *args = message.strip().split(":")
    code = code.upper()
    if code not in ALERTS:
        return []
    stems = [f"alert_{code.lower()}"]
    if code == "EQA" and len(args) >= 1:
        try:
            whole, _, frac = args[0].partition(".")
            mag = ["word_magnitude", *number_clips(int(whole))]
            if frac:
                mag += ["word_point", *number_clips(int(frac[0]))]
            dist = []
            if len(args) >= 2:
                dist = ["word_about", *number_clips(int(args[1])), "word_km_away"]
            stems += mag + dist
        except ValueError:
            pass  # malformed details: still play the base alert
    return [s + ".mp3" for s in stems]


# ---------------------------------------------------------------------------
# ElevenLabs API
# ---------------------------------------------------------------------------
TAG_RE = re.compile(r"\[[a-z ]+\]\s*")


def prepare_text(text, model):
    # Only v3 models understand [audio tags]; other models would read them aloud.
    return text if model.startswith("eleven_v3") else TAG_RE.sub("", text)


def voice_settings(model):
    if model.startswith("eleven_v3"):
        return {"stability": 0.5}  # v3 accepts only 0.0 / 0.5 / 1.0
    return {"stability": 0.6, "similarity_boost": 0.75}


def api_request(path, api_key, body=None, accept="application/json", retries=4):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path, data=data, method="POST" if data else "GET",
        headers={"xi-api-key": api_key, "Content-Type": "application/json",
                 "Accept": accept})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                return resp.read()
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code in (429, 500, 502, 503) and attempt < retries:
                wait = 2 ** attempt * 2
                print(f"  HTTP {e.code}, retrying in {wait}s...", file=sys.stderr)
                time.sleep(wait)
                continue
            raise SystemExit(f"ElevenLabs API error {e.code}: {detail}")
        except urllib.error.URLError as e:
            if attempt < retries:
                time.sleep(2 ** attempt * 2)
                continue
            raise SystemExit(f"Network error: {e.reason}")


def list_voices(api_key):
    voices = json.loads(api_request("/voices", api_key))["voices"]
    for v in sorted(voices, key=lambda v: v["name"].lower()):
        labels = ", ".join(f"{k}: {val}" for k, val in (v.get("labels") or {}).items())
        print(f"{v['voice_id']}  {v['name']:<20} {labels}")


def synthesize(text, api_key, voice_id, model, fmt):
    body = {"text": prepare_text(text, model), "model_id": model,
            "voice_settings": voice_settings(model)}
    return api_request(f"/text-to-speech/{voice_id}?output_format={fmt}",
                       api_key, body, accept="audio/mpeg")


def write_manifest(out_dir, voice_id, model, words_model):
    manifest = {
        "voice_id": voice_id,
        "model": model,
        "words_model": words_model,
        "alerts": {code: {"file": f"alert_{code.lower()}.mp3", "title": title,
                          "tone": tone, "text": TAG_RE.sub("", text),
                          "screen_extra": SCREEN_EXTRA.get(code, "")}
                   for code, (tone, title, text) in ALERTS.items()},
        "example_playlists": {m: playlist(m) for m in
                              ("EQW", "EQN", "EQA:4.8:120", "TSU", "CXL", "TEST")},
    }
    with open(os.path.join(out_dir, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)


def model_for(stem, args):
    return args.model if stem.startswith("alert_") else args.words_model


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--voice-id", default=os.environ.get("ELEVENLABS_VOICE_ID", DEFAULT_VOICE))
    p.add_argument("--words-model", default=DEFAULT_WORDS_MODEL,
                   help="model for the short number/word clips (default eleven_multilingual_v2)")
    p.add_argument("--model", default=DEFAULT_MODEL,
                   help="e.g. eleven_v3 (default) or eleven_multilingual_v2")
    p.add_argument("--format", default="mp3_44100_128", help="ElevenLabs output_format")
    p.add_argument("--out", default="clips", help="output directory")
    p.add_argument("--only", nargs="+", metavar="CODE",
                   help="only these alert codes, e.g. --only EQW TSU (skips number clips)")
    p.add_argument("--force", action="store_true", help="regenerate existing files")
    p.add_argument("--dry-run", action="store_true", help="list clips without calling the API")
    p.add_argument("--list-voices", action="store_true", help="print available voices and exit")
    args = p.parse_args()

    clips = all_clips()
    if args.only:
        unknown = [c for c in args.only if c.upper() not in ALERTS]
        if unknown:
            p.error(f"unknown codes: {', '.join(unknown)}; known: {', '.join(ALERTS)}")
        wanted = {f"alert_{c.lower()}" for c in args.only}
        clips = {k: v for k, v in clips.items() if k in wanted}

    if args.dry_run:
        for stem, (text, _tone) in clips.items():
            m = model_for(stem, args)
            print(f"{stem}.mp3  [{m}]  <-  {prepare_text(text, m)}")
        print(f"\n{len(clips)} clips, {sum(len(t) for t, _ in clips.values())} characters "
              f"(ElevenLabs bills per character).")
        return

    api_key = os.environ.get("ELEVENLABS_API_KEY")
    if not api_key:
        sys.exit("Set ELEVENLABS_API_KEY first (ElevenLabs dashboard -> API keys).")
    if args.list_voices:
        list_voices(api_key)
        return

    os.makedirs(args.out, exist_ok=True)
    made = skipped = 0
    for stem, (text, _tone) in clips.items():
        path = os.path.join(args.out, stem + ".mp3")
        if os.path.exists(path) and not args.force:
            skipped += 1
            continue
        print(f"generating {stem}.mp3")
        audio = synthesize(text, api_key, args.voice_id, model_for(stem, args), args.format)
        tmp = path + ".part"
        with open(tmp, "wb") as f:
            f.write(audio)
        os.replace(tmp, path)
        made += 1
    write_manifest(args.out, args.voice_id, args.model, args.words_model)
    print(f"done: {made} generated, {skipped} already existed -> {os.path.abspath(args.out)}")


if __name__ == "__main__":
    main()
