# Контекст для продолжения в новом сеансе

## Проект

- Workspace: `/Users/konstantin/projects.my/piano_trainer`
- Android module: `android-app`
- Application ID: `com.konstantin.pianotrainer`
- Устройство: Samsung SM-X230, Android 16 / One UI 8.5, landscape.
- Пианино: Roland FP-30; BLE MIDI подтверждён пользователем, USB MIDI также поддерживается.
- Пользователь просит всегда устанавливать новые сборки через ADB и показывать новый номер версии в приложении.

## Текущая реализация

- Native Kotlin + Compose, Android SDK 37, minSdk 31, targetSdk 36.
- Версия динамическая: `versionName = 0.1.0-<epoch-seconds>` в `android-app/app/build.gradle.kts`.
- Main UI/score viewer: `android-app/app/src/main/java/com/konstantin/pianotrainer/MainActivity.kt`.
- MIDI transport: `MidiController.kt`.
- MIDI parser/scheduler: `MidiPlayback.kt`.
- Waiting mode: `PracticeEngine.kt`.
- `.pianoscore` import/content API: `ScorePackageRepository.kt`.
- Документация: `docs/current-status.md`, `docs/roadmap.md`.

## Контент и исходный пример

- Исходный MIDI пользователя: `/Users/konstantin/Drive/Hobby/Piano/Listen_To_Your_Heart__DHT_Roxette_1786967414114.mid`.
- Подготовленные артефакты лежат в игнорируемой папке `local-content/`.
- Текущий пакет на планшете был заменён debug-командой и содержит SVG с `data-n`, `data-pname`, `data-oct`.
- Пакетный pipeline: MuseScore → MusicXML → `generate_timeline_mapping.py` → `render_pages.py` (Verovio) → `prepare_score.py`.
- В mapping текущего примера есть `hand: left/right`, но `scoreNoteIds` пусты. Поэтому SVG-подсветка эвристическая.

## Сборка и установка

Обычная сборка:

```sh
cd /Users/konstantin/projects.my/piano_trainer/android-app
./gradlew :app:assembleDebug
```

Если sandbox не может писать в `~/.gradle`, выполнять сборку с повышенным разрешением. В одном случае автоматическая проверка разрешения истекла по тайм-ауту; повторная попытка прошла успешно.

Установка по ADB (путь к исходному APK для escalated команды лучше сначала копировать во временный каталог):

```sh
cp app/build/outputs/apk/debug/app-debug.apk /private/tmp/piano-trainer-debug.apk
/Users/konstantin/Library/Android/sdk/platform-tools/adb devices -l
/Users/konstantin/Library/Android/sdk/platform-tools/adb install -r /private/tmp/piano-trainer-debug.apk
/Users/konstantin/Library/Android/sdk/platform-tools/adb shell monkey -p com.konstantin.pianotrainer -c android.intent.category.LAUNCHER 1
```

Последний известный serial: `R5GL63ELKFR`; не предполагать, что он подключён — всегда проверять `adb devices -l`.

После переустановки debug APK MIDI-подключение обычно нужно выполнить заново через экран MIDI.

## Последние изменения перед handoff

Добавлены режимы ожидающего обучения `Обе`, `Левая`, `Правая`. Выбор находится в компактном меню кнопки `Обе` в верхней строке. Режим одной руки формирует учебные группы только из `leftPitches` или `rightPitches`; невыбранная рука не блокирует продвижение.

Последняя установленная версия до первого Git-коммита: `v0.1.0-1787084104`.

## Рекомендуемая первая задача следующего сеанса

Реализовать непрерывную оценку пользователя во время Play (`Слушать`): сравнение входящих MIDI Note On с текущими event groups в окне допуска, правильные/неправильные ноты на SVG и небольшая статистика. См. подробный план в `docs/roadmap.md`.
