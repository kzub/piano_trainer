#!/usr/bin/env bash
# Usage: bash android-app/scripts/adb-midi.sh <serial> <on|off> <midi-pitch> [velocity]
set -euo pipefail

serial="$1"
state="$2"
pitch="$3"
velocity="${4:-100}"

case "$state" in
  on) pressed=true ;;
  off) pressed=false ;;
  *) echo "state must be on or off" >&2; exit 2 ;;
esac

adb -s "$serial" shell am broadcast \
  -a com.konstantin.pianotrainer.DEBUG_MIDI_NOTE \
  --ei pitch "$pitch" \
  --ei velocity "$velocity" \
  --ez pressed "$pressed" \
  -n com.konstantin.pianotrainer/.DebugMidiReceiver
