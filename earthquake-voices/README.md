# Earthquake alert voice clips

Pre-generated ElevenLabs voice clips for earthquake alerts sent over the BLE mesh.
The mesh carries only short codes (BT Probe packets hold 12 bytes of text); each
phone plays the matching clip offline.

- `clips/` - 44 MP3s (11 alerts + 33 number/word clips) and `manifest.json`
  (code -> file, on-screen text, `screen_extra` advice, example playlists)
- `generate_clips.py` - regenerates the clips (Python standard library only)

| Code | Alert |
| --- | --- |
| `EQW` | Shaking expected |
| `EQN` | Shaking now |
| `EQA[:mag[:km]]` | Possible light shaking, e.g. `EQA:4.8:120` |
| `AFT` | Aftershocks |
| `TSU` | Tsunami |
| `CLR` | After the shaking |
| `CHK` | Safety check-in |
| `SOS` | Help needed nearby |
| `CXL` | False alarm / cancel |
| `RLY` | Unverified source |
| `TEST` | Test |

`playlist()` in `generate_clips.py` is the reference for turning a received code
into the ordered list of clips. Unknown codes play nothing.

## Regenerate

```bash
export ELEVENLABS_API_KEY=...   # never commit the key
python3 generate_clips.py --dry-run          # preview, no credits used
python3 generate_clips.py --voice-id <ID>    # missing clips only
python3 generate_clips.py --only EQW --force # redo one alert
```

Alerts use `eleven_v3` (understands the `[urgent]`/`[calm]` cues); single
words/numbers use `eleven_multilingual_v2`. Default voice: Rachel.

Scripts follow US guidance (USGS ShakeAlert, NOAA, Cal OES). Check local
guidance for other regions.
