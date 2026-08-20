# `.pianoscore` schema v3

`.pianoscore` — ZIP-архив с расширением `.pianoscore`.

Поддерживаемый Android schema v3 создаётся из MuseScore `.mscz`. Пакеты schema 1–2
остаются импортируемыми для обратной совместимости, но для новых произведений следует
использовать v3.

Обязательные файлы:

- `manifest.json` — версия схемы и SHA-256 вложений.
- `source.mid` — учебный Standard MIDI File: только правая (channel 0) и левая
  (channel 1) фортепианные руки.
- `score.musicxml` — семантический экспорт исходной партитуры.
- `mapping.json` — события учебного MIDI, такты и точная SVG-привязка.
- `pages/normal/page-*.svg` — страницы MuseScore, нарезанные между системами для
  ландшафтного планшета.

`manifest.json` содержит `schemaVersion: 3`, `id`, `title`, имена этих файлов, список
страниц `pages.normal` и SHA-256 каждого вложения. Android проверяет контрольные суммы
всех обязательных файлов и SVG-страниц перед импортом.

`mapping.json` хранит `kind: "musescore-native"`, `ppq`, массив `measures` и массив
`events`. Событие содержит `id`, `pitch`, `onTick`, `offTick`, `hand`,
`expectedGroupId`, `measure` и `scoreNoteIds`. В v3 `scoreNoteIds` — это
`segment-<id>`: ID точных графических элементов, выделенных MuseScore для данного
музыкального момента. Один сегмент может объединять ноты аккорда обеих рук.
