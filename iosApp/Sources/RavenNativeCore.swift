import Foundation
import RavenBridge

/// Swift wrapper around the exact same C++ emulator cores as Android.
/// Load only legally sourced game files selected by the user.
final class RavenNativeCore {
    enum System: Int32 { case gb = 0, gbc = 1, gba = 2 }
    private var handle: OpaquePointer?

    init?(system: System) {
        guard ravenemu_api_version() == RAVENEMU_API_VERSION else { return nil }
        guard let value = ravenemu_create(system.rawValue) else { return nil }
        self.handle = value
    }

    deinit {
        ravenemu_destroy(handle)
    }

    var width: Int { Int(ravenemu_video_width(handle)) }
    var height: Int { Int(ravenemu_video_height(handle)) }
    var fps: Double { ravenemu_video_fps(handle) }
    var audioRate: Int { Int(ravenemu_audio_rate(handle)) }
    var lastError: String { String(cString: ravenemu_last_error()) }

    func load(rom: Data, battery: Data? = nil) -> Bool {
        guard let handle else { return false }
        return rom.withUnsafeBytes { romBytes in
            if let battery {
                return battery.withUnsafeBytes { batteryBytes in
                    ravenemu_load(handle,
                        romBytes.bindMemory(to: UInt8.self).baseAddress, rom.count,
                        batteryBytes.bindMemory(to: UInt8.self).baseAddress, battery.count) != 0
                }
            }
            return ravenemu_load(handle,
                romBytes.bindMemory(to: UInt8.self).baseAddress, rom.count, nil, 0) != 0
        }
    }

    /// Packed 0xAARRGGBB output. Call from a single serial emulation queue.
    func runFrame() -> [UInt32]? {
        guard let handle else { return nil }
        var pixels = [UInt32](repeating: 0, count: width * height)
        let success = pixels.withUnsafeMutableBufferPointer {
            ravenemu_run_frame(handle, $0.baseAddress, $0.count) != 0
        }
        return success ? pixels : nil
    }

    func readAudio(sampleCapacity: Int = 4096) -> [Int16] {
        guard let handle, sampleCapacity >= 0 else { return [] }
        var samples = [Int16](repeating: 0, count: sampleCapacity)
        let count = samples.withUnsafeMutableBufferPointer {
            ravenemu_read_audio(handle, $0.baseAddress, $0.count)
        }
        return Array(samples.prefix(Int(count)))
    }

    func button(_ id: Int32, pressed: Bool) -> Bool {
        ravenemu_set_button(handle, id, pressed ? 1 : 0) != 0
    }

    func saveState() -> Data? {
        guard let handle else { return nil }
        let capacity = Int(ravenemu_state_size(handle))
        guard capacity > 0 else { return nil }
        var bytes = [UInt8](repeating: 0, count: capacity)
        let ok = bytes.withUnsafeMutableBufferPointer {
            ravenemu_save_state(handle, $0.baseAddress, $0.count) != 0
        }
        return ok ? Data(bytes) : nil
    }

    func loadState(_ state: Data) -> Bool {
        guard let handle else { return false }
        return state.withUnsafeBytes {
            ravenemu_load_state(handle, $0.bindMemory(to: UInt8.self).baseAddress, state.count) != 0
        }
    }

    func batterySnapshot() -> Data? {
        guard let handle else { return nil }
        let capacity = Int(ravenemu_battery_size(handle))
        guard capacity > 0 else { return nil }
        var bytes = [UInt8](repeating: 0, count: capacity)
        let ok = bytes.withUnsafeMutableBufferPointer {
            ravenemu_copy_battery(handle, $0.baseAddress, $0.count) != 0
        }
        return ok ? Data(bytes) : nil
    }

    func dirtyBattery() throws -> (Data, UInt64)? {
        guard let handle else { return nil }
        guard ravenemu_battery_dirty(handle) != 0 else { return nil }
        let capacity = Int(ravenemu_battery_size(handle))
        guard capacity > 0, capacity <= RavenLibraryStore.maxBattery else {
            throw RavenStorageError.invalid("Taille de sauvegarde batterie invalide.")
        }
        var bytes = [UInt8](repeating: 0, count: capacity)
        var generation: UInt64 = 0
        let ok = bytes.withUnsafeMutableBufferPointer {
            ravenemu_copy_battery_generation(handle, $0.baseAddress, $0.count, &generation) != 0
        }
        guard ok else { throw RavenStorageError.invalid(lastError) }
        return (Data(bytes), generation)
    }

    func acknowledgeBattery(_ generation: UInt64) {
        _ = ravenemu_acknowledge_battery(handle, generation)
    }

    func releaseButtons() {
        for id in Int32(0)...Int32(9) { _ = button(id, pressed: false) }
    }

    func reset() -> Bool { ravenemu_reset(handle) != 0 }
}
