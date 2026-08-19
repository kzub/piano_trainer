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

# 2. Построить MIDI timeline. Он задаёт ожидаемые ноты и их группы по времени.
python3 score-preparer/src/generate_timeline_mapping.py \
  --midi local-content/l2uh.mid \
  --output local-content/l2uh.mapping.json

# 3. Отрендерить страницы партитуры для приложения.
python3 score-preparer/src/render_pages.py \
  --musicxml local-content/l2uh.musicxml \
  --output-dir local-content/l2uh-pages-normal \
  --scale 100

# 4. Собрать итоговый файл, который нужно импортировать в тренер.
python3 score-preparer/src/prepare_score.py \
  --midi local-content/l2uh.mid \
  --musicxml local-content/l2uh.musicxml \
  --mapping local-content/l2uh.mapping.json \
  --title "Listen to Your Heart" \
  --pages-dir local-content/l2uh-pages-normal \
  --output local-content/l2uh.pianoscore

# 5. Проверить, что ZIP-пакет не повреждён.
unzip -t local-content/l2uh.pianoscore
```

`generate_timeline_mapping.py` назначает ноты ниже C4 левой руке, а C4 и выше — правой.
Его `scoreNoteIds` пусты: пакет годится для отображения, воспроизведения
и текущего режима практики по MIDI-группам, но не для будущей подсветки конкретных нот
на партитуре. Для такой подсветки нужна вручную проверенная привязка MIDI к MusicXML.

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

Для теста просмотра и воспроизведения можно построить MIDI timeline без учебной привязки:

```text
python3 src/generate_timeline_mapping.py --midi source.mid --output mapping.json
```
