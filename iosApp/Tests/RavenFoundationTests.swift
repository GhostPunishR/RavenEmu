import XCTest

final class RavenFoundationTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try FileManager.default.removeItem(at: directory) }

    private func gbROM(color: UInt8 = 0) -> Data {
        var rom = Data(repeating: 0, count: 0x8000)
        // Original program: A=5A, write cartridge RAM, then JP 0105 forever.
        rom.replaceSubrange(0x100..<0x108, with: [0x3e, 0x5a, 0xea, 0x00, 0xa0, 0xc3, 0x05, 0x01])
        rom[0x143] = color; rom[0x147] = 0x09; rom[0x149] = 2
        return rom
    }
    func testImportUsesContentIdentityAndHeaderAndSurvivesReopening() throws {
        let store = try RavenLibraryStore(root: directory)
        let first = try store.importROM(filename: "Été (France).gb", bytes: gbROM(color: 0x80))
        let renamed = try store.importROM(filename: "Renamed.gbc", bytes: gbROM(color: 0x80))
        XCTAssertEqual(first.id, renamed.id)
        XCTAssertEqual(first.system, 1)
        XCTAssertEqual(store.games.count, 1)
        let reopened = try RavenLibraryStore(root: directory)
        XCTAssertEqual(reopened.games, [first])
        XCTAssertEqual(try reopened.rom(first), gbROM(color: 0x80))
        XCTAssertEqual(RavenLibraryStore.exportName(first.filename), "Été (France).sav")
    }
    func testBatteryAndFourStateSlotsStayIsolatedAndPreserveExistingDataOnInvalidWrite() throws {
        let store = try RavenLibraryStore(root: directory)
        let game = try store.importROM(filename: "Synthetic.gb", bytes: gbROM())
        let battery = Data([1, 2, 3])
        try store.writeBattery(game.id, data: battery)
        XCTAssertThrowsError(try store.writeBattery(game.id, data: Data()))
        XCTAssertEqual(try store.battery(game.id), battery)
        for slot in 0..<4 { try store.writeState(game.id, slot: slot, data: Data([UInt8(slot)])) }
        try store.deleteState(game.id, slot: 2)
        XCTAssertNil(try store.state(game.id, slot: 2))
        XCTAssertEqual(try store.state(game.id, slot: 3), Data([3]))
        XCTAssertThrowsError(try store.writeState(game.id, slot: -1, data: Data([1])))
        XCTAssertThrowsError(try store.writeBattery("../../escape", data: battery))
    }
    func testOversizeCorruptIndexAndCorruptROMAreRejected() throws {
        let input = directory.appendingPathComponent("input")
        try Data(repeating: 0, count: 20).write(to: input)
        XCTAssertThrowsError(try RavenLibraryStore.readBounded(input, maximum: 19))
        XCTAssertEqual(try RavenLibraryStore.readBounded(input, maximum: 20).count, 20)
        let store = try RavenLibraryStore(root: directory)
        let game = try store.importROM(filename: "Synthetic.gb", bytes: gbROM())
        try Data([9]).write(to: directory.appendingPathComponent(game.id + ".rom"))
        XCTAssertThrowsError(try store.rom(game))
        let corrupt = Data("broken index".utf8)
        let index = directory.appendingPathComponent("library.json")
        try corrupt.write(to: index)
        XCTAssertThrowsError(try RavenLibraryStore(root: directory))
        XCTAssertEqual(try Data(contentsOf: index), corrupt)
    }
    func testNativeGBAndGBCFramesAudioStateAndBatteryPersistence() throws {
        for system in [RavenNativeCore.System.gb, .gbc] {
            let core = try XCTUnwrap(RavenNativeCore(system: system))
            XCTAssertTrue(core.load(rom: gbROM(color: system == .gbc ? 0xc0 : 0)))
            XCTAssertEqual(core.runFrame()?.count, 160 * 144)
            XCTAssertFalse(core.readAudio().isEmpty)
            let state = try XCTUnwrap(core.saveState())
            XCTAssertTrue(core.loadState(state))
            let (battery, generation) = try XCTUnwrap(core.dirtyBattery())
            XCTAssertEqual(battery.first, 0x5a)
            let store = try RavenLibraryStore(root: directory)
            let game = try store.importROM(filename: "Synthetic.gb", bytes: gbROM(color: system == .gbc ? 0xc0 : 0))
            try store.writeBattery(game.id, data: battery)
            core.acknowledgeBattery(generation)
            XCTAssertNil(try core.dirtyBattery())
            let replacement = try XCTUnwrap(RavenNativeCore(system: system))
            XCTAssertTrue(replacement.load(rom: try store.rom(game), battery: try store.battery(game.id)))
            XCTAssertEqual(replacement.batterySnapshot(), battery)
        }
    }
    func testNativeGBAFrameAndStateRoundTrip() throws {
        var rom = Data(repeating: 0, count: 0x200)
        rom.replaceSubrange(0..<4, with: [0xfe, 0xff, 0xff, 0xea]) // original ARM B .
        rom[0xb2] = 0x96
        let core = try XCTUnwrap(RavenNativeCore(system: .gba))
        XCTAssertTrue(core.load(rom: rom))
        XCTAssertEqual(core.runFrame()?.count, 240 * 160)
        let state = try XCTUnwrap(core.saveState())
        XCTAssertTrue(core.loadState(state))
    }
}
