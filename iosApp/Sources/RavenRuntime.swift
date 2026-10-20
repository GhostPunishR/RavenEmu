import Foundation
import UIKit
import AVFoundation
import UniformTypeIdentifiers
import RavenShared

/// UIKit/main owns presentation; this queue is the sole owner of every C handle.
final class RavenRuntime: NSObject, IosHostActions, UIDocumentPickerDelegate {
    let host = IosHost()
    weak var presenter: UIViewController?
    private let queue = DispatchQueue(label: "RavenEmu.emulation", qos: .userInteractive)
    private var store: RavenLibraryStore?
    private var core: RavenNativeCore?
    private var game: RavenLibraryGame?
    private var timer: DispatchSourceTimer?
    private let audio = RavenAudioOutput()
    private var playing = false, menuPaused = false, foreground = true, interrupted = false
    private var audioEnabled = true, audioSuppressed = false
    private var volume = 100
    private var framePending = false
    private var lastSave: UInt64 = 0
    // Main-thread fields.
    private var observers: [NSObjectProtocol] = []
    private var didShutdown = false
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private enum PickerPurpose { case rom, battery(RavenLibraryGame), export }
    private var purpose: PickerPurpose?
    private var exportDirectory: URL?

    override init() {
        super.init()
        host.actions = self
        audioEnabled = host.audioEnabled(); volume = Int(host.audioVolume())
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.suspend()
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            if self.backgroundTask == .invalid {
                self.backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in self?.endBackgroundTask() }
            }
            self.suspend { [weak self] in self?.endBackgroundTask() }
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            self?.queue.async { [weak self] in
                guard let self else { return }
                self.foreground = true; self.audioSuppressed = false; self.reconcilePlayback()
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.uintValue
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.uintValue ?? 0
            self?.queue.async { [weak self] in
                guard let self else { return }
                self.interrupted = type == AVAudioSession.InterruptionType.began.rawValue
                if !self.interrupted { self.audioSuppressed = !AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) }
                self.reconcilePlayback()
                if self.interrupted { self.persistOrReport() }
            }
        })
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                    appropriateFor: nil, create: true).appendingPathComponent("RavenEmu", isDirectory: true)
                self.store = try RavenLibraryStore(root: root)
                self.publishLibrary()
            } catch { self.report(error) }
        }
    }

    private func publish(_ action: @escaping (IosHost) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.didShutdown else { return }
            action(self.host)
        }
    }
    private func report(_ error: Error) { publish { $0.showError(message: error.localizedDescription) } }
    private func library() throws -> RavenLibraryStore {
        guard let store else { throw RavenStorageError.invalid("Bibliothèque indisponible.") }
        return store
    }
    private func find(_ id: String) throws -> RavenLibraryGame {
        guard let found = try library().games.first(where: { $0.id == id }) else {
            throw RavenStorageError.invalid("Jeu introuvable.")
        }
        return found
    }
    private func publishLibrary() {
        let rows = store?.games.map { IosLibraryGame(id: $0.id, filename: $0.filename, system: $0.system) } ?? []
        publish { $0.replaceLibrary(entries: rows) }
    }

    func launchGame(id: String) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                self.playing = false; self.menuPaused = false; self.reconcilePlayback()
                try self.persistBattery()
                let game = try self.find(id)
                let store = try self.library()
                guard let system = RavenNativeCore.System(rawValue: game.system), let replacement = RavenNativeCore(system: system) else {
                    throw RavenStorageError.invalid("Impossible de créer le moteur.")
                }
                guard replacement.load(rom: try store.rom(game), battery: try store.battery(id)) else {
                    throw RavenStorageError.invalid(replacement.lastError)
                }
                self.core = replacement; self.game = game; self.lastSave = DispatchTime.now().uptimeNanoseconds
                let width = Int32(replacement.width), height = Int32(replacement.height)
                self.refreshSlots()
                self.publish { $0.gameLoaded(filename: game.filename, system: game.system, width: width, height: height) }
            } catch { self.report(error) }
        }
    }
    func startPlayback() { queue.async { [weak self] in self?.playing = true; self?.reconcilePlayback() } }
    func stopPlayback() {
        queue.async { [weak self] in
            self?.playing = false; self?.menuPaused = false; self?.reconcilePlayback(); self?.persistOrReport()
        }
    }
    func menuVisibilityChanged(open: Bool) {
        queue.async { [weak self] in
            self?.menuPaused = open; self?.reconcilePlayback()
            if open { self?.persistOrReport(); self?.refreshSlots() }
        }
    }
    func buttonChanged(code: Int32, pressed: Bool) {
        queue.async { [weak self] in
            guard let self, !pressed || (self.playing && self.foreground && !self.menuPaused && !self.interrupted) else { return }
            _ = self.core?.button(code, pressed: pressed)
        }
    }
    func configureAudio(enabled: Bool, volume: Int32) {
        queue.async { [weak self] in
            self?.audioEnabled = enabled; self?.volume = max(0, min(100, Int(volume)))
            self?.audioSuppressed = false; self?.reconcilePlayback()
        }
    }

    private func reconcilePlayback() {
        guard playing && foreground && !menuPaused && !interrupted, let core else {
            timer?.setEventHandler {}; timer?.cancel(); timer = nil
            self.core?.releaseButtons(); audio.stop(); return
        }
        if audioEnabled && !audioSuppressed {
            do { try audio.start(sampleRate: core.audioRate, volume: volume) }
            catch { audioSuppressed = true; audio.stop(); report(RavenStorageError.invalid("La sortie audio est indisponible. La partie continue sans son.")) }
        } else { audio.stop() }
        if timer != nil { return }
        let source = DispatchSource.makeTimerSource(queue: queue)
        let interval = max(1, Int((1_000_000_000 / core.fps).rounded()))
        source.schedule(deadline: .now(), repeating: .nanoseconds(interval), leeway: .microseconds(200))
        source.setEventHandler { [weak self] in self?.tick() }
        timer = source; source.resume()
    }
    private func tick() {
        guard let core else { return }
        do {
            guard let pixels = core.runFrame() else { throw RavenStorageError.invalid(core.lastError) }
            audio.enqueue(core.readAudio(sampleCapacity: 8192)) // Drain even when muted.
            if !framePending {
                framePending = true
                let data = pixels.withUnsafeBufferPointer { Data(buffer: $0) }
                let width = Int32(core.width), height = Int32(core.height)
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    if !self.didShutdown { self.host.presentFrame(bytes: data, width: width, height: height) }
                    self.queue.async { [weak self] in self?.framePending = false }
                }
            }
            let now = DispatchTime.now().uptimeNanoseconds
            if now - lastSave >= 1_000_000_000 { try persistBattery(); lastSave = now }
        } catch {
            playing = false; reconcilePlayback(); persistOrReport(); report(error)
        }
    }
    private func persistBattery() throws {
        guard let game, let core, let (bytes, generation) = try core.dirtyBattery() else { return }
        try library().writeBattery(game.id, data: bytes)
        core.acknowledgeBattery(generation) // Only after a successful atomic write.
    }
    private func persistOrReport() { do { try persistBattery() } catch { report(error) } }
    private func suspend(completion: (() -> Void)? = nil) {
        queue.async { [weak self] in
            self?.foreground = false; self?.reconcilePlayback(); self?.persistOrReport()
            if let completion { DispatchQueue.main.async(execute: completion) }
        }
    }
    private func endBackgroundTask() {
        if backgroundTask != .invalid { UIApplication.shared.endBackgroundTask(backgroundTask); backgroundTask = .invalid }
    }

    private func refreshSlots() {
        do {
            guard let game else { return }
            let store = try library()
            let formatter = DateFormatter(); formatter.dateStyle = .medium; formatter.timeStyle = .short
            let dates = try (0..<4).map { slot in try store.stateDate(game.id, slot: slot).map { formatter.string(from: $0) } ?? "" }
            publish { $0.stateSlots(dates: dates) }
        } catch { report(error) }
    }
    private func stateOperation(_ action: @escaping (RavenNativeCore, RavenLibraryGame, RavenLibraryStore) throws -> Void) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                guard let core = self.core, let game = self.game else { throw RavenStorageError.invalid("Aucun jeu chargé.") }
                try action(core, game, self.library())
                self.refreshSlots()
            } catch { self.report(error) }
        }
    }
    func saveState(slot: Int32) {
        stateOperation { core, game, store in
            guard let data = core.saveState() else { throw RavenStorageError.invalid(core.lastError) }
            try store.writeState(game.id, slot: Int(slot), data: data)
        }
    }
    func loadState(slot: Int32) {
        stateOperation { [weak self] core, game, store in
            guard let data = try store.state(game.id, slot: Int(slot)) else { throw RavenStorageError.invalid("Emplacement vide.") }
            core.releaseButtons()
            guard core.loadState(data) else { throw RavenStorageError.invalid(core.lastError) }
            self?.audio.stop(); self?.reconcilePlayback(); try self?.persistBattery()
        }
    }
    func deleteState(slot: Int32) { stateOperation { _, game, store in try store.deleteState(game.id, slot: Int(slot)) } }

    func importRom() {
        guard purpose == nil else { return }
        host.beginImport(); purpose = .rom
        let types = ["gb", "gbc", "gba"].compactMap { UTType(filenameExtension: $0) }
        present(UIDocumentPickerViewController(forOpeningContentTypes: types.isEmpty ? [.data] : types, asCopy: false))
    }
    func importBattery(id: String) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let game = try self.find(id)
                DispatchQueue.main.async { [weak self] in
                    guard let self, !self.didShutdown else { return }
                    self.purpose = .battery(game)
                    self.present(UIDocumentPickerViewController(forOpeningContentTypes: [UTType(filenameExtension: "sav") ?? .data], asCopy: false))
                }
            } catch { self.report(error) }
        }
    }
    func exportBattery(id: String) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                try self.persistBattery()
                let game = try self.find(id)
                guard let data = try self.library().battery(id) else { throw RavenStorageError.invalid("Ce jeu n'a pas encore de sauvegarde .sav.") }
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let url = directory.appendingPathComponent(RavenLibraryStore.exportName(game.filename))
                try data.write(to: url, options: .atomic)
                DispatchQueue.main.async { [weak self] in
                    guard let self, !self.didShutdown else { try? FileManager.default.removeItem(at: directory); return }
                    self.exportDirectory = directory; self.purpose = .export
                    self.present(UIDocumentPickerViewController(forExporting: [url], asCopy: true))
                }
            } catch { self.report(error) }
        }
    }
    private func present(_ picker: UIDocumentPickerViewController) {
        guard var controller = presenter else { finishPicker(); host.showError(message: "Sélecteur de fichiers indisponible."); return }
        while let next = controller.presentedViewController { controller = next }
        picker.delegate = self; picker.allowsMultipleSelection = false
        controller.present(picker, animated: true)
    }
    private func finishPicker() {
        purpose = nil; host.cancelImport(); host.finishTransfer()
        if let exportDirectory { try? FileManager.default.removeItem(at: exportDirectory) }
        exportDirectory = nil
    }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finishPicker() }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let purpose, let url = urls.first else { finishPicker(); return }
        if case .export = purpose { finishPicker(); return }
        self.purpose = nil
        controller.dismiss(animated: true) { [weak self] in
        let scoped = url.startAccessingSecurityScopedResource()
        guard let self else { if scoped { url.stopAccessingSecurityScopedResource() }; return }
        self.queue.async { [weak self] in
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let self else { return }
            do {
                var result: Result<Data, Error>?
                var coordinationError: NSError?
                let maximum: Int = { if case .rom = purpose { return RavenLibraryStore.maxROM }; return RavenLibraryStore.maxBattery }()
                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { selected in
                    result = Result { try RavenLibraryStore.readBounded(selected, maximum: maximum) }
                }
                if let coordinationError { throw coordinationError }
                guard let result else { throw RavenStorageError.invalid("Le fichier n'a pas pu être lu.") }
                let bytes = try result.get()
                let store = try self.library()
                switch purpose {
                case .rom:
                    let system = try RavenLibraryStore.system(filename: url.lastPathComponent, rom: bytes)
                    guard let kind = RavenNativeCore.System(rawValue: system), let probe = RavenNativeCore(system: kind), probe.load(rom: bytes) else {
                        throw RavenStorageError.invalid("Le moteur ne peut pas ouvrir cette ROM.")
                    }
                    try store.importROM(filename: url.lastPathComponent, bytes: bytes)
                    self.publishLibrary()
                case .battery(let game):
                    guard !bytes.isEmpty, let kind = RavenNativeCore.System(rawValue: game.system),
                          let probe = RavenNativeCore(system: kind), probe.load(rom: try store.rom(game), battery: bytes) else {
                        throw RavenStorageError.invalid("Sauvegarde incompatible avec ce jeu.")
                    }
                    DispatchQueue.main.async { [weak self] in self?.confirmBattery(bytes, game: game) }
                case .export: break
                }
            } catch { self.report(error) }
        }
        }
    }
    private func confirmBattery(_ data: Data, game: RavenLibraryGame) {
        guard !didShutdown, var controller = presenter else { return }
        while let next = controller.presentedViewController { controller = next }
        let alert = UIAlertController(title: "Importer la sauvegarde ?", message: "La sauvegarde de \(game.filename) sera remplacée. Un fichier .sav ne contient pas l'identité de sa ROM.", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Annuler", style: .cancel) { [weak self] _ in self?.host.finishTransfer() })
        alert.addAction(UIAlertAction(title: "Remplacer", style: .destructive) { [weak self] _ in
            self?.queue.async { [weak self] in
                guard let self else { return }
                do {
                    if self.game?.id == game.id {
                        try self.persistBattery(); self.playing = false; self.reconcilePlayback(); self.core = nil; self.game = nil
                    }
                    try self.library().writeBattery(game.id, data: data)
                    self.publish { $0.finishTransfer() }
                } catch { self.report(error) }
            }
        })
        controller.present(alert, animated: true)
    }
    func openLink(url: String) {
        guard let address = URL(string: url), address.scheme == "https" else { return }
        UIApplication.shared.open(address)
    }
    func shutdown() {
        guard !didShutdown else { return }
        didShutdown = true; host.actions = nil; presenter = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }; observers.removeAll()
        finishPicker(); endBackgroundTask()
        queue.async { [self] in
            playing = false; reconcilePlayback(); try? persistBattery(); core = nil; game = nil
        }
    }
}
