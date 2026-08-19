# `.pianoscore` v1

`.pianoscore` — ZIP-архив с расширением `.pianoscore`.

Обязательные файлы:

- `manifest.json` — версия схемы и SHA-256 вложений.
- `source.mid` — оригинальный Standard MIDI File для тайминга и playback.
- `score.musicxml` — проверенная нотная запись.
- `mapping.json` — соответствие между MIDI-событиями и нотами партитуры.

`mapping.json` хранит массив `events`. Каждое событие содержит `id`, `pitch`, `onTick`, `offTick`, `hand`, `expectedGroupId` и `scoreNoteIds`. Массив `scoreNoteIds` намеренно допускает несколько ID: одна MIDI-нота может стать несколькими связанными нотами.
