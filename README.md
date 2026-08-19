# Piano Trainer

Локальный Android-тренажёр для Samsung SM-X230 и Roland FP-30.

## Структура

- `android-app` — Android-приложение.
- `score-preparer` — Mac CLI для сборки проверяемого `.pianoscore`.
- `pianoscore-schema` — контракт пакета композиции.
- `docs` — решения и сценарии проверки.

## Документация

- [Текущее состояние MVP](docs/current-status.md) — реализованные функции, реальные ограничения и проверка на устройстве.
- [План доработки](docs/roadmap.md) — сверка с первоначальными требованиями и оставшаяся работа.
- [Контекст следующего сеанса](docs/handoff.md) — краткий handoff: устройство, команды сборки и важные технические решения.

## Первый запуск

1. Откройте `android-app` в Android Studio.
2. Установите Android SDK Platform 37 и Build Tools 36.0.0.
3. Соберите `./gradlew :app:assembleDebug`.

Подготовленный контент и APK не попадают в Git.
