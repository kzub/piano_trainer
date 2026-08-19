#!/usr/bin/env python3
"""Build a deterministic Piano Trainer score package from reviewed inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import uuid
import zipfile
from pathlib import Path
from xml.etree import ElementTree


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_file(value: str, suffix: str) -> Path:
    path = Path(value).expanduser().resolve()
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"file not found: {path}")
    if path.suffix.lower() != suffix:
        raise argparse.ArgumentTypeError(f"expected {suffix}: {path}")
    return path


def validate_midi(path: Path) -> None:
    header = path.read_bytes()[:14]
    if len(header) != 14 or header[:4] != b"MThd" or header[4:8] != b"\x00\x00\x00\x06":
        raise ValueError("source MIDI is not a Standard MIDI File with a six-byte header")
    midi_format = int.from_bytes(header[8:10], "big")
    if midi_format not in (0, 1):
        raise ValueError(f"unsupported MIDI format {midi_format}; only formats 0 and 1 are supported")


def validate_musicxml(path: Path) -> None:
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        raise ValueError(f"invalid MusicXML: {error}") from error
    if root.tag.rsplit("}", 1)[-1] != "score-partwise":
        raise ValueError("only score-partwise MusicXML is supported")


def validate_mapping(path: Path) -> dict:
    try:
        mapping = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid mapping JSON: {error}") from error
    events = mapping.get("events")
    if not isinstance(events, list) or not events:
        raise ValueError("mapping must contain a non-empty events array")
    required = {"id", "pitch", "onTick", "offTick", "hand", "expectedGroupId", "scoreNoteIds"}
    for index, event in enumerate(events):
        if not isinstance(event, dict) or required - event.keys():
            raise ValueError(f"mapping event {index} misses required fields")
        if not 0 <= event["pitch"] <= 127 or event["offTick"] <= event["onTick"]:
            raise ValueError(f"mapping event {index} has invalid pitch or duration")
        if event["hand"] not in ("left", "right", "both", "excluded"):
            raise ValueError(f"mapping event {index} has invalid hand")
        if not isinstance(event["scoreNoteIds"], list):
            raise ValueError(f"mapping event {index} has invalid scoreNoteIds")
        if mapping.get("kind") == "exact-score" and not event["scoreNoteIds"]:
            raise ValueError(f"exact mapping event {index} has no score note IDs")
    if mapping.get("kind") == "exact-score":
        measures = mapping.get("measures")
        if not isinstance(measures, list) or not measures:
            raise ValueError("exact mapping must contain a non-empty measures array")
    return mapping


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--midi", required=True, type=lambda p: require_file(p, ".mid"))
    parser.add_argument("--musicxml", required=True, type=lambda p: require_file(p, ".musicxml"))
    parser.add_argument("--mapping", required=True, type=lambda p: require_file(p, ".json"))
    parser.add_argument("--title", required=True)
    parser.add_argument("--id", default=None, help="stable composition UUID; generated if omitted")
    parser.add_argument("--pages-dir", type=Path, help="directory with Verovio SVG pages for the normal scale")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    validate_midi(args.midi)
    validate_musicxml(args.musicxml)
    validate_mapping(args.mapping)
    package_id = args.id or str(uuid.uuid5(uuid.NAMESPACE_URL, f"piano-trainer:{sha256(args.midi)}"))

    members = {"source.mid": args.midi, "score.musicxml": args.musicxml, "mapping.json": args.mapping}
    pages: dict[str, list[str]] = {}
    if args.pages_dir:
        page_dir = args.pages_dir.expanduser().resolve()
        page_files = sorted(page_dir.glob("*.svg"))
        if not page_files:
            raise ValueError(f"no SVG pages found in {page_dir}")
        page_names = []
        for index, page in enumerate(page_files, start=1):
            name = f"pages/normal/{index:03d}.svg"
            members[name] = page
            page_names.append(name)
        pages["normal"] = page_names
    manifest = {
        "schemaVersion": 2,
        "id": package_id,
        "title": args.title,
        "sourceMidi": "source.mid",
        "score": "score.musicxml",
        "mapping": "mapping.json",
        "pages": pages,
        "sha256": {name: sha256(path) for name, path in members.items()},
    }

    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        for name, path in members.items():
            archive.write(path, name)
    print(f"Created {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f"prepare-score: {error}", file=sys.stderr)
        raise SystemExit(2)
