# Архитектура MVP

```text
MuseScore .mscz
            │
            ▼
 Mac score-preparer ──► .pianoscore v3
                                 │
                                 ▼
          Android library / score viewer / practice engine
                                 │
                    ┌────────────┴────────────┐
                    ▼                         ▼
            Roland FP-30 (BLE/USB)       tablet audio
```

`.mscz` — единственный рекомендуемый вход: он хранит проверенную пользователем нотацию,
повторы и вёрстку MuseScore. Подготовщик штатно получает из него MusicXML, SVG и
координаты нот, создаёт учебный MIDI ровно для двух фортепианных рук и добавляет
контрольные суммы. Android не делает автоматическую транскрипцию MIDI в ноты.
