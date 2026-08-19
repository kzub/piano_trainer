#!/usr/bin/env python3
"""Audit a .pianoscore package for layout and MIDI-to-score ambiguity."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter, defaultdict
from pathlib import Path


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--target-ratio", type=float, default=1.6,
                        help="expected score viewport width/height ratio")
    parser.add_argument("--strict", action="store_true", help="fail when warnings are found")
    args = parser.parse_args()

    source = args.package.expanduser().resolve()
    warnings: list[str] = []
    errors: list[str] = []
    with zipfile.ZipFile(source) as archive:
        names = set(archive.namelist())
        if "manifest.json" not in names:
            errors.append("manifest.json is missing")
            manifest = {}
        else:
            manifest = json.loads(archive.read("manifest.json"))

        mapping_name = manifest.get("mapping", "mapping.json")
        if mapping_name not in names:
            errors.append(f"{mapping_name} is missing")
            mapping = {"events": []}
        else:
            mapping = json.loads(archive.read(mapping_name))

        events = mapping.get("events", [])
        empty_links = [event for event in events if not event.get("scoreNoteIds")]
        if empty_links:
            message = f"{len(empty_links)}/{len(events)} MIDI events have no scoreNoteIds"
            (errors if mapping.get("kind") == "exact-score" else warnings).append(message)

        groups: dict[str, list[dict]] = defaultdict(list)
        for event in events:
            groups[str(event.get("expectedGroupId"))].append(event)
        multi_track = sum(len({event.get("track") for event in group}) > 1 for group in groups.values())
        mixed_hand = sum(len({event.get("hand") for event in group}) > 1 for group in groups.values())
        # Chords commonly combine hands/tracks; this is normal and is resolved
        # by per-event score IDs rather than a pitch-only group heuristic.

        page_names = [name for name in manifest.get("pages", {}).get("normal", []) if name in names]
        score_note_ids: set[str] = set()
        page_ratios: list[float] = []
        for page_name in page_names:
            root = ET.fromstring(archive.read(page_name))
            view_box = root.attrib.get("viewBox", "").split()
            if len(view_box) == 4 and float(view_box[3]) > 0:
                page_ratios.append(float(view_box[2]) / float(view_box[3]))
            for element in root.iter():
                classes = element.attrib.get("class", "").split()
                if "note" in classes and element.attrib.get("data-id"):
                    score_note_ids.add(element.attrib["data-id"])

        if page_ratios:
            mismatch = max(abs(ratio - args.target_ratio) / args.target_ratio for ratio in page_ratios)
            if mismatch > 0.05:
                warnings.append(
                    f"SVG page ratio {page_ratios[0]:.3f} differs from target {args.target_ratio:.3f}"
                )

        linked_ids = {
            str(note_id)
            for event in events
            for note_id in event.get("scoreNoteIds", [])
        }
        missing_ids = linked_ids - score_note_ids
        if missing_ids:
            errors.append(f"{len(missing_ids)} mapped scoreNoteIds are absent from SVG pages")

        score_name = manifest.get("score", "score.musicxml")
        pitched_notes = 0
        if score_name in names:
            root = ET.fromstring(archive.read(score_name))
            pitched_notes = sum(local_name(element.tag) == "pitch" for element in root.iter())
            if pitched_notes != len(events) and mapping.get("kind") != "exact-score":
                warnings.append(
                    f"MusicXML has {pitched_notes} pitched noteheads for {len(events)} MIDI events; "
                    "ties/voices require explicit many-to-many mapping"
                )

    print(f"Package: {source.name}")
    print(f"MIDI events: {len(events)}; expected groups: {len(groups)}; SVG notes: {len(score_note_ids)}")
    for message in warnings:
        print(f"WARNING: {message}")
    for message in errors:
        print(f"ERROR: {message}")
    if errors or (args.strict and warnings):
        return 2
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile, ET.ParseError) as error:
        print(f"audit-score-package: {error}", file=sys.stderr)
        raise SystemExit(2)
