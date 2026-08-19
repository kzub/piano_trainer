#!/usr/bin/env python3
"""Create a deterministic MIDI event timeline for a first .pianoscore package.

The output deliberately has empty scoreNoteIds. It is suitable for viewing and
playback, but not yet for score-following training; a reviewed score mapping is
required for that later stage.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path


def read_vlq(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    while True:
        byte = data[offset]
        offset += 1
        value = (value << 7) | (byte & 0x7F)
        if not byte & 0x80:
            return value, offset


@dataclass
class Note:
    track: int
    channel: int
    pitch: int
    velocity: int
    on_tick: int
    off_tick: int


def read_notes(path: Path) -> tuple[int, list[Note]]:
    data = path.read_bytes()
    if data[:4] != b"MThd" or data[4:8] != b"\x00\x00\x00\x06":
        raise ValueError("not a Standard MIDI File")
    fmt = int.from_bytes(data[8:10], "big")
    tracks = int.from_bytes(data[10:12], "big")
    ppq = int.from_bytes(data[12:14], "big")
    if fmt not in (0, 1) or ppq & 0x8000:
        raise ValueError("only PPQ Standard MIDI Files in formats 0 and 1 are supported")
    offset = 14
    result: list[Note] = []
    for track in range(tracks):
        if data[offset:offset + 4] != b"MTrk":
            raise ValueError(f"missing track {track}")
        size = int.from_bytes(data[offset + 4:offset + 8], "big")
        end = offset + 8 + size
        cursor, tick, running = offset + 8, 0, None
        active: dict[tuple[int, int], list[tuple[int, int]]] = {}
        while cursor < end:
            delta, cursor = read_vlq(data, cursor)
            tick += delta
            status = data[cursor]
            if status < 0x80:
                if running is None:
                    raise ValueError("invalid running status")
                status = running
            else:
                cursor += 1
                if status < 0xF0:
                    running = status
            if status == 0xFF:
                cursor += 1  # meta type
                length, cursor = read_vlq(data, cursor)
                cursor += length
                continue
            if status in (0xF0, 0xF7):
                length, cursor = read_vlq(data, cursor)
                cursor += length
                continue
            command, channel = status & 0xF0, status & 0x0F
            first = data[cursor]
            cursor += 1
            second = None
            if command not in (0xC0, 0xD0):
                second = data[cursor]
                cursor += 1
            if command == 0x90 and second and second > 0:
                active.setdefault((channel, first), []).append((tick, second))
            elif command == 0x80 or (command == 0x90 and second == 0):
                starts = active.get((channel, first), [])
                if starts:
                    on_tick, velocity = starts.pop(0)
                    result.append(Note(track, channel, first, velocity, on_tick, tick))
        offset = end
    return ppq, sorted(result, key=lambda note: (note.on_tick, note.track, note.channel, note.pitch))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--midi", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    ppq, notes = read_notes(args.midi.expanduser().resolve())
    events = []
    for index, note in enumerate(notes):
        events.append({
            "id": f"midi-{index:05d}", "pitch": note.pitch, "velocity": note.velocity,
            "onTick": note.on_tick, "offTick": note.off_tick,
            "track": note.track, "channel": note.channel,
            "hand": "right" if note.pitch >= 60 else "left",
            "expectedGroupId": f"tick-{note.on_tick}", "scoreNoteIds": [],
        })
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"version": 1, "kind": "timeline-only", "ppq": ppq, "events": events}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(events)} note events to {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, IndexError) as error:
        print(f"generate-timeline: {error}", file=__import__("sys").stderr)
        raise SystemExit(2)
