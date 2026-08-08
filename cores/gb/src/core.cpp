#include "machine/machine.hpp"

#include "ravenemu/sha256.hpp"

namespace ravenemu {
namespace cgb {

class GameBoyCore final : public Core {
public:
    [[nodiscard]] Console console() const noexcept override { return Console::game_boy; }
    [[nodiscard]] VideoSpec video_spec() const noexcept override { return {160, 144, 4'194'304.0 / 70'224.0}; }
    [[nodiscard]] AudioSpec audio_spec() const noexcept override { return {Apu::sample_rate, 2}; }
    [[nodiscard]] FramebufferFormat framebuffer_format() const noexcept override {
        return machine_ != nullptr && machine_->cgb_mode ? FramebufferFormat::argb_8888 : FramebufferFormat::indexed_4;
    }

    void load_rom(std::span<const std::uint8_t> rom, std::span<const std::uint8_t> battery) override {
        static_cast<void>(CartridgeHeader::parse(rom));
        auto image = std::make_shared<const std::vector<std::uint8_t>>(rom.begin(), rom.end());
        auto replacement = new_machine(image);
        if (!battery.empty()) replacement->cartridge->import_battery(battery);
        loaded_rom_ = std::move(image); rom_hash_ = detail::sha256(rom); machine_ = std::move(replacement);
    }
    void reset() override {
        require_loaded();
        const auto battery = machine_->cartridge->export_battery();
        auto replacement = new_machine(loaded_rom_);
        if (battery) replacement->cartridge->import_battery(*battery);
        machine_ = std::move(replacement);
    }
    void run_frame(std::span<std::int32_t> framebuffer, bool) override {
        require_loaded();
        if (framebuffer.size() < Ppu::frame_pixels) throw std::invalid_argument("Framebuffer trop petit");
        int ppu_cycles{};
        while (ppu_cycles < 70'224) {
            const int advanced = machine_->step();
            if (advanced <= 0) break;
            ppu_cycles += advanced;
        }
        std::copy(machine_->ppu.completed_frame.begin(), machine_->ppu.completed_frame.end(), framebuffer.begin());
    }
    void set_button(Button button, bool pressed) override { if (machine_) machine_->joypad.set_button(button, pressed); }
    std::size_t read_audio(std::span<std::int16_t> destination) override {
        return machine_ ? machine_->apu.read_samples(destination) : 0;
    }
    [[nodiscard]] bool rumble_active() const noexcept override {
        return machine_ != nullptr && machine_->cartridge->rumble_active();
    }
    [[nodiscard]] bool has_battery_ram() const noexcept override {
        return machine_ != nullptr && machine_->cartridge->has_persistent_data();
    }
    [[nodiscard]] bool battery_ram_dirty() const noexcept override {
        return machine_ != nullptr && machine_->cartridge->dirty();
    }
    std::optional<BatterySnapshot> snapshot_battery_ram() override {
        if (!machine_) return std::nullopt;
        auto data = machine_->cartridge->export_battery();
        if (!data) return std::nullopt;
        return BatterySnapshot{std::move(*data), machine_->cartridge->generation()};
    }
    void acknowledge_battery_ram_saved(std::uint64_t generation) noexcept override {
        if (machine_) machine_->cartridge->acknowledge_saved(generation);
    }

    [[nodiscard]] std::vector<std::uint8_t> save_state() const override {
        require_loaded(); BinaryWriter out(64 * 1024);
        out.u32(0x52564e53U); out.u16(5); out.u8(static_cast<std::uint8_t>(Console::game_boy)); out.raw(rom_hash_);
        machine_->cpu.save(out);
        out.i32(machine_->interrupts.flags); out.i32(machine_->interrupts.enable);
        machine_->timer.save(out); machine_->serial.save(out); machine_->joypad.save(out);
        machine_->ppu.save(out); machine_->speed.save(out); machine_->bus.save(out);
        machine_->apu.save(out); machine_->cartridge->save_state(out);
        return std::move(out).take();
    }
    void load_state(std::span<const std::uint8_t> state) override {
        require_loaded();
        if (state.size() > (1U << 20U)) throw SaveStateError("État instantané trop volumineux");
        BinaryReader in(state);
        if (in.u32() != 0x52564e53U) throw SaveStateError("Ce fichier n'est pas un état RavenEmu");
        const auto version = in.u16(); if (version != 5) throw SaveStateError("Version d'état non prise en charge");
        if (in.u8() != static_cast<std::uint8_t>(Console::game_boy)) throw SaveStateError("État issu d'une autre console");
        std::array<std::uint8_t, 32> hash{}; in.raw(hash);
        if (hash != rom_hash_) throw SaveStateError("État issu d'une autre ROM");
        auto replacement = new_machine(loaded_rom_);
        replacement->cpu.load(in); replacement->interrupts.flags = in.i32(); replacement->interrupts.enable = in.i32();
        replacement->timer.load(in); replacement->serial.load(in); replacement->joypad.load(in);
        replacement->ppu.load(in); replacement->speed.load(in); replacement->bus.load(in);
        replacement->apu.load(in); replacement->cartridge->load_state(in);
        if (!in.exhausted()) throw SaveStateError("État instantané corrompu (données excédentaires)");
        machine_ = std::move(replacement);
    }
    void set_clock_epoch(std::optional<std::int64_t> epoch) noexcept override { clock_override_ = epoch; }

private:
    [[nodiscard]] std::int64_t current_epoch() const noexcept {
        if (clock_override_) return *clock_override_;
        return std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    }
    [[nodiscard]] std::unique_ptr<Machine> new_machine(RomImage rom) const {
        return std::make_unique<Machine>(rom, [this] { return current_epoch(); });
    }
    void require_loaded() const {
        if (!machine_) throw std::logic_error("Aucune ROM chargée");
    }

    std::unique_ptr<Machine> machine_;
    RomImage loaded_rom_;
    std::array<std::uint8_t, 32> rom_hash_{};
    std::optional<std::int64_t> clock_override_;
};

} // namespace cgb

std::unique_ptr<Core> make_game_boy_core() {
    return std::make_unique<cgb::GameBoyCore>();
}

} // namespace ravenemu
