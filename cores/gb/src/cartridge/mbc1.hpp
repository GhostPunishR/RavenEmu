#pragma once

#include "cartridge/cartridge.hpp"

namespace ravenemu::cgb {

class Mbc1 final : public Cartridge {
public:
    using Cartridge::Cartridge;
    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? fixed_bank() : current_rom_bank();
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address <= 0x1fff) ram_enabled_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x3fff) { const int v = value & 0x1f; rom_bank_low_ = v == 0 ? 1 : v; }
        else if (address <= 0x5fff) bank_high_ = value & 3;
        else advanced_mode_ = (value & 1) != 0;
    }
    int read_ram(int address) const override {
        if (!ram_enabled_ || ram_.empty()) return 0xff;
        const auto offset = static_cast<std::size_t>(ram_bank() * ram_bank_size + address - 0xa000);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_ || ram_.empty()) return;
        const auto offset = static_cast<std::size_t>(ram_bank() * ram_bank_size + address - 0xa000);
        if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
    }
    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_low_); out.i32(bank_high_);
        out.boolean(advanced_mode_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_low_ = in.i32(); bank_high_ = in.i32();
        advanced_mode_ = in.boolean(); in.raw(ram_);
    }
private:
    [[nodiscard]] int current_rom_bank() const noexcept {
        return normalize_rom_bank((bank_high_ << 5) | rom_bank_low_);
    }
    [[nodiscard]] int fixed_bank() const noexcept {
        return advanced_mode_ ? normalize_rom_bank(bank_high_ << 5) : 0;
    }
    [[nodiscard]] int ram_bank() const noexcept {
        return advanced_mode_ && ram_.size() > ram_bank_size ? bank_high_ : 0;
    }
    bool ram_enabled_{};
    int rom_bank_low_{1};
    int bank_high_{};
    bool advanced_mode_{};
};

} // namespace ravenemu::cgb
