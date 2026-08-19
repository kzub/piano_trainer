# Архитектура MVP

```text
MIDI + проверенный MusicXML
            │
            ▼
   Mac score-preparer ──► .pianoscore
                                 │
                                 ▼
          Android library / score viewer / practice engine
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
            Roland FP-30 (BLE/USB)       tablet audio
```

Приложение не делает автоматическую транскрипцию MIDI в нотную запись. Эта рискованная операция остаётся явной частью подготовки на Mac; Android получает проверяемый пакет с оригинальным MIDI и отображаемой партитурой.
