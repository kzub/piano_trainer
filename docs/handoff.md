# Контекст для продолжения работы

## Среда

- Workspace: `/Users/konstantin/projects.my/piano_trainer`
- Android module: `android-app`; package: `com.konstantin.pianotrainer`.
- Планшет: Samsung SM-X230, Android 16 / One UI 8.5. Последний ADB serial:
  `R5GL63ELKFR` — всегда сначала проверять `adb devices -l`.
- Пианино: Roland FP-30. BLE MIDI подтверждён пользователем; USB — supported fallback.
- Пользователь просит ставить каждую новую сборку через ADB. Номер версии автоматически
  меняется в `android-app/app/build.gradle.kts` и виден в шапке приложения.

## Состояние на 2026-08-20

- Последняя установленная проверенная сборка отображала `v0.1.0-1787255756`.
- `Listen to Your Heart` пересобрана в schema v3 `.pianoscore`, установлена напрямую
  в приватное хранилище приложения и содержит 9 SVG-страниц MuseScore, нарезанных
  между системами для альбомного экрана.
- 817 MIDI events имеют точную связь с 459 MuseScore SVG-сегментами; правая рука —
  445 событий, левая — 372. Не возвращаться к подбору ноты только по высоте/X.
- `WaitingPractice` повторяет произведение/выбранный сегмент после последней группы.
  Play делает то же самое: `onFinished` вызывает `restartPlaybackFrom(selectedFromTick)`.
- Интервал задаётся курсором, а не касанием партитуры: `←`/`→` двигают `browseCursorTick`
  по тактам, `[`/`]` помечают такт под курсором. Обе границы привязаны к границам такта,
  `]` берёт `startTick + durationTicks - 1`. Не возвращаться к границе по началу такта:
  тогда в интервал попадала только первая нота такта, и она звучала за пределами выделения.
- Красные маркеры живут 250 мс, затем сохраняются только у удерживаемых клавиш.
- `MidiController` декодирует весь входной MIDI callback. Это критично: старая версия
  читала только первые три байта и могла пропускать Note Off при batched MIDI.
- Play имеет скорости 0,25×, 0,5×, 0,75×, 1×, 1,25×, 1,5×; выбор доступен до запуска.

## Основные файлы

- `MainActivity.kt` — Compose UI, курсор и выбор диапазона `[`/`]`, выбор рук/скорости,
  WebView SVG overlay.
- `MidiController.kt` — BLE/USB MIDI, полный decoder входящих сообщений.
- `MidiPlayback.kt` — parser/scheduler MIDI, скорость Play, All Notes Off.
- `PracticeEngine.kt` — ожидающее обучение и Play feedback.
- `ScorePackageRepository.kt` — import, schema v1–v3, проверка SHA-256 всех SVG и mapping.
- `score-preparer/src/prepare_mscz.py` — основной `.mscz → .pianoscore` v3 pipeline.
- `score-preparer/src/install_pianoscore.py` — проверенная прямая установка в debug-приложение через ADB.
- `score-preparer/README.md` — актуальные команды конвертации, audit и установки.
- `docs/review-2026-08-19.md` — технический review и известные риски.

## Сборка, тесты, установка

```sh
cd /Users/konstantin/projects.my/piano_trainer/android-app
./gradlew :app:testDebugUnitTest :app:assembleDebug
/Users/konstantin/Library/Android/sdk/platform-tools/adb devices -l
/Users/konstantin/Library/Android/sdk/platform-tools/adb -s R5GL63ELKFR \
  install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle может требовать повышенного разрешения из-за записи в `~/.gradle`.

Чтобы доставить новый score package в debug-сборку без Downloads:

```sh
python3 score-preparer/src/install_pianoscore.py \
  local-content/listen-to-your-heart-roxette.pianoscore --serial R5GL63ELKFR
```

## Следующий приоритет

Провести длительный тест на FP-30 по BLE и USB после decoder-fix: быстрые Note On/Off,
аккорды, педаль и удержания. Затем откалибровать отдельные окна допуска BLE/USB.
Подробный список оставшейся работы — в `docs/roadmap.md`.
