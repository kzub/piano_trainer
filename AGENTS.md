# Piano Trainer agent guide

## Preparing scores and installing them

For requests to convert sheet music or upload a score to the tablet, read
[the prepare-piano-score skill](.agents/skills/prepare-piano-score/SKILL.md).
It covers the normal MuseScore workflow, a checked PDF transcription fallback,
MuseScore export failures, and verification on the device.

- The Android app imports `.pianoscore`. A PDF copied to Downloads is not an
  interactive exercise. The supported converter input is one `.mscz` containing
  one piano part and both hands.
- Use `score-preparer/src/prepare_mscz.py`, then
  `score-preparer/src/audit_score_package.py --strict`, then
  `score-preparer/src/install_pianoscore.py` when installation is requested.
  See [score-preparer/README.md](score-preparer/README.md) for the command syntax.
- A user-supplied PDF can sometimes be transcribed locally into MusicXML and
  exported to `.mscz` before running that same pipeline. This is a separate
  preparation step, not native PDF support. Verify every measure against the
  source; report ambiguities rather than inventing notes.
- Reuse the repository converter and installer. Do not switch to the legacy
  independent MIDI/rendering pipeline for a new score or weaken validation to
  work around a tool failure.
- Store source transcriptions, exports, diagnostic logs, previews, and finished
  packages in ignored `local-content/`. Prefer a `<slug>-source/` subdirectory
  for intermediates. Keep user sheet music out of tracked documentation.
- Discover current ADB devices each time. The app ID is
  `com.konstantin.pianotrainer`; direct installation requires a debug build with
  working `run-as`. Do not rebuild/install an APK merely to add a score.
- An explicit request to upload/install authorizes that step on the identified
  device. Ask only when the target is ambiguous or an actual permission barrier
  requires it. Report checks performed separately from untested playback/MIDI.

## Operational knowledge

MuseScore 4.7.4 on the local Mac has produced complete export data and then
aborted with `mutex lock failed: Invalid argument`. The converter already retries
five times, but retries can all fail at shutdown. Read the skill's
[recovery reference](.agents/skills/prepare-piano-score/references/musescore-recovery.md)
before accepting any nonzero export. A valid file's existence alone is insufficient.

The strict audit verifies package integrity, note mapping, both-hand events,
and layout coverage. It does **not** prove a PDF transcription is musically
correct. Visual source comparison and device rendering checks remain separate.
