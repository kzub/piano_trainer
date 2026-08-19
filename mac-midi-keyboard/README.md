# Mac MIDI Keyboard

Локальная macOS-клавиатура для тестирования Android BLE MIDI.

1. В `Audio MIDI Setup → Window → Show MIDI Studio → Configure Bluetooth` нажмите **Advertise**.
2. На планшете откройте Piano Trainer → MIDI → поиск Bluetooth MIDI → выберите Mac.
3. Соберите и запустите:

```text
./build-and-run.sh
```

4. Нажмите **Обновить MIDI-выходы**, выберите появившийся Bluetooth MIDI output и кликайте ноты.
