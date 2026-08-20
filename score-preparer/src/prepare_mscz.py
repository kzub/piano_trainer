#!/usr/bin/env python3
"""Create a self-contained .pianoscore directly from a MuseScore .mscz file."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import shutil
import struct
import subprocess
import tempfile
import uuid
import zipfile
import io
from collections import defaultdict
from pathlib import Path
from xml.etree import ElementTree as ET

from generate_timeline_mapping import parse_score, tied_chain

PPQ = 480
SVG_NS = "http://www.w3.org/2000/svg"


def run_musescore(executable: str, arguments: list[str], output: Path) -> None:
    with output.open("wb") as stream:
        result = subprocess.run([executable, *arguments], stdout=stream, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace").strip() or "MuseScore failed")


def decode(value: str) -> bytes:
    return base64.b64decode(value)


def decode_musicxml(value: str) -> bytes:
    compressed = decode(value)
    if not compressed.startswith(b"PK"):
        return compressed
    with zipfile.ZipFile(io.BytesIO(compressed)) as archive:
        container = ET.fromstring(archive.read("META-INF/container.xml"))
        rootfile = next(item for item in container.iter() if local_name(item.tag) == "rootfile")
        return archive.read(rootfile.attrib["full-path"])


def marker_color(element_id: int) -> str:
    # Deliberately stay away from black and white, the normal notation colors.
    value = element_id + 0x10203
    return f"#{(value >> 16) & 255:02x}{(value >> 8) & 255:02x}{value & 255:02x}"


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def extract_marker(element: ET.Element, colors: dict[str, int]) -> int | None:
    candidates = [element.get("fill", ""), element.get("stroke", "")]
    style = element.get("style", "")
    candidates.extend(part.split(":", 1)[1] for part in style.split(";") if ":" in part)
    for candidate in candidates:
        found = colors.get(candidate.strip().lower())
        if found is not None:
            return found
    return None


def annotate_svg(plain: bytes, highlighted: bytes, page: int, positions: list[dict], measures: list[dict],
                 crop: tuple[float, float, float, float] | None = None) -> bytes:
    ET.register_namespace("", SVG_NS)
    original = ET.fromstring(plain)
    colored = ET.fromstring(highlighted)
    original_nodes, colored_nodes = list(original.iter()), list(colored.iter())
    if len(original_nodes) != len(colored_nodes):
        raise ValueError(f"MuseScore changed SVG structure on page {page + 1}")
    segment_ids = {value["id"] for value in positions}
    color_map = {marker_color(value): value for value in segment_ids}
    marked: set[int] = set()
    def mark(node: ET.Element, segment_id: int) -> None:
        identity = f"segment-{segment_id}"
        node.set("data-id", identity)
        node.set("data-segment-id", str(segment_id))
        classes = set(node.get("class", "").split())
        classes.update(("note", "pt-segment"))
        node.set("class", " ".join(sorted(classes)))
        marked.add(segment_id)
    for node, colored_node in zip(original_nodes, colored_nodes):
        if local_name(node.tag) != local_name(colored_node.tag):
            raise ValueError(f"MuseScore changed SVG element order on page {page + 1}")
        segment_id = extract_marker(colored_node, color_map)
        if segment_id is None:
            continue
        mark(node, segment_id)
    expected = {value for value in segment_ids if value >= 0}
    # MuseScore occasionally leaves the final sounding segment uncolored. Its
    # official position rectangle still lets us identify the noteheads safely.
    for position in positions:
        if position["id"] in marked:
            continue
        # The rectangle is a playback anchor, not a glyph box; stems and
        # noteheads may extend roughly one spatium beyond it.
        left, right = position["x"] / 12 - 250, (position["x"] + position["sx"]) / 12 + 250
        top, bottom = position["y"] / 12 - 250, (position["y"] + position["sy"]) / 12 + 250
        for node in original_nodes:
            if node.get("data-id"):
                continue
            if not ({"Note", "Rest"} & set(node.get("class", "").split())):
                continue
            numbers = re.findall(r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)", node.get("transform", ""))
            if len(numbers) >= 2 and left <= float(numbers[-2]) <= right and top <= float(numbers[-1]) <= bottom:
                mark(node, position["id"])
    if marked != expected:
        missing = sorted(expected - marked)
        raise ValueError(f"SVG page {page + 1}: musical segments were not annotated: {missing[:12]}")

    if crop and positions and crop[1] > 0:
        # The crop begins in the vertical gap before a system so that its chord
        # symbols remain visible.  MuseScore noteheads/stems can protrude into
        # that gap from the preceding system; hide only that protrusion.
        mask_bottom = min(value["y"] / 12 for value in positions) - 450
        if mask_bottom > crop[1]:
            ET.SubElement(original, f"{{{SVG_NS}}}rect", {
                "class": "pt-crop-mask", "x": "0", "y": f"{crop[1]:.3f}",
                "width": f"{crop[2]:.3f}", "height": f"{mask_bottom - crop[1]:.3f}",
                "fill": "#ffffff", "stroke": "none", "pointer-events": "none",
            })
    overlay = ET.SubElement(original, f"{{{SVG_NS}}}g", {"class": "pt-measures"})
    for measure in measures:
        if measure["page"] != page:
            continue
        group = ET.SubElement(overlay, f"{{{SVG_NS}}}g", {
            "class": "measure pt-measure", "data-n": str(measure["number"]),
        })
        ET.SubElement(group, f"{{{SVG_NS}}}rect", {
            "x": f'{measure["x"] / 12:.3f}', "y": f'{measure["y"] / 12:.3f}',
            "width": f'{measure["sx"] / 12:.3f}', "height": f'{measure["sy"] / 12:.3f}',
            "fill": "none", "stroke": "none", "pointer-events": "none",
        })
    if crop:
        x, y, width, height = crop
        original.set("viewBox", f"{x:.3f} {y:.3f} {width:.3f} {height:.3f}")
        original.set("width", f"{width:.3f}")
        original.set("height", f"{height:.3f}")
    return ET.tostring(original, encoding="utf-8", xml_declaration=True)


def page_slices(page: int, plain: bytes, positions: list[dict], measures: list[dict]) -> list[tuple[list[dict], list[dict], tuple[float, float, float, float]]]:
    root = ET.fromstring(plain)
    view_box = [float(value) for value in root.attrib["viewBox"].split()]
    _, _, width, full_height = view_box
    page_measures = [value for value in measures if value["page"] == page]
    system_y = sorted({round(value["y"] / 12, 1) for value in page_measures})
    systems = []
    for y in system_y:
        entries = [value for value in page_measures if abs(value["y"] / 12 - y) < 2]
        systems.append({"y": y, "bottom": max(value["y"] / 12 + value["sy"] / 12 for value in entries)})
    result = []
    for offset in range(0, len(system_y), 2):
        selected_y = system_y[offset:offset + 2]
        selected_measures = [value for value in page_measures
                             if min(abs(value["y"] / 12 - y) for y in selected_y) < 2]
        selected_positions = [value for value in positions if value["page"] == page and
                              min(abs(value["y"] / 12 - y) for y in selected_y) < 2]
        # Boundaries must lie outside real grand-staff bounds.  A midpoint can
        # still pass through a tall bass staff; fixed aspect-ratio padding can
        # pull chord symbols from the following system into this crop.
        first, last = offset, offset + len(selected_y) - 1
        top = 0.0 if first == 0 else systems[first - 1]["bottom"] + 15
        if last == len(systems) - 1:
            bottom = full_height
        else:
            bottom = min(systems[last]["bottom"] + 250, systems[last + 1]["y"] - 350)
        result.append((selected_positions, selected_measures, (0.0, top, width, bottom - top)))
    return result


def vlq(value: int) -> bytes:
    encoded = bytearray([value & 0x7F])
    value >>= 7
    while value:
        encoded.insert(0, 0x80 | (value & 0x7F))
        value >>= 7
    return bytes(encoded)


def midi_track(messages: list[tuple[int, bytes]]) -> bytes:
    body, previous = bytearray(), 0
    for tick, message in sorted(messages, key=lambda item: (item[0], 0 if item[1][0] & 0xF0 == 0x80 else 1)):
        body += vlq(tick - previous) + message
        previous = tick
    body += b"\x00\xff\x2f\x00"
    return b"MTrk" + struct.pack(">I", len(body)) + body


def make_midi(events: list[dict], tempo: int) -> bytes:
    micros = round(60_000_000 / max(1, tempo))
    conductor = midi_track([(0, b"\xff\x51\x03" + micros.to_bytes(3, "big"))])
    tracks = []
    for hand, channel in (("right", 0), ("left", 1)):
        messages = []
        for event in events:
            if event["hand"] != hand:
                continue
            messages.append((event["onTick"], bytes((0x90 | channel, event["pitch"], event["velocity"]))))
            messages.append((event["offTick"], bytes((0x80 | channel, event["pitch"], 0))))
        tracks.append(midi_track(messages))
    return b"MThd" + struct.pack(">IHHH", 6, 1, 3, PPQ) + conductor + b"".join(tracks)


def score_slots(elements: list[dict], position_count: int) -> list[tuple[int, float]]:
    keys = sorted({(int(item["measureIdx"]), float(item["beat"])) for item in elements
                   if item.get("type") in {"Note", "Chord", "Rest"}})
    if len(keys) != position_count:
        raise ValueError(f"semantic/position segment mismatch: {len(keys)} vs {position_count}")
    return keys


def make_practice(musicxml: Path, elements: list[dict], positions: list[dict]) -> tuple[dict, bytes, dict]:
    _, notes, measures = parse_score(musicxml, PPQ)
    slots = score_slots(elements, len(positions))
    segments_by_key: dict[tuple[int, float], list[int]] = defaultdict(list)
    for key, position in zip(slots, positions):
        segments_by_key[key].append(position["id"])

    measure_index = {measure["number"]: index for index, measure in enumerate(measures)}
    unique: dict[tuple[int, int, str], dict] = {}
    for note in sorted((value for value in notes if not value.tie_stop), key=lambda value: (value.on_tick, value.staff, value.pitch)):
        hand = "right" if note.staff == "1" else "left"
        key = (note.on_tick, note.pitch, hand)
        chain = tied_chain(note, notes)
        event = unique.get(key)
        if event is None:
            idx = measure_index[note.measure]
            beat = round((note.on_tick - measures[idx]["startTick"]) / PPQ, 6)
            segment_ids = segments_by_key.get((idx, beat), [])
            if not segment_ids:
                raise ValueError(f"no rendered segment for measure {note.measure}, beat {beat}")
            event = {
                "id": f"note-{len(unique):05d}", "pitch": note.pitch, "velocity": 80,
                "onTick": note.on_tick, "offTick": max(item.off_tick for item in chain),
                "track": 0 if hand == "right" else 1, "channel": 0 if hand == "right" else 1,
                "hand": hand, "measure": note.measure, "expectedGroupId": f"tick-{note.on_tick}",
                "scoreNoteIds": [f"segment-{value}" for value in segment_ids],
            }
            unique[key] = event
        else:
            event["offTick"] = max(event["offTick"], *(item.off_tick for item in chain))
    events = sorted(unique.values(), key=lambda value: (value["onTick"], value["hand"], value["pitch"]))
    for event in events:
        if event["offTick"] <= event["onTick"]:
            event["offTick"] = event["onTick"] + PPQ // 8
    hands = {hand: sum(event["hand"] == hand for event in events) for hand in ("left", "right")}
    if not all(hands.values()):
        raise ValueError(f"both piano hands are required, got {hands}")
    mapping = {"schemaVersion": 3, "kind": "musescore-native", "ppq": PPQ,
               "measures": measures, "events": events}
    return mapping, make_midi(events, 120), {"eventsByHand": hands, "segments": len(positions)}


def position_data(xml_data: bytes, count: int | None = None) -> tuple[list[dict], list[dict]]:
    root = ET.fromstring(xml_data)
    elements = [{key: (int(value) if key in {"id", "page"} else float(value)) for key, value in item.attrib.items()}
                for item in root.findall(".//element")]
    events = [{key: int(value) for key, value in item.attrib.items()} for item in root.findall(".//event")]
    if count is not None and len(elements) != count:
        raise ValueError(f"unexpected position count: {len(elements)} instead of {count}")
    return elements, events


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def convert(source: Path, output: Path, executable: str) -> dict:
    if source.suffix.lower() != ".mscz" or not source.is_file():
        raise ValueError("input must be an existing .mscz file")
    if not shutil.which(executable):
        raise ValueError(f"MuseScore executable not found: {executable}")
    with tempfile.TemporaryDirectory(prefix="pianoscore-") as temporary:
        temp = Path(temporary)
        media_path, elements_path = temp / "media.json", temp / "elements.json"
        run_musescore(executable, ["--score-media", str(source)], media_path)
        run_musescore(executable, ["--score-elements", str(source)], elements_path)
        media, element_sets = json.loads(media_path.read_text()), json.loads(elements_path.read_text())
        piano_sets = [value for value in element_sets if value.get("instrumentId") == "piano"]
        if len(piano_sets) != 1:
            raise ValueError(f"expected exactly one piano part, found {len(piano_sets)}")
        metadata = media["metadata"]
        if not metadata.get("parts") or not metadata["parts"][0].get("hasPitchedStaff") == "true":
            raise ValueError("MuseScore did not report a pitched piano staff")
        musicxml = decode_musicxml(media["mxml"])
        musicxml_path = temp / "score.musicxml"
        musicxml_path.write_bytes(musicxml)
        positions, position_events = position_data(decode(media["sposXML"]))
        semantic_position_count = len({(int(item["measureIdx"]), float(item["beat"]))
                                       for item in piano_sets[0]["elements"]
                                       if item.get("type") in {"Note", "Chord", "Rest"}})
        # MuseScore's spos export may finish with a playback boundary that has
        # no printed glyph. It must not be treated as a score position.
        if len(positions) == semantic_position_count + 1:
            positions = positions[:-1]
        measure_positions, measure_events = position_data(decode(media["mposXML"]), int(metadata["measures"]))
        for index, measure in enumerate(measure_positions):
            measure["number"] = index + 1
        mapping, midi, audit = make_practice(musicxml_path, piano_sets[0]["elements"], positions)
        tempo = int(round(float(metadata.get("tempo", 120))))
        midi = make_midi(mapping["events"], tempo)

        beats_by_element: dict[int, list[str]] = defaultdict(list)
        for beat_index, event in enumerate(position_events):
            beats_by_element[event["elid"]].append(str(beat_index))
        highlight = {"highlight": [{"beats": beats, "color": marker_color(element_id)}
                                   for element_id, beats in sorted(beats_by_element.items())]}
        highlight_path, highlighted_media_path = temp / "highlight.json", temp / "highlighted.json"
        highlight_path.write_text(json.dumps(highlight, separators=(",", ":")))
        run_musescore(executable, ["--score-media", "--highlight-config", str(highlight_path), str(source)], highlighted_media_path)
        highlighted_media = json.loads(highlighted_media_path.read_text())
        plain_pages, highlighted_pages = media["svgs"], highlighted_media["svgs"]
        if len(plain_pages) != len(highlighted_pages):
            raise ValueError("plain and highlighted MuseScore exports have different page counts")
        pages, page_names = {}, []
        for page, (plain, colored) in enumerate(zip(plain_pages, highlighted_pages)):
            plain_data, colored_data = decode(plain), decode(colored)
            for page_positions, page_measures, crop in page_slices(page, plain_data, positions, measure_positions):
                name = f"pages/normal/page-{len(page_names) + 1}.svg"
                page_names.append(name)
                pages[name] = annotate_svg(plain_data, colored_data, page, page_positions, page_measures, crop)

        members = {"source.mid": midi, "score.musicxml": musicxml,
                   "mapping.json": json.dumps(mapping, ensure_ascii=False, indent=2).encode(), **pages}
        package_id = str(uuid.uuid5(uuid.NAMESPACE_URL, sha256(source.read_bytes())))
        manifest = {
            "schemaVersion": 3, "id": package_id,
            "title": metadata.get("title") or source.stem,
            "sourceFormat": "mscz", "sourceMidi": "source.mid", "score": "score.musicxml",
            "mapping": "mapping.json", "pages": {"normal": page_names},
            "sha256": {name: sha256(data) for name, data in members.items()},
            "generator": {"name": "score-preparer", "museScoreVersion": metadata.get("mscoreVersion", "")},
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            for name, data in members.items():
                archive.writestr(name, data)
        audit.update({"title": manifest["title"], "pages": len(page_names), "measures": len(measure_positions),
                      "events": len(mapping["events"]), "packageId": package_id, "output": str(output)})
        return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="MuseScore .mscz file")
    parser.add_argument("--output", type=Path, required=True, help="resulting .pianoscore")
    parser.add_argument("--musescore", default="mscore", help="MuseScore CLI executable")
    args = parser.parse_args()
    print(json.dumps(convert(args.input.expanduser().resolve(), args.output.expanduser().resolve(), args.musescore),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
