# Recovering MuseScore exports on the local Mac

## Distinguish desktop access from export failure

In the 2026-10-13 run, a sandboxed MuseScore invocation failed with PasteBoard
errors and an invalid `com.apple.hiservices-xpcservice` connection. The environment's
normal permission escalation allowed desktop execution. If similar errors occur,
use the available permission mechanism for the exact required command; do not
treat a permissions failure as a successful export or ask again when authorization
and tool policy already allow the action.

## Known shutdown crash after complete output

MuseScore 4.7.4 produced a valid `.mscz` and complete `--score-media` and
`--score-elements` JSON, then sometimes terminated with:

```text
libc++abi: terminating due to uncaught exception of type std::__1::system_error:
mutex lock failed: Invalid argument
```

Shell exit status was 134; Python subprocesses can report SIGABRT as -6.
`prepare_mscz.run_musescore` already retries up to five times but requires exit 0,
so all retries can fail even when the last export is complete. This is an observed
version/environment issue, not permission to ignore arbitrary nonzero exits.

## Bounded recovery procedure

1. Try the normal converter first. Capture stdout and stderr separately when
   investigating; stderr must not contaminate JSON output.
2. For a `.mscz` saved despite the crash, test ZIP integrity, parse the `.mscx`,
   and confirm expected staves/measures. Successful subsequent semantic exports
   and visual comparison are still necessary.
3. If normal retries fail with the exact known shutdown signature, a **local**
   wrapper may override `prepare_mscz.run_musescore` for this conversion. Keep it
   in ignored `local-content/<slug>-source/`; do not change the shared converter
   just to finish a score request.
4. Accept a nonzero result only if the return code is -6 or 134, stderr contains
   the known mutex failure, **and** the complete stdout parses and validates as
   the expected export. Use explicit validation errors rather than assertions
   that Python optimization could disable.
5. For elements, require a nonempty list with the intended piano part and
   nonempty musical elements. For media, require nonempty `svgs`, `mxml`,
   `metadata`, `sposXML`, and `mposXML`; compare measure count with the actual
   source. Decode/parse the payloads through the existing converter's checks.
   Plain and highlighted exports must have corresponding pages and geometry.
6. Preserve the repository's remaining conversion checks, then run the independent
   strict package audit and visual review. Keep export JSON and a conversion
   report locally for diagnosis. Record when a known crash was tolerated.
7. Bound attempts (five at most per export). Stop recovery for incomplete JSON,
   unrelated failure signatures, missing fields, inconsistent counts, mapping
   failures, or failed audit. Do not replace failed data with empty arrays, trim
   broken JSON, suppress audit warnings, or install a partially verified package.

An ignored case-specific wrapper exists at
`local-content/now-we-are-free-source/convert_local.py` on the original workstation.
It demonstrates importing the shared converter and overriding only its export
runner. It hardcodes a 48-measure check and source/output names, so it is not a
general-purpose command. If adapting it, parameterize paths and expected counts,
and replace its assertions with explicit checks. Do not depend on that file being
present in a fresh checkout.

## Distinguish validation results

- Complete JSON plus payload parsing establishes export completeness, not musical
  equivalence to a PDF.
- The strict audit checks hashes, linked score IDs, both-hand events, and SVG
  viewport coverage, not the correctness of transcribed pitches.
- Matching the full local/device SHA-256 verifies transfer integrity.
- A screenshot of the opened score verifies rendering at that moment, not
  playback timing, later page navigation, or MIDI keyboard input.
