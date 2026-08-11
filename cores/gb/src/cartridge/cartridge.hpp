#pragma once

#include "cartridge/cartridge_header.hpp"

namespace ravenemu::cgb {

class Cartridge {
public:
    static constexpr int rom_bank_size = 0x4000;
    static constexpr int ram_bank_size = 0x2000;
    using Clock = std::function<std::int64_t()>;

    Cartridge(RomImage rom, CartridgeHeader header)
        : rom_(std::move(rom)), header_(header), ram_(static_cast<std::size_t>(header.ram_size)) {}
    virtual ~Cartridge() = default;

    [[nodiscard]] virtual int read_rom(int address) const = 0;
    virtual void write_control(int address, int value) = 0;
    [[nodiscard]] virtual int read_ram(int address) const = 0;
    virtual void write_ram(int address, int value) = 0;

    /**
     * Écriture GameShark dans la banque de RAM externe demandée, sans modifier
     * les registres du MBC. Retourne false si la cartouche ne possède pas cette
     * banque ou si les paramètres sortent de l'espace émulé.
     */
    virtual bool write_cheat_ram(int bank, int address, int value) noexcept {
        if (bank < 0 || address < 0xa000 || address > 0xbfff || value < 0 || value > 0xff) {
            return false;
        }
        const auto offset = static_cast<std::size_t>(bank) *
                static_cast<std::size_t>(ram_bank_size)
            + static_cast<std::size_t>(address - 0xa000);
        if (offset >= ram_.size()) return false;
        const auto byte_value = static_cast<std::uint8_t>(value);
        if (ram_[offset] != byte_value) {
            ram_[offset] = byte_value;
            mark_written();
        }
        return true;
    }
    virtual void tick(int) {}
    [[nodiscard]] virtual bool rumble_active() const noexcept { return false; }
    virtual void save_state(BinaryWriter& out) const = 0;
    virtual void load_state(BinaryReader& in) = 0;

    [[nodiscard]] const CartridgeHeader& header() const noexcept { return header_; }
    [[nodiscard]] bool dirty() const noexcept { return generation_ != saved_generation_; }
    [[nodiscard]] std::uint64_t generation() const noexcept { return generation_; }
    [[nodiscard]] bool has_persistent_data() const noexcept {
        return header_.has_battery && (!ram_.empty() || header_.has_rtc);
    }

    virtual std::optional<std::vector<std::uint8_t>> export_battery() {
        if (!header_.has_battery || ram_.empty()) return std::nullopt;
        return ram_;
    }

    virtual void import_battery(std::span<const std::uint8_t> data) {
        if (ram_.empty()) return;
        std::copy_n(data.begin(), static_cast<std::ptrdiff_t>(std::min(data.size(), ram_.size())), ram_.begin());
        mark_clean();
    }

    void acknowledge_saved(std::uint64_t generation) noexcept {
        if (generation == generation_) saved_generation_ = generation;
    }

    [[nodiscard]] static std::unique_ptr<Cartridge> create(
        RomImage rom,
        Clock clock
    );

protected:
    [[nodiscard]] int rom_bank_count() const noexcept {
        return std::max(2, static_cast<int>(rom_->size() / rom_bank_size));
    }
    [[nodiscard]] int normalize_rom_bank(int bank) const noexcept { return bank % rom_bank_count(); }
    void mark_written() noexcept { ++generation_; }
    void mark_clean() noexcept { saved_generation_ = generation_; }

    RomImage rom_;
    CartridgeHeader header_;
    std::vector<std::uint8_t> ram_;

private:
    std::uint64_t generation_{};
    std::uint64_t saved_generation_{};
};

} // namespace ravenemu::cgb
