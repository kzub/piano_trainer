import CoreMIDI
import SwiftUI

struct MidiDestination: Identifiable, Hashable {
    let endpoint: MIDIEndpointRef
    let name: String
    var id: MIDIEndpointRef { endpoint }
}

@MainActor
final class MidiKeyboard: ObservableObject {
    @Published var destinations: [MidiDestination] = []
    @Published var selected: MIDIEndpointRef = 0
    @Published var status = "Нажмите «Обновить MIDI-выходы»"

    private var client = MIDIClientRef()
    private var port = MIDIPortRef()

    init() {
        MIDIClientCreateWithBlock("PianoTrainerMacKeyboard" as CFString, &client) { _ in }
        MIDIOutputPortCreate(client, "Keyboard output" as CFString, &port)
        refresh()
    }

    deinit {
        MIDIPortDispose(port)
        MIDIClientDispose(client)
    }

    func refresh() {
        destinations = (0..<MIDIGetNumberOfDestinations()).compactMap { index in
            let endpoint = MIDIGetDestination(index)
            guard endpoint != 0 else { return nil }
            var value: Unmanaged<CFString>?
            MIDIObjectGetStringProperty(endpoint, kMIDIPropertyDisplayName, &value)
            return MidiDestination(endpoint: endpoint, name: (value?.takeRetainedValue() as String?) ?? "MIDI output \(index + 1)")
        }
        if !destinations.contains(where: { $0.endpoint == selected }) {
            selected = destinations.first?.endpoint ?? 0
        }
        status = destinations.isEmpty ? "MIDI-выходов нет. Подключите планшет по Bluetooth MIDI и обновите список." : "Выберите выход и нажимайте клавиши"
    }

    func play(note: UInt8) {
        guard selected != 0 else {
            status = "Сначала выберите MIDI-выход"
            return
        }
        send([0x90, note, 100])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in self?.send([0x80, note, 0]) }
        status = "Отправлена нота \(noteName(note))"
    }

    private func send(_ bytes: [UInt8]) {
        var packetList = MIDIPacketList()
        bytes.withUnsafeBufferPointer { buffer in
            withUnsafeMutablePointer(to: &packetList) { listPointer in
                let packet = MIDIPacketListInit(listPointer)
                _ = MIDIPacketListAdd(listPointer, MemoryLayout<MIDIPacketList>.size, packet, 0, buffer.count, buffer.baseAddress!)
                MIDISend(port, selected, listPointer)
            }
        }
    }
}

private func noteName(_ note: UInt8) -> String {
    let names = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
    return "\(names[Int(note) % 12])\(Int(note) / 12 - 1)"
}

@main
struct MacMidiKeyboardApp: App {
    @StateObject private var keyboard = MidiKeyboard()
    private let whiteNotes: [UInt8] = [48, 50, 52, 53, 55, 57, 59, 60, 62, 64, 65, 67, 69, 71, 72]

    var body: some Scene {
        WindowGroup("Mac MIDI Keyboard") {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Mac MIDI Keyboard").font(.title2).bold()
                    Spacer()
                    Button("Обновить MIDI-выходы") { keyboard.refresh() }
                }
                Picker("Bluetooth MIDI-выход", selection: $keyboard.selected) {
                    if keyboard.destinations.isEmpty {
                        Text("Нет устройств").tag(MIDIEndpointRef(0))
                    } else {
                        ForEach(keyboard.destinations) { destination in
                            Text(destination.name).tag(destination.endpoint)
                        }
                    }
                }
                .pickerStyle(.menu)
                Text(keyboard.status).foregroundStyle(.secondary)
                HStack(spacing: 2) {
                    ForEach(whiteNotes, id: \.self) { note in
                        Button(action: { keyboard.play(note: note) }) {
                            Text(noteName(note)).font(.caption).frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .frame(width: 58, height: 150)
                    }
                }
                Text("Диапазон: C3–C5. Окно отправляет Note On/Off через выбранный MIDI-выход.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .padding(20)
            .frame(minWidth: 940, minHeight: 300)
        }
    }
}
