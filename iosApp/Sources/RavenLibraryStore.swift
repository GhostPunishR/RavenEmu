import Foundation
import CryptoKit

enum RavenStorageError: LocalizedError {
    case invalid(String)
    var errorDescription: String? {
        switch self { case .invalid(let message): return message }
    }
}

struct RavenLibraryGame: Codable, Equatable {
    let id: String
    let filename: String
    /// RavenCore system values: GB 0, GBC 1, GBA 2.
    let system: Int32
}

/// Serial-queue owned private storage. All identities derive from ROM content.
final class RavenLibraryStore {
    static let maxROM = 32 * 1024 * 1024
    static let maxBattery = 2 * 1024 * 1024
    static let maxState = 16 * 1024 * 1024
    private let root: URL
    private(set) var games: [RavenLibraryGame]

    init(root: URL) throws {
        self.root = root
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let index = root.appendingPathComponent("library.json")
        if FileManager.default.fileExists(atPath: index.path) {
            games = try JSONDecoder().decode([RavenLibraryGame].self,
                from: Self.readBounded(index, maximum: 2 * 1024 * 1024))
            guard games.count <= 10000, Set(games.map(\.id)).count == games.count else {
                throw RavenStorageError.invalid("Index de bibliothèque invalide.")
            }
            for game in games {
                try Self.validate(game.id)
                guard (0...2).contains(game.system), Self.accepts(game.filename),
                      URL(fileURLWithPath: game.filename).lastPathComponent == game.filename else {
                    throw RavenStorageError.invalid("Entrée de bibliothèque invalide.")
                }
            }
        } else { games = [] }
    }

    static func accepts(_ filename: String) -> Bool {
        ["gb", "gbc", "gba"].contains(URL(fileURLWithPath: filename).pathExtension.lowercased())
    }

    /// Matches shared RomImportPolicy: GB/GBC is selected by header, not extension.
    static func system(filename: String, rom: Data) throws -> Int32 {
        guard accepts(filename), !rom.isEmpty, rom.count <= maxROM else {
            throw RavenStorageError.invalid("Choisissez une ROM .gb, .gbc ou .gba de 32 Mio maximum.")
        }
        if URL(fileURLWithPath: filename).pathExtension.lowercased() == "gba" { return 2 }
        let flag = rom.count > 0x143 ? rom[0x143] : 0
        return flag == 0x80 || flag == 0xc0 ? 1 : 0
    }

    /// Validate with RavenCore before calling this function. Index is committed last.
    @discardableResult
    func importROM(filename: String, bytes: Data) throws -> RavenLibraryGame {
        let system = try Self.system(filename: filename, rom: bytes)
        let id = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        let game = games.first { $0.id == id } ?? RavenLibraryGame(
            id: id, filename: URL(fileURLWithPath: filename).lastPathComponent, system: system)
        try bytes.write(to: try file(id: id, suffix: ".rom"), options: .atomic)
        if !games.contains(where: { $0.id == id }) {
            guard games.count < 10000 else { throw RavenStorageError.invalid("La bibliothèque est pleine.") }
            let updated = games + [game]
            try JSONEncoder().encode(updated).write(to: root.appendingPathComponent("library.json"), options: .atomic)
            games = updated
        }
        return game
    }

    func rom(_ game: RavenLibraryGame) throws -> Data {
        let bytes = try Self.readBounded(file(id: game.id, suffix: ".rom"), maximum: Self.maxROM)
        let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
        guard digest == game.id else { throw RavenStorageError.invalid("La copie de la ROM est endommagée. Réimportez le fichier.") }
        return bytes
    }

    func battery(_ id: String) throws -> Data? { try optional(id, suffix: ".sav", maximum: Self.maxBattery) }
    func writeBattery(_ id: String, data: Data) throws {
        guard !data.isEmpty, data.count <= Self.maxBattery else { throw RavenStorageError.invalid("Taille de sauvegarde .sav invalide.") }
        try data.write(to: file(id: id, suffix: ".sav"), options: .atomic)
    }
    func state(_ id: String, slot: Int) throws -> Data? { try optional(id, suffix: stateSuffix(slot), maximum: Self.maxState) }
    func writeState(_ id: String, slot: Int, data: Data) throws {
        guard !data.isEmpty, data.count <= Self.maxState else { throw RavenStorageError.invalid("Taille d'état invalide.") }
        try data.write(to: file(id: id, suffix: stateSuffix(slot)), options: .atomic)
    }
    func deleteState(_ id: String, slot: Int) throws {
        let url = try file(id: id, suffix: stateSuffix(slot))
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    func stateDate(_ id: String, slot: Int) throws -> Date? {
        let url = try file(id: id, suffix: stateSuffix(slot))
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate
    }

    static func exportName(_ filename: String) -> String {
        var stem = URL(fileURLWithPath: filename).deletingPathExtension().lastPathComponent
        if stem.isEmpty { stem = "Game" }
        while (stem + ".sav").utf8.count > 255 { stem.removeLast() }
        return stem + ".sav"
    }

    static func readBounded(_ url: URL, maximum: Int) throws -> Data {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        var data = Data()
        while let block = try input.read(upToCount: min(65536, maximum + 1 - data.count)), !block.isEmpty {
            data.append(block)
            guard data.count <= maximum else { throw RavenStorageError.invalid("Le fichier dépasse la taille autorisée.") }
        }
        return data
    }

    private static func validate(_ id: String) throws {
        guard id.count == 64, id.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw RavenStorageError.invalid("Identifiant de ROM invalide.")
        }
    }
    private func file(id: String, suffix: String) throws -> URL {
        try Self.validate(id)
        return root.appendingPathComponent(id + suffix)
    }
    private func stateSuffix(_ slot: Int) throws -> String {
        guard (0..<4).contains(slot) else { throw RavenStorageError.invalid("Emplacement invalide.") }
        return ".slot\(slot).state"
    }
    private func optional(_ id: String, suffix: String, maximum: Int) throws -> Data? {
        let url = try file(id: id, suffix: suffix)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try Self.readBounded(url, maximum: maximum)
    }
}
