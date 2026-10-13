---
name: prepare-piano-score
description: Convert local sheet music into verified Piano Trainer packages and install them on an authorized Android tablet when requested. Use for MuseScore score preparation, PDF transcription for this repository, and score upload or import troubleshooting.
---

# Prepare a Piano Trainer score

Run commands from the repository root. Read `score-preparer/README.md` for the
supported conversion contract. Prefer an existing `.mscz`; the converter needs
exactly one piano part with events for both hands.

## Choose and prepare the source

- Inspect the supplied file and search narrowly for a matching local `.mscz` or
  MusicXML source. Avoid listing unrelated Downloads contents.
- If given PDF only, read [PDF transcription](references/pdf-transcription.md).
  Rendering a PDF is not extracting note semantics. A short, legible arrangement
  can be transcribed; complex or ambiguous notation may need a better source or
  user clarification. Do not reject every PDF merely because the CLI takes `.mscz`.
- Use the local file the user supplied. Do not fetch scores, bypass download
  restrictions, or install recognition software unless needed and within scope.
- Keep results in `local-content/<slug>.mscz` and `.pianoscore`; keep intermediate
  files and provenance in `local-content/<slug>-source/`. These paths are ignored.

## Convert and validate

Check `command -v mscore`, `python3 --version`, and, if installing, `adb devices -l`.
Use current tool paths rather than hardcoding a dependency-cache version.

```sh
python3 score-preparer/src/prepare_mscz.py local-content/<slug>.mscz \
  --output local-content/<slug>.pianoscore
python3 score-preparer/src/audit_score_package.py \
  local-content/<slug>.pianoscore --strict
```

For crashes, read [MuseScore recovery](references/musescore-recovery.md).
Keep existing semantic and SVG validation intact. Do not use `prepare_score.py`
or independent MIDI and MusicXML inputs for new packages.

Review rendered notation, including ties, chords, both staves, and the final
measure. Tablet pages are slices of the MuseScore engraving (currently up to two
systems per page), so their count can differ from the original paper page count.
The audit's success does not validate transcription accuracy or hardware playback.

## Install and verify when requested

With exactly one authorized ADB device, the installer can select it automatically.
If several are connected, use a known intended serial or ask which target to use.
Do not hardcode a serial remembered from a previous run.

```sh
python3 score-preparer/src/install_pianoscore.py \
  local-content/<slug>.pianoscore --serial <serial>
```

The installer validates package hashes, pushes through `/data/local/tmp`, copies
to `files/scores/<manifest-id>.pianoscore` with `run-as`, removes the staging file,
and restarts `com.konstantin.pianotrainer`. Manual Downloads import is unnecessary
for a working debug installation. If `run-as` is unavailable, explain the limitation
and use the app's import flow if within the user's request; do not silently replace
the installed application.

Verify the full installed file against the local SHA-256:

```sh
shasum -a 256 local-content/<slug>.pianoscore
adb -s <serial> shell run-as com.konstantin.pianotrainer \
  sha256sum files/scores/<manifest-id>.pianoscore
```

Obtain the ID from `manifest.json` inside the package or the installer's report.
Check the app's current UI before interacting:

```sh
adb -s <serial> shell uiautomator dump /data/local/tmp/piano-window.xml
adb -s <serial> pull /data/local/tmp/piano-window.xml \
  local-content/<slug>-source/device-window.xml
adb -s <serial> exec-out screencap -p > local-content/<slug>-source/device-score.png
```

Locate the title and its Open button in the fresh UI hierarchy; derive tap
coordinates from its bounds. Never reuse previous library row coordinates.
Open the score and inspect a fresh screenshot for readable staves and highlighting.
For rendering errors, inspect `PianoTrainerVisual` logcat messages. Exercise page
navigation or playback when needed to resolve a concern; do not claim those checks
from merely opening the score. Return it to the beginning after navigation checks.

Report the installed title, device, measures/pages, audit result, and local package
and editable source links. Say when the source was transcribed from PDF; distinguish
verified rendering from untested MIDI playback or practice input.
