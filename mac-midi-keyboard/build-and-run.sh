#!/bin/zsh
set -euo pipefail
task_dir="${0:A:h}"
mkdir -p "$task_dir/build"
swiftc -parse-as-library -framework SwiftUI -framework CoreMIDI "$task_dir/MacMidiKeyboard.swift" -o "$task_dir/build/MacMidiKeyboard"
open "$task_dir/build/MacMidiKeyboard"
