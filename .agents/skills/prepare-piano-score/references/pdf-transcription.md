# PDF to an editable piano source

Use this fallback when the user supplies PDF and no matching semantic source is
available. The supported package pipeline remains `.mscz -> .pianoscore`.

## Inspect before choosing an approach

Use `pdfinfo` for page count and `pdftoppm` for previews of **all** pages. For example:

```sh
mkdir -p local-content/<slug>-source
pdftoppm -scale-to 1800 -png /absolute/path/to/input.pdf \
  local-content/<slug>-source/original
```

Inspect images using the available image-viewing tool. Render larger or crop local
previews for dense chords and ledger lines. Text extraction alone cannot identify
pitches and rhythms. Reuse installed rendering tools; discover bundled workspace
dependencies if missing rather than assuming a Python package is available.

For short, clear notation, a measure-indexed transcription can be more practical
than installing OMR. Repetition reduces entry work but does not prove measures are
identical. Check changes in voicing, octaves, bass reinforcement, dynamics, and ties.
If notes are unreadable or the task is too complex to verify, retain useful work
and request the missing source or clarification rather than silently approximating.

## Preserve the musical content

Record provenance and make the transcription reproducible in a local script or
MusicXML file. Preserve title, composer, arranger, key signature, meter, tempo,
staff/hand assignment, pitches and accidentals, durations, chords, ties, rests,
dynamics, repeats/endings where present, and the complete final measure.

A MusicXML `score-partwise` with one Piano part and two staves works with the
converter. Use explicit `staff` and distinct voices for the hands. A `backup`
returns the time cursor for the second staff; extra chord notes use `chord` and
do not advance it. Divisions of 2 handle eighths and dotted quarters, but choose
appropriate divisions for the actual rhythms. Encode playback `tie` and visual
`notations/tied` consistently. Use the key signature and explicit pitch alterations
rather than changing note spelling to avoid flats or sharps.

Assert each full measure's duration per voice (allow real pickups and irregular
measures), verify staff and measure counts, and check tie endpoints. These checks
catch structural errors, not incorrect pitches copied consistently.

```sh
mscore -o local-content/<slug>.mscz \
  local-content/<slug>-source/<slug>.musicxml
```

Render the resulting source and compare **every measure** against the supplied
PDF. Check dense voicings and octave changes at higher resolution. Correct the
transcription before packaging. Be explicit that re-engraving may change spacing
and pagination while preserving the music. The package audit cannot substitute
for this comparison.

## Rendering exports without extra Python dependencies

MuseScore's `--score-media` JSON includes `pngs` and `svgs` arrays as base64, plus
MusicXML (`mxml`), positional XML, MIDI, PDF, and metadata. When those exports
already exist, decode `pngs` for visual QA instead of installing CairoSVG or PyMuPDF.
PNGs can be very large: resize known local exports for inspection and avoid globally
disabling image decompression safeguards. Do not author an extra PDF solely for QA.

## Observed successful case (2026-10-13)

The supplied “Now We Are Free - Gladiator” PDF, arranged by Jacob Justus, had two
pages, 48 measures, 4/4, two flats, and quarter-note tempo 140. Local transcription
preserved the fuller middle-section voicings and the lower final-section melody.
The package had six tablet pages, 384 mapped segments, and 559 learning events
(428 left, 131 right), and passed the strict audit. The installed score opened
with both staves and initial note highlighting on a Samsung SM-X230.

These are case-specific observations, **not** defaults or expected counts for
future pieces. Full playback and hardware MIDI input were not tested in that run.
Optional local examples are `local-content/now-we-are-free-source/transcribe.py`
and `local-content/now-we-are-free.mscz`; they are ignored and may not exist in a
fresh checkout. Do not require or copy their musical content for another piece.
