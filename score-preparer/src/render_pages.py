#!/usr/bin/env python3
"""Render reviewed MusicXML into one offline SVG score page per file."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--musicxml", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--scale", type=int, choices=(75, 100, 125), default=100)
    args = parser.parse_args()

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
        "--landscape",
        "--svg-view-box",
        "--svg-html5",
        "--svg-additional-attribute", "measure@n",
        "--svg-additional-attribute", "note@pname",
        "--svg-additional-attribute", "note@oct",
        "--svg-additional-attribute", "note@accid",
        "--svg-additional-attribute", "note@accid.ges",
        "--breaks", "auto",
        "--scale", str(args.scale),
        "-o", str(target),
        str(source),
    ]
    subprocess.run(command, check=True)
    pages = sorted(output.glob("page*.svg"))
    if not pages:
        raise RuntimeError("Verovio did not produce any SVG pages")
    print(f"Rendered {len(pages)} page(s) to {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"render-pages: {error}", file=sys.stderr)
        raise SystemExit(2)
