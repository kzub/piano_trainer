#!/usr/bin/env python3
"""Build a verified, stable MIDI-to-MusicXML mapping for a .pianoscore.

The first prototype labelled SVG notes by pitch and screen position. That is
not a safe identity: repeated pitches, voices, ties and reflow can select the
wrong printed note. This tool assigns each MusicXML note an xml:id and maps
each sounding MIDI note to the complete tied chain of those IDs.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict, deque
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path
from xml.etree import ElementTree as ET

XML_ID = "{http://www.w3.org/XML/1998/namespace}id"
PITCH_CLASS = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def child(element: ET.Element, name: str) -> ET.Element | None:
    return next((item for item in element if tag(item) == name), None)


def child_text(element: ET.Element, name: str, default: str = "") -> str:
    value = child(element, name)
    return value.text.strip() if value is not None and value.text else default


def read_vlq(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    while True:
        byte = data[offset]
        offset += 1
        value = (value << 7) | (byte & 0x7F)
        if not byte & 0x80:
            return value, offset


@dataclass(frozen=True)
class MidiNote:
    track: int
    channel: int
    pitch: int
    velocity: int
    on_tick: int
    off_tick: int


@dataclass
class ScoreNote:
    xml_id: str
    part: str
    measure: int
    voice: str
    staff: str
    pitch: int
    on_tick: int
    off_tick: int
    tie_start: bool
    tie_stop: bool


def infer_source_hands(notes: list[MidiNote], minimum_dominance: float = 0.90) -> dict[tuple[int, int], str]:
    by_source: dict[tuple[int, int], list[str]] = {}
    for note in notes:
        by_source.setdefault((note.track, note.channel), []).append("right" if note.pitch >= 60 else "left")
    result: dict[tuple[int, int], str] = {}
    for source, hands in by_source.items():
        right = hands.count("right")
        dominant = "right" if right >= len(hands) - right else "left"
        if max(right, len(hands) - right) / len(hands) >= minimum_dominance:
            result[source] = dominant
    return result


def read_notes(path: Path) -> tuple[int, list[MidiNote]]:
    data = path.read_bytes()
    if data[:4] != b"MThd" or data[4:8] != b"\x00\x00\x00\x06":
        raise ValueError("not a Standard MIDI File")
    fmt, tracks, ppq = int.from_bytes(data[8:10], "big"), int.from_bytes(data[10:12], "big"), int.from_bytes(data[12:14], "big")
    if fmt not in (0, 1) or ppq & 0x8000:
        raise ValueError("only PPQ Standard MIDI Files in formats 0 and 1 are supported")
    offset, result = 14, []
    for track in range(tracks):
        if data[offset:offset + 4] != b"MTrk":
            raise ValueError(f"missing track {track}")
        size = int.from_bytes(data[offset + 4:offset + 8], "big")
        end, cursor, tick, running = offset + 8 + size, offset + 8, 0, None
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
                cursor += 1
                length, cursor = read_vlq(data, cursor)
                cursor += length
                continue
            if status in (0xF0, 0xF7):
                length, cursor = read_vlq(data, cursor)
                cursor += length
                continue
            command, channel = status & 0xF0, status & 0x0F
            first, cursor = data[cursor], cursor + 1
            second = None
            if command not in (0xC0, 0xD0):
                second, cursor = data[cursor], cursor + 1
            if command == 0x90 and second and second > 0:
                active.setdefault((channel, first), []).append((tick, second))
            elif command == 0x80 or (command == 0x90 and second == 0):
                starts = active.get((channel, first), [])
                if starts:
                    on_tick, velocity = starts.pop(0)
                    result.append(MidiNote(track, channel, first, velocity, on_tick, tick))
        offset = end
    return ppq, sorted(result, key=lambda value: (value.on_tick, value.track, value.channel, value.pitch, value.off_tick))


def fraction_to_tick(value: Fraction, ppq: int, context: str) -> int:
    ticks = value * ppq
    if ticks.denominator != 1:
        raise ValueError(f"{context} does not align with MIDI PPQ {ppq}: {value}")
    return ticks.numerator


def parse_score(path: Path, ppq: int) -> tuple[ET.ElementTree, list[ScoreNote], list[dict[str, int]]]:
    tree = ET.parse(path)
    root = tree.getroot()
    if tag(root) != "score-partwise":
        raise ValueError("only score-partwise MusicXML is supported")
    score_notes: list[ScoreNote] = []
    reference_measures: list[dict[str, int]] = []
    for part_index, part in enumerate((item for item in root if tag(item) == "part"), start=1):
        part_id, divisions, part_tick, serial = part.get("id", f"P{part_index}"), 1, 0, 0
        for measure_index, measure in enumerate((item for item in part if tag(item) == "measure"), start=1):
            number = int(re.sub(r"\D.*$", "", measure.get("number", str(measure_index))) or measure_index)
            measure_start, cursor, last_start, maximum = part_tick, Fraction(0), Fraction(0), Fraction(0)
            for item in measure:
                name = tag(item)
                if name == "attributes":
                    value = child_text(item, "divisions")
                    if value:
                        divisions = int(value)
                    continue
                if name in ("backup", "forward"):
                    duration = Fraction(int(child_text(item, "duration", "0")), divisions)
                    cursor = cursor - duration if name == "backup" else cursor + duration
                    maximum = max(maximum, cursor)
                    continue
                if name != "note":
                    continue
                duration, is_chord = Fraction(int(child_text(item, "duration", "0")), divisions), child(item, "chord") is not None
                onset = last_start if is_chord else cursor
                if not is_chord:
                    last_start, cursor, maximum = onset, cursor + duration, max(maximum, cursor + duration)
                pitch_element = child(item, "pitch")
                if pitch_element is None:
                    continue
                step, octave, alter = child_text(pitch_element, "step").upper(), int(child_text(pitch_element, "octave")), int(child_text(pitch_element, "alter", "0"))
                serial += 1
                xml_id = item.get(XML_ID) or f"pt-p{part_index}-m{number}-v{child_text(item, 'voice', '1')}-s{child_text(item, 'staff', '1')}-n{serial}"
                item.set(XML_ID, xml_id)
                # MusicXML readers that do not preserve xml:id still retain the
                # ordinary ID attribute; Verovio can export that as data-id.
                item.set("id", xml_id)
                tie_types = {tie.get("type") for tie in item if tag(tie) == "tie"}
                score_notes.append(ScoreNote(
                    xml_id, part_id, number, child_text(item, "voice", "1"), child_text(item, "staff", "1"),
                    (octave + 1) * 12 + PITCH_CLASS[step] + alter,
                    measure_start + fraction_to_tick(onset, ppq, f"{part_id} measure {number}"),
                    measure_start + fraction_to_tick(onset + duration, ppq, f"{part_id} measure {number}"),
                    "start" in tie_types, "stop" in tie_types,
                ))
            measure_duration = fraction_to_tick(maximum, ppq, f"{part_id} measure {number}")
            if part_index == 1:
                reference_measures.append({"number": number, "startTick": measure_start, "durationTicks": measure_duration})
            elif measure_index <= len(reference_measures) and reference_measures[measure_index - 1]["durationTicks"] != measure_duration:
                raise ValueError(f"parts disagree about duration of measure {number}")
            part_tick += measure_duration
    if not score_notes or not reference_measures:
        raise ValueError("MusicXML has no pitched notes or measures")
    return tree, score_notes, reference_measures


def tied_chain(note: ScoreNote, all_notes: list[ScoreNote]) -> list[ScoreNote]:
    chain, current = [note], note
    while current.tie_start:
        following = [candidate for candidate in all_notes if candidate.part == current.part and candidate.voice == current.voice and candidate.staff == current.staff and candidate.pitch == current.pitch and candidate.on_tick == current.off_tick and candidate.tie_stop]
        if len(following) != 1:
            raise ValueError(f"tie from {current.xml_id} has {len(following)} continuations")
        current = following[0]
        chain.append(current)
    return chain


def build_events(midi_notes: list[MidiNote], score_notes: list[ScoreNote], source_hands: dict[tuple[int, int], str]) -> list[dict]:
    attacks_by_key: dict[tuple[int, int], deque[ScoreNote]] = defaultdict(deque)
    for note in sorted((item for item in score_notes if not item.tie_stop), key=lambda value: (value.on_tick, value.pitch, value.part, value.staff, value.voice, value.xml_id)):
        attacks_by_key[(note.on_tick, note.pitch)].append(note)
    midi_by_key: dict[tuple[int, int], deque[tuple[int, MidiNote]]] = defaultdict(deque)
    for index, note in enumerate(midi_notes):
        midi_by_key[(note.on_tick, note.pitch)].append((index, note))
    assigned: dict[int, list[ScoreNote]] = {}
    unmatched_midi: list[int] = []
    unmatched_score: list[str] = []
    for key in sorted(set(attacks_by_key) | set(midi_by_key)):
        midi_queue, score_queue = midi_by_key[key], attacks_by_key[key]
        while midi_queue and score_queue:
            midi_index, _ = midi_queue.popleft()
            assigned[midi_index] = tied_chain(score_queue.popleft(), score_notes)
        unmatched_midi.extend(index for index, _ in midi_queue)
        unmatched_score.extend(note.xml_id for note in score_queue)
    if unmatched_midi or unmatched_score:
        raise ValueError(f"MIDI/MusicXML attacks do not match: {len(unmatched_midi)} MIDI and {len(unmatched_score)} score attacks unmatched")
    events = []
    for index, note in enumerate(midi_notes):
        chain = assigned[index]
        events.append({
            "id": f"midi-{index:05d}", "pitch": note.pitch, "velocity": note.velocity,
            "onTick": note.on_tick, "offTick": note.off_tick, "track": note.track, "channel": note.channel,
            "hand": source_hands.get((note.track, note.channel), "right" if note.pitch >= 60 else "left"),
            "measure": chain[0].measure, "expectedGroupId": f"tick-{note.on_tick}",
            "scoreNoteIds": [item.xml_id for item in chain],
        })
    return events


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--midi", required=True, type=Path)
    parser.add_argument("--musicxml", required=True, type=Path)
    parser.add_argument("--normalized-musicxml", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    ppq, midi_notes = read_notes(args.midi.expanduser().resolve())
    tree, score_notes, measures = parse_score(args.musicxml.expanduser().resolve(), ppq)
    events = build_events(midi_notes, score_notes, infer_source_hands(midi_notes))
    normalized = args.normalized_musicxml.expanduser().resolve()
    normalized.parent.mkdir(parents=True, exist_ok=True)
    ET.register_namespace("xml", "http://www.w3.org/XML/1998/namespace")
    tree.write(normalized, encoding="utf-8", xml_declaration=True)
    output = args.output.expanduser().resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"version": 2, "kind": "exact-score", "ppq": ppq, "measures": measures, "events": events}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {len(events)} exactly mapped MIDI events and {len(score_notes)} MusicXML notes")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        print(f"generate-timeline: {error}", file=__import__("sys").stderr)
        raise SystemExit(2)
