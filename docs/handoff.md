# Контекст для продолжения работы

## Среда

- Workspace: `/Users/konstantin/projects.my/piano_trainer`
- Android module: `android-app`; package: `com.konstantin.pianotrainer`.
- Планшет: Samsung SM-X230, Android 16 / One UI 8.5. Последний ADB serial:
  `R5GL63ELKFR` — всегда сначала проверять `adb devices -l`.
- Пианино: Roland FP-30. BLE MIDI подтверждён пользователем; USB — supported fallback.
- Пользователь просит ставить каждую новую сборку через ADB. Номер версии автоматически
  меняется в `android-app/app/build.gradle.kts` и виден в шапке приложения.

## Состояние на 2026-08-19

- Последняя установленная сборка: `v0.1.0-1787170049`.
- `Listen to Your Heart` пересобрана в schema v2 `.pianoscore`, импортирована на
  планшет и содержит три SVG-страницы 3360×1700.
- 918/918 MIDI events имеют точную связь с SVG ID; не возвращаться к подбору ноты
  только по высоте/X без явной причины.
- `WaitingPractice` повторяет произведение/выбранный сегмент после последней группы.
- Красные маркеры живут 250 мс, затем сохраняются только у удерживаемых клавиш.
- `MidiController` декодирует весь входной MIDI callback. Это критично: старая версия
  читала только первые три байта и могла пропускать Note Off при batched MIDI.
- Play имеет скорости 0,25×, 0,5×, 0,75×, 1×, 1,25×, 1,5×; выбор доступен до запуска.

## Основные файлы

- `MainActivity.kt` — Compose UI, выбор диапазона/рук/скорости, WebView SVG overlay.
- `MidiController.kt` — BLE/USB MIDI, полный decoder входящих сообщений.
- `MidiPlayback.kt` — parser/scheduler MIDI, скорость Play, All Notes Off.
- `PracticeEngine.kt` — ожидающее обучение и Play feedback.
- `ScorePackageRepository.kt` — import, schema v1/v2, exact mapping и карта тактов.
- `score-preparer/src/generate_timeline_mapping.py` — MusicXML ID и exact mapping.
- `score-preparer/src/render_pages.py` — Verovio SVG без footer, нормализация viewBox.
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

Чтобы доставить новый score package, положить его в Downloads и импортировать через UI:

```sh
adb -s R5GL63ELKFR push local-content/l2uh-v2.pianoscore \
  /sdcard/Download/Listen_To_Your_Heart_v2.pianoscore
```

## Следующий приоритет

Провести длительный тест на FP-30 по BLE и USB после decoder-fix: быстрые Note On/Off,
аккорды, педаль и удержания. Затем откалибровать отдельные окна допуска BLE/USB.
Подробный список оставшейся работы — в `docs/roadmap.md`.
