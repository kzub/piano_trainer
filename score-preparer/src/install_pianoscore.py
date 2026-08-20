#!/usr/bin/env python3
"""Install a verified .pianoscore directly into a debug Android application."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


def adb(serial: str, *arguments: str, capture: bool = False) -> str:
    result = subprocess.run(["adb", "-s", serial, *arguments], check=True,
                            stdout=subprocess.PIPE if capture else None, text=True)
    return result.stdout.strip() if capture else ""


def validate(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("schemaVersion") not in (1, 2, 3):
            raise ValueError("unsupported .pianoscore schema")
        package_id = str(manifest["id"])
        if not re.fullmatch(r"[0-9a-fA-F-]{36}", package_id):
            raise ValueError("invalid package id")
        for name, expected in manifest.get("sha256", {}).items():
            actual = hashlib.sha256(archive.read(name)).hexdigest()
            if actual.lower() != str(expected).lower():
                raise ValueError(f"checksum mismatch: {name}")
        return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    parser.add_argument("--serial", help="ADB serial; auto-detected when exactly one device is connected")
    parser.add_argument("--app-id", default="com.konstantin.pianotrainer")
    parser.add_argument("--no-launch", action="store_true")
    args = parser.parse_args()
    source = args.package.expanduser().resolve()
    manifest = validate(source)
    serial = args.serial
    if not serial:
        lines = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True).stdout.splitlines()[1:]
        devices = [line.split()[0] for line in lines if line.strip().endswith("device")]
        if len(devices) != 1:
            raise ValueError(f"expected exactly one ADB device, found {len(devices)}")
        serial = devices[0]
    remote = f'/data/local/tmp/{manifest["id"]}.pianoscore'
    target = f'files/scores/{manifest["id"]}.pianoscore'
    adb(serial, "push", str(source), remote)
    try:
        adb(serial, "shell", "run-as", args.app_id, "mkdir", "-p", "files/scores")
        adb(serial, "shell", "run-as", args.app_id, "cp", remote, target)
        installed = adb(serial, "shell", "run-as", args.app_id, "ls", "-l", target, capture=True)
    finally:
        adb(serial, "shell", "rm", "-f", remote)
    if not args.no_launch:
        adb(serial, "shell", "am", "force-stop", args.app_id)
        adb(serial, "shell", "am", "start", "-n", f"{args.app_id}/.MainActivity")
    print(json.dumps({"serial": serial, "title": manifest["title"], "target": target,
                      "installed": installed}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
