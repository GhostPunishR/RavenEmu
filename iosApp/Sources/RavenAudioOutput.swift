import AVFoundation

/// Emulation-queue owned AVAudioEngine. Completion callbacks only touch the counter.
final class RavenAudioOutput {
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var format: AVAudioFormat?
    private let lock = NSLock()
    private var queued = 0
    private var generation = 0

    func start(sampleRate: Int, volume: Int) throws {
        if engine != nil { player?.volume = Float(volume) / 100; return }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.ambient, mode: .default)
        try session.setActive(true)
        guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32,
            sampleRate: Double(sampleRate), channels: 2, interleaved: false) else {
            throw RavenStorageError.invalid("Format audio indisponible.")
        }
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        player.volume = Float(volume) / 100
        try engine.start()
        player.play()
        self.engine = engine; self.player = player; self.format = format
    }

    func enqueue(_ samples: [Int16]) {
        guard let player, let format, samples.count >= 2 else { return }
        lock.lock()
        // Bound queued audio instead of accumulating seconds of latency on a stall.
        guard queued < 4 else { lock.unlock(); return }
        queued += 1
        let current = generation
        lock.unlock()
        let frames = AVAudioFrameCount(samples.count / 2)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let channels = buffer.floatChannelData else {
            finish(current); return
        }
        buffer.frameLength = frames
        for frame in 0..<Int(frames) {
            channels[0][frame] = Float(samples[frame * 2]) / 32768
            channels[1][frame] = Float(samples[frame * 2 + 1]) / 32768
        }
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            self?.finish(current)
        }
    }

    private func finish(_ current: Int) {
        lock.lock(); defer { lock.unlock() }
        if generation == current { queued = max(0, queued - 1) }
    }

    func stop() {
        lock.lock(); generation += 1; queued = 0; lock.unlock()
        player?.stop(); engine?.stop()
        player = nil; engine = nil; format = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
