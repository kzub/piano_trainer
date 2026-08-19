#!/usr/bin/env python3
"""Render reviewed MusicXML into one offline SVG score page per file."""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path


VIEW_BOX_RE = re.compile(
    r'viewBox="(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)"'
)


def normalize_page_heights(pages: list[Path]) -> float:
    parsed: list[tuple[Path, str, list[re.Match[str]]]] = []
    for page in pages:
        svg = page.read_text(encoding="utf-8")
        matches = list(VIEW_BOX_RE.finditer(svg))
        if len(matches) < 2:
            raise RuntimeError(f"SVG page has no viewBox: {page}")
        parsed.append((page, svg, matches))
    height = max(float(matches[0].group(4)) for _, _, matches in parsed)
    normalized_height = f"{height:g}"
    for page, svg, matches in parsed:
        outer, inner = matches[:2]
        scale = float(inner.group(4)) / float(outer.group(4))
        replacements = [
            (outer, normalized_height),
            (inner, f"{height * scale:g}"),
        ]
        for match, replacement_height in reversed(replacements):
            replacement = (
                f'viewBox="{match.group(1)} {match.group(2)} '
                f'{match.group(3)} {replacement_height}"'
            )
            svg = svg[:match.start()] + replacement + svg[match.end():]
        page.write_text(svg, encoding="utf-8")
    return height


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--musicxml", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--scale", type=int, choices=(75, 100, 125), default=100)
    parser.add_argument("--page-width", type=int, default=3360,
                        help="SVG page width; default matches the tablet score viewport")
    parser.add_argument("--page-height", type=int, default=1720,
                        help="SVG page height; default matches the tablet score viewport")
    args = parser.parse_args()

    if args.page_width <= 0 or args.page_height <= 0:
        parser.error("page dimensions must be positive")

    source = args.musicxml.expanduser().resolve()
    if not source.is_file():
        parser.error(f"MusicXML not found: {source}")
    verovio = shutil.which("verovio")
    if not verovio:
        parser.error("Verovio is not installed; run `brew install verovio`")

    output = args.output_dir.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    target = output / "page.svg"
    command = [
        verovio,
        "--input-from", "xml",
        "--all-pages",
        "--page-width", str(args.page_width),
        "--page-height", str(args.page_height),
        "--footer", "none",
        "--adjust-page-height",
        "--svg-view-box",
        "--svg-html5",
        "--svg-additional-attribute", "measure@n",
        "--svg-additional-attribute", "note@pname",
        "--svg-additional-attribute", "note@oct",
        "--svg-additional-attribute", "note@accid",
        "--svg-additional-attribute", "note@accid.ges",
        "--svg-additional-attribute", "note@id",
        "--breaks", "auto",
        "--scale", str(args.scale),
        "-o", str(target),
        str(source),
    ]
    subprocess.run(command, check=True)
    pages = sorted(output.glob("page*.svg"))
    if not pages:
        raise RuntimeError("Verovio did not produce any SVG pages")
    normalized_height = normalize_page_heights(pages)
    print(f"Rendered {len(pages)} page(s) to {output}; normalized height {normalized_height:g}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"render-pages: {error}", file=sys.stderr)
        raise SystemExit(2)
