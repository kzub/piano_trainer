# Score preparer

Утилиты создают проверяемый пакет `.pianoscore` для приложения Piano Trainer из MIDI.
Пакет содержит исходный MIDI, нотную запись MusicXML, MIDI timeline и SVG-страницы.

## Полный процесс подготовки

Нужны macOS, [MuseScore](https://musescore.org/) для конвертации MIDI в MusicXML и
[Verovio](https://www.verovio.org/) для SVG (`brew install verovio`). Все промежуточные
файлы удобно хранить в `local-content/`: эта директория намеренно не попадает в Git.

Пример для `local-content/l2uh.mid`:

```sh
# 1. Конвертировать MIDI в MusicXML. При необходимости открыть результат в MuseScore
#    и вручную исправить нотацию перед продолжением.
mscore -o local-content/l2uh.musicxml local-content/l2uh.mid

# 2. Назначить устойчивые MusicXML ID и построить точную MIDI → score mapping.
#    Команда завершится ошибкой, если атаки MIDI и MusicXML не совпадают: это
#    сигнал проверить импорт/ручную правку партитуры в MuseScore.
python3 score-preparer/src/generate_timeline_mapping.py \
  --midi local-content/l2uh.mid \
  --musicxml local-content/l2uh.musicxml \
  --normalized-musicxml local-content/l2uh.normalized.musicxml \
  --output local-content/l2uh.mapping.json

# 3. Отрендерить страницы партитуры для приложения.
python3 score-preparer/src/render_pages.py \
  --musicxml local-content/l2uh.normalized.musicxml \
  --output-dir local-content/l2uh-pages-normal \
  --scale 100

# 4. Собрать итоговый файл, который нужно импортировать в тренер.
python3 score-preparer/src/prepare_score.py \
  --midi local-content/l2uh.mid \
  --musicxml local-content/l2uh.normalized.musicxml \
  --mapping local-content/l2uh.mapping.json \
  --title "Listen to Your Heart" \
  --pages-dir local-content/l2uh-pages-normal \
  --output local-content/l2uh.pianoscore

# 5. Проверить, что ZIP-пакет не повреждён.
unzip -t local-content/l2uh.pianoscore

# 6. Проверить пропорции страниц и полную MIDI → MusicXML/SVG привязку.
python3 score-preparer/src/audit_score_package.py local-content/l2uh.pianoscore \
  --target-ratio 1.976470588 --strict
```

По умолчанию `render_pages.py` использует профиль `3360×1720`, отключает нижний
колонтитул Verovio и поджимает высоту до фактического содержимого. После рендера
внешний и внутренний `viewBox` всех страниц нормализуются по самой высокой странице,
чтобы масштаб нот не менялся при перелистывании. Для проверочного `l2uh.pianoscore`
получились три страницы одинакового размера `3360×1700`, близкого к рабочей области
Piano Trainer на SM-X230 в альбомной ориентации. Другой исходный профиль можно задать
через `--page-width` и `--page-height`.

`generate_timeline_mapping.py` назначает каждой напечатанной MusicXML ноте стабильные
`xml:id` и обычный `id`. `render_pages.py` экспортирует их через Verovio в SVG `data-id`.
Для каждого MIDI Note On mapping содержит `scoreNoteIds`; они могут включать несколько
печатных нот одной tie-цепочки. Поэтому runtime не ищет «похожую» высоту на странице,
а обращается к конкретным SVG-элементам. `mapping.json` также содержит карту тактов с
реальными длительностями, а не предположение 4/4.

Руки определяются так: `generate_timeline_mapping.py` сначала назначает ноты ниже C4 левой руке, а C4 и выше —
правой. Если не менее 90% нот одного MIDI track/channel относятся к одной руке, весь
этот источник закрепляется за ней. Это сохраняет мелодию правой руки при кратком
переходе ниже C4 и бас при кратком переходе выше C4. Для действительно смешанных
источников остаётся понотная классификация, которую следует проверить вручную.
`audit_score_package.py --strict` завершится с ошибкой при отсутствующих SVG-ID или
неполной точной привязке; обычный режим выводит диагностический отчёт.

## Импорт на планшет

Обычный способ: перенесите `.pianoscore` на устройство любым способом и в приложении
выберите «Импортировать композицию». Это работает и для release-сборок.

Для отладки с подключённым по USB планшетом можно сначала положить файл в Downloads:

```sh
adb devices -l
adb -s <serial> push local-content/l2uh.pianoscore /sdcard/Download/
```

После этого выберите файл из `Downloads` системным файловым picker'ом приложения.
Для debug-сборки допускается автоматический импорт в приватный каталог приложения:

```sh
# Получить UUID из manifest.json внутри .pianoscore:
unzip -p local-content/l2uh.pianoscore manifest.json
# Подставить значение поля id вместо <package-uuid>:
adb -s <serial> push local-content/l2uh.pianoscore /data/local/tmp/l2uh.pianoscore
adb -s <serial> shell run-as com.konstantin.pianotrainer \
  cp /data/local/tmp/l2uh.pianoscore files/scores/<package-uuid>.pianoscore
adb -s <serial> shell rm /data/local/tmp/l2uh.pianoscore
```

Последняя команда требует, чтобы `run-as` был разрешён для установленной debug-сборки.
После замены пакета перезапустите приложение или вернитесь к списку композиций.

## Низкоуровневые команды

Если MusicXML и mapping уже подготовлены, достаточно выполнить сборку:

```text
python3 src/prepare_score.py \
  --midi /path/to/song.mid \
  --musicxml /path/to/song.musicxml \
  --mapping /path/to/mapping.json \
  --title "Song" \
  --pages-dir pages/normal \
  --output /path/to/song.pianoscore
```

Конвертация MIDI в MusicXML и визуальная коррекция пока выполняются в MuseScore. Скрипт намеренно валидирует вход, а не делает скрытые музыкальные догадки.

После проверки MusicXML создайте SVG-страницы для одного из трёх масштабов:

```text
python3 src/render_pages.py --musicxml corrected.musicxml --output-dir pages/normal --scale 100
```

Чтобы построить пакет с точной учебной привязкой, используйте полный вызов выше:

```text
python3 src/generate_timeline_mapping.py \
  --midi source.mid --musicxml corrected.musicxml \
  --normalized-musicxml normalized.musicxml --output mapping.json
```
