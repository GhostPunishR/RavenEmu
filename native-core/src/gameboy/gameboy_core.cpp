#include "ravenemu/core.hpp"

#include "binary_io.hpp"
#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace ravenemu {
namespace {

using detail::BinaryReader;
using detail::BinaryWriter;

constexpr int byte(int value) noexcept { return value & 0xff; }
constexpr int word(int value) noexcept { return value & 0xffff; }
using RomImage = std::shared_ptr<const std::vector<std::uint8_t>>;

enum class Interrupt : std::uint8_t { vblank, stat, timer, serial, joypad };

constexpr int interrupt_mask(Interrupt interrupt) noexcept {
    return 1 << static_cast<int>(interrupt);
}

constexpr int interrupt_vector(Interrupt interrupt) noexcept {
    return 0x40 + static_cast<int>(interrupt) * 8;
}

class InterruptController {
public:
    int flags{0xe1};
    int enable{};

    void request(Interrupt interrupt) noexcept { flags |= interrupt_mask(interrupt); }
    void acknowledge(Interrupt interrupt) noexcept { flags &= ~interrupt_mask(interrupt); }
    [[nodiscard]] int pending() const noexcept { return enable & flags & 0x1f; }

    [[nodiscard]] std::optional<Interrupt> highest_pending() const noexcept {
        const auto value = pending();
        for (int index = 0; index < 5; ++index) {
            if ((value & (1 << index)) != 0) return static_cast<Interrupt>(index);
        }
        return std::nullopt;
    }

    [[nodiscard]] int read_flags() const noexcept { return flags | 0xe0; }
    void write_flags(int value) noexcept { flags = value & 0x1f; }
    [[nodiscard]] int read_enable() const noexcept { return enable; }
    void write_enable(int value) noexcept { enable = byte(value); }
};

class SpeedController {
public:
    explicit SpeedController(bool cgb_mode) : cgb_mode_(cgb_mode) {}

    [[nodiscard]] int peripheral_shift() const noexcept { return double_speed_ ? 1 : 0; }
    [[nodiscard]] int read_key1() const noexcept {
        if (!cgb_mode_) return 0xff;
        return (double_speed_ ? 0x80 : 0) | (armed_ ? 1 : 0) | 0x7e;
    }
    void write_key1(int value) noexcept { if (cgb_mode_) armed_ = (value & 1) != 0; }
    bool on_stop() noexcept {
        if (!cgb_mode_ || !armed_) return false;
        double_speed_ = !double_speed_;
        armed_ = false;
        return true;
    }
    void save(BinaryWriter& out) const { out.i32(double_speed_ ? 1 : 0); out.i32(armed_ ? 1 : 0); }
    void load(BinaryReader& in) { double_speed_ = in.i32() != 0; armed_ = in.i32() != 0; }

private:
    bool cgb_mode_{};
    bool double_speed_{};
    bool armed_{};
};

enum class MbcType : std::uint8_t { none, mbc1, mbc2, mbc3, mbc5, unsupported };

struct CartridgeHeader {
    int cartridge_type{};
    MbcType mbc{MbcType::unsupported};
    bool has_ram{};
    bool has_battery{};
    bool has_rtc{};
    int ram_size{};
    bool uses_color{};

    static constexpr std::size_t min_rom_size = 0x8000;
    static constexpr std::size_t max_rom_size = 8U * 1024U * 1024U;

    static CartridgeHeader parse(std::span<const std::uint8_t> rom) {
        if (rom.size() < min_rom_size) {
            throw RomLoadError("ROM trop petite (minimum 32768 octets)");
        }
        if (rom.size() > max_rom_size) {
            throw RomLoadError("ROM trop grande (maximum 8388608 octets)");
        }

        CartridgeHeader header;
        header.cartridge_type = rom[0x147];
        switch (header.cartridge_type) {
        case 0x00: case 0x08: case 0x09: header.mbc = MbcType::none; break;
        case 0x01: case 0x02: case 0x03: header.mbc = MbcType::mbc1; break;
        case 0x05: case 0x06: header.mbc = MbcType::mbc2; break;
        case 0x0f: case 0x10: case 0x11: case 0x12: case 0x13: header.mbc = MbcType::mbc3; break;
        case 0x19: case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e:
            header.mbc = MbcType::mbc5; break;
        default: header.mbc = MbcType::unsupported; break;
        }

        switch (header.cartridge_type) {
        case 0x02: case 0x03: case 0x05: case 0x06: case 0x08: case 0x09:
        case 0x10: case 0x12: case 0x13: case 0x1a: case 0x1b: case 0x1d: case 0x1e:
            header.has_ram = true; break;
        default: break;
        }
        switch (header.cartridge_type) {
        case 0x03: case 0x06: case 0x09: case 0x0f: case 0x10: case 0x13:
        case 0x1b: case 0x1e: header.has_battery = true; break;
        default: break;
        }
        header.has_rtc = header.cartridge_type == 0x0f || header.cartridge_type == 0x10;
        if (header.mbc == MbcType::mbc2) {
            header.has_ram = true;
            header.ram_size = 512;
        } else if (header.has_ram) {
            switch (rom[0x149]) {
            case 0x01: header.ram_size = 2 * 1024; break;
            case 0x02: header.ram_size = 8 * 1024; break;
            case 0x03: header.ram_size = 32 * 1024; break;
            case 0x04: header.ram_size = 128 * 1024; break;
            case 0x05: header.ram_size = 64 * 1024; break;
            default: header.ram_size = 0; break;
            }
        }
        const auto cgb_flag = rom[0x143];
        header.uses_color = cgb_flag == 0x80 || cgb_flag == 0xc0;
        return header;
    }
};

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
    virtual void tick(int) {}
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

class Mbc0 final : public Cartridge {
public:
    using Cartridge::Cartridge;
    int read_rom(int address) const override {
        return static_cast<std::size_t>(address) < rom_->size()
            ? (*rom_)[static_cast<std::size_t>(address)] : 0xff;
    }
    void write_control(int, int) override {}
    int read_ram(int address) const override {
        const auto offset = static_cast<std::size_t>(address - 0xa000);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }
    void write_ram(int address, int value) override {
        const auto offset = static_cast<std::size_t>(address - 0xa000);
        if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
    }
    void save_state(BinaryWriter& out) const override { out.raw(ram_); }
    void load_state(BinaryReader& in) override { in.raw(ram_); }
};

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

class Mbc2 final : public Cartridge {
public:
    using Cartridge::Cartridge;
    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address > 0x3fff) return;
        if ((address & 0x100) == 0) ram_enabled_ = (value & 0x0f) == 0x0a;
        else { const int v = value & 0x0f; rom_bank_ = v == 0 ? 1 : v; }
    }
    int read_ram(int address) const override {
        if (!ram_enabled_) return 0xff;
        return 0xf0 | (ram_[static_cast<std::size_t>((address - 0xa000) & 0x1ff)] & 0x0f);
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_) return;
        ram_[static_cast<std::size_t>((address - 0xa000) & 0x1ff)] = static_cast<std::uint8_t>(value & 0x0f);
        mark_written();
    }
    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_ = in.i32(); in.raw(ram_);
    }
private:
    bool ram_enabled_{};
    int rom_bank_{1};
};

class Mbc5 final : public Cartridge {
public:
    Mbc5(RomImage rom, CartridgeHeader header)
        : Cartridge(std::move(rom), header), has_rumble_(header.cartridge_type >= 0x1c && header.cartridge_type <= 0x1e) {}
    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank((rom_bank_high_ << 8) | rom_bank_low_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address <= 0x1fff) ram_enabled_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x2fff) rom_bank_low_ = byte(value);
        else if (address <= 0x3fff) rom_bank_high_ = value & 1;
        else if (address <= 0x5fff) ram_bank_ = value & (has_rumble_ ? 7 : 0x0f);
    }
    int read_ram(int address) const override {
        if (!ram_enabled_ || ram_.empty()) return 0xff;
        const auto offset = static_cast<std::size_t>(ram_bank_ * ram_bank_size + address - 0xa000);
        return offset < ram_.size() ? ram_[offset] : 0xff;
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_ || ram_.empty()) return;
        const auto offset = static_cast<std::size_t>(ram_bank_ * ram_bank_size + address - 0xa000);
        if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
    }
    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_low_); out.i32(rom_bank_high_);
        out.i32(ram_bank_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_low_ = in.i32(); rom_bank_high_ = in.i32();
        ram_bank_ = in.i32(); in.raw(ram_);
    }
private:
    bool ram_enabled_{};
    int rom_bank_low_{1};
    int rom_bank_high_{};
    int ram_bank_{};
    bool has_rumble_{};
};

class Mbc3 final : public Cartridge {
public:
    static constexpr std::size_t rtc_footer_size = 48;
    static constexpr std::size_t rtc_footer_min_size = 44;

    Mbc3(RomImage rom, CartridgeHeader header, Clock clock)
        : Cartridge(std::move(rom), header), clock_(std::move(clock)),
          last_sync_epoch_(clock_()), has_rtc_(header.has_rtc) {}

    int read_rom(int address) const override {
        const int bank = address < rom_bank_size ? 0 : normalize_rom_bank(rom_bank_);
        const auto offset = static_cast<std::size_t>(bank * rom_bank_size + (address & (rom_bank_size - 1)));
        return offset < rom_->size() ? (*rom_)[offset] : 0xff;
    }
    void write_control(int address, int value) override {
        if (address <= 0x1fff) ram_enabled_ = (value & 0x0f) == 0x0a;
        else if (address <= 0x3fff) { const int v = value & 0x7f; rom_bank_ = v == 0 ? 1 : v; }
        else if (address <= 0x5fff) ram_bank_or_rtc_ = value & 0x0f;
        else {
            if (latch_armed_ && value == 1 && has_rtc_) latch_rtc();
            latch_armed_ = value == 0;
        }
    }
    int read_ram(int address) const override {
        if (!ram_enabled_) return 0xff;
        if (ram_bank_or_rtc_ <= 3) {
            const auto offset = static_cast<std::size_t>(ram_bank_or_rtc_ * ram_bank_size + address - 0xa000);
            return offset < ram_.size() ? ram_[offset] : 0xff;
        }
        if (!has_rtc_) return 0xff;
        switch (ram_bank_or_rtc_) {
        case 0x08: return latch_seconds_; case 0x09: return latch_minutes_;
        case 0x0a: return latch_hours_; case 0x0b: return latch_day_low_;
        case 0x0c: return latch_day_high_; default: return 0xff;
        }
    }
    void write_ram(int address, int value) override {
        if (!ram_enabled_) return;
        if (ram_bank_or_rtc_ <= 3) {
            const auto offset = static_cast<std::size_t>(ram_bank_or_rtc_ * ram_bank_size + address - 0xa000);
            if (offset < ram_.size()) { ram_[offset] = static_cast<std::uint8_t>(value); mark_written(); }
            return;
        }
        if (!has_rtc_) return;
        switch (ram_bank_or_rtc_) {
        case 0x08: sync_rtc(); rtc_seconds_ = value & 0x3f; mark_written(); break;
        case 0x09: sync_rtc(); rtc_minutes_ = value & 0x3f; mark_written(); break;
        case 0x0a: sync_rtc(); rtc_hours_ = value & 0x1f; mark_written(); break;
        case 0x0b: sync_rtc(); rtc_days_ = (rtc_days_ & 0x100) | byte(value); mark_written(); break;
        case 0x0c:
            sync_rtc(); rtc_days_ = (rtc_days_ & 0xff) | ((value & 1) << 8);
            rtc_halt_ = (value & 0x40) != 0; rtc_day_carry_ = (value & 0x80) != 0;
            mark_written(); break;
        default: break;
        }
    }

    std::optional<std::vector<std::uint8_t>> export_battery() override {
        if (!header_.has_battery) return std::nullopt;
        if (!has_rtc_) return Cartridge::export_battery();
        sync_rtc(); latch_rtc();
        std::vector<std::uint8_t> output(ram_.size() + rtc_footer_size);
        std::copy(ram_.begin(), ram_.end(), output.begin());
        std::size_t position = ram_.size();
        const std::array values{
            rtc_seconds_, rtc_minutes_, rtc_hours_, rtc_days_ & 0xff,
            ((rtc_days_ >> 8) & 1) | (rtc_halt_ ? 0x40 : 0) | (rtc_day_carry_ ? 0x80 : 0),
            latch_seconds_, latch_minutes_, latch_hours_, latch_day_low_, latch_day_high_,
        };
        for (const int value : values) {
            for (int i = 0; i < 4; ++i) output[position++] = static_cast<std::uint8_t>(value >> (i * 8));
        }
        auto timestamp = static_cast<std::uint64_t>(last_sync_epoch_);
        for (int i = 0; i < 8; ++i) { output[position++] = static_cast<std::uint8_t>(timestamp); timestamp >>= 8U; }
        return output;
    }

    void import_battery(std::span<const std::uint8_t> data) override {
        Cartridge::import_battery(data);
        if (!has_rtc_ || data.size() < ram_.size() + rtc_footer_min_size) return;
        std::size_t position = ram_.size();
        const auto read_u32_le = [&]() {
            std::uint32_t value{};
            for (unsigned i = 0; i < 4; ++i) value |= static_cast<std::uint32_t>(data[position++]) << (i * 8U);
            return value;
        };
        rtc_seconds_ = static_cast<int>(read_u32_le() & 0x3fU);
        rtc_minutes_ = static_cast<int>(read_u32_le() & 0x3fU);
        rtc_hours_ = static_cast<int>(read_u32_le() & 0x1fU);
        const int day_low = static_cast<int>(read_u32_le() & 0xffU);
        const auto day_high = read_u32_le();
        rtc_days_ = day_low | (static_cast<int>(day_high & 1U) << 8);
        rtc_halt_ = (day_high & 0x40U) != 0; rtc_day_carry_ = (day_high & 0x80U) != 0;
        latch_seconds_ = static_cast<int>(read_u32_le() & 0x3fU);
        latch_minutes_ = static_cast<int>(read_u32_le() & 0x3fU);
        latch_hours_ = static_cast<int>(read_u32_le() & 0x1fU);
        latch_day_low_ = static_cast<int>(read_u32_le() & 0xffU);
        latch_day_high_ = static_cast<int>(read_u32_le() & 0xffU);
        const int timestamp_bytes = data.size() >= ram_.size() + rtc_footer_size ? 8 : 4;
        std::uint64_t timestamp{};
        for (unsigned i = 0; i < static_cast<unsigned>(timestamp_bytes); ++i) {
            timestamp |= static_cast<std::uint64_t>(data[position + static_cast<std::size_t>(i)]) << (i * 8U);
        }
        last_sync_epoch_ = static_cast<std::int64_t>(timestamp);
        sync_rtc(); mark_clean();
    }

    void save_state(BinaryWriter& out) const override {
        out.boolean(ram_enabled_); out.i32(rom_bank_); out.i32(ram_bank_or_rtc_);
        out.boolean(latch_armed_); out.i32(rtc_seconds_); out.i32(rtc_minutes_);
        out.i32(rtc_hours_); out.i32(rtc_days_); out.boolean(rtc_halt_);
        out.boolean(rtc_day_carry_); out.i32(latch_seconds_); out.i32(latch_minutes_);
        out.i32(latch_hours_); out.i32(latch_day_low_); out.i32(latch_day_high_);
        out.i64(last_sync_epoch_); out.raw(ram_);
    }
    void load_state(BinaryReader& in) override {
        ram_enabled_ = in.boolean(); rom_bank_ = in.i32(); ram_bank_or_rtc_ = in.i32();
        latch_armed_ = in.boolean(); rtc_seconds_ = in.i32(); rtc_minutes_ = in.i32();
        rtc_hours_ = in.i32(); rtc_days_ = in.i32(); rtc_halt_ = in.boolean();
        rtc_day_carry_ = in.boolean(); latch_seconds_ = in.i32(); latch_minutes_ = in.i32();
        latch_hours_ = in.i32(); latch_day_low_ = in.i32(); latch_day_high_ = in.i32();
        last_sync_epoch_ = in.i64(); in.raw(ram_);
    }

private:
    void sync_rtc() {
        const auto now = clock_();
        auto elapsed = now - last_sync_epoch_;
        last_sync_epoch_ = now;
        if (rtc_halt_ || elapsed <= 0) return;
        elapsed += rtc_seconds_ + 60LL * rtc_minutes_ + 3600LL * rtc_hours_ + 86400LL * rtc_days_;
        rtc_seconds_ = static_cast<int>(elapsed % 60); elapsed /= 60;
        rtc_minutes_ = static_cast<int>(elapsed % 60); elapsed /= 60;
        rtc_hours_ = static_cast<int>(elapsed % 24); elapsed /= 24;
        rtc_days_ = static_cast<int>(elapsed & 0x1ff);
        if (elapsed > 0x1ff) rtc_day_carry_ = true;
    }
    void latch_rtc() {
        sync_rtc(); latch_seconds_ = rtc_seconds_; latch_minutes_ = rtc_minutes_;
        latch_hours_ = rtc_hours_; latch_day_low_ = rtc_days_ & 0xff;
        latch_day_high_ = ((rtc_days_ >> 8) & 1) | (rtc_halt_ ? 0x40 : 0) | (rtc_day_carry_ ? 0x80 : 0);
    }

    Clock clock_;
    bool ram_enabled_{};
    int rom_bank_{1};
    int ram_bank_or_rtc_{};
    bool latch_armed_{};
    int rtc_seconds_{};
    int rtc_minutes_{};
    int rtc_hours_{};
    int rtc_days_{};
    bool rtc_halt_{};
    bool rtc_day_carry_{};
    int latch_seconds_{};
    int latch_minutes_{};
    int latch_hours_{};
    int latch_day_low_{};
    int latch_day_high_{};
    std::int64_t last_sync_epoch_{};
    bool has_rtc_{};
};

std::unique_ptr<Cartridge> Cartridge::create(RomImage rom, Clock clock) {
    const auto header = CartridgeHeader::parse(*rom);
    switch (header.mbc) {
    case MbcType::none: return std::make_unique<Mbc0>(std::move(rom), header);
    case MbcType::mbc1: return std::make_unique<Mbc1>(std::move(rom), header);
    case MbcType::mbc2: return std::make_unique<Mbc2>(std::move(rom), header);
    case MbcType::mbc3: return std::make_unique<Mbc3>(std::move(rom), header, std::move(clock));
    case MbcType::mbc5: return std::make_unique<Mbc5>(std::move(rom), header);
    case MbcType::unsupported:
        throw RomLoadError("Type de cartouche non pris en charge");
    }
    throw RomLoadError("Type de cartouche non pris en charge");
}

class Joypad {
public:
    explicit Joypad(InterruptController& interrupts) : interrupts_(interrupts) {}

    void set_button(Button button, bool pressed) noexcept {
        bool action{};
        int bit{};
        switch (button) {
        case Button::a: action = true; bit = 1; break;
        case Button::b: action = true; bit = 2; break;
        case Button::select: action = true; bit = 4; break;
        case Button::start: action = true; bit = 8; break;
        case Button::right: bit = 1; break;
        case Button::left: bit = 2; break;
        case Button::up: bit = 4; break;
        case Button::down: bit = 8; break;
        case Button::l: case Button::r: return;
        }
        auto& state = action ? action_state_ : direction_state_;
        const bool was_pressed = (state & bit) != 0;
        state = pressed ? state | bit : state & ~bit;
        const bool selected = (action && (select_ & 0x20) == 0) || (!action && (select_ & 0x10) == 0);
        if (pressed && !was_pressed && selected) interrupts_.request(Interrupt::joypad);
    }

    [[nodiscard]] int read() const noexcept {
        int lines = 0x0f;
        if ((select_ & 0x10) == 0) lines &= ~direction_state_;
        if ((select_ & 0x20) == 0) lines &= ~action_state_;
        return 0xc0 | select_ | (lines & 0x0f);
    }
    void write(int value) noexcept { select_ = value & 0x30; }
    void save(BinaryWriter& out) const { out.i32(select_); }
    void load(BinaryReader& in) { select_ = in.i32() & 0x30; }

private:
    InterruptController& interrupts_;
    int select_{0x30};
    int action_state_{};
    int direction_state_{};
};

class SerialPort {
public:
    explicit SerialPort(InterruptController& interrupts) : interrupts_(interrupts) {}

    void tick(int cycles) noexcept {
        if (remaining_cycles_ <= 0) return;
        remaining_cycles_ -= cycles;
        if (remaining_cycles_ <= 0) {
            remaining_cycles_ = 0; data_ = 0xff; control_ &= 0x7f;
            interrupts_.request(Interrupt::serial);
        }
    }
    [[nodiscard]] int read_data() const noexcept { return data_; }
    void write_data(int value) noexcept { data_ = byte(value); }
    [[nodiscard]] int read_control() const noexcept { return control_ | 0x7e; }
    void write_control(int value) noexcept {
        control_ = (value & 0x81) | 0x7e;
        remaining_cycles_ = (value & 0x81) == 0x81 ? 4096 : 0;
    }
    void save(BinaryWriter& out) const {
        out.i32(data_); out.i32(control_); out.i32(remaining_cycles_);
    }
    void load(BinaryReader& in) {
        data_ = byte(in.i32()); control_ = in.i32(); remaining_cycles_ = in.i32();
    }

private:
    InterruptController& interrupts_;
    int data_{};
    int control_{0x7e};
    int remaining_cycles_{};
};

class Timer {
public:
    explicit Timer(InterruptController& interrupts) : interrupts_(interrupts) {}

    void tick(int cycles) noexcept {
        for (int i = 0; i < cycles; ++i) {
            if (reload_delay_ > 0 && --reload_delay_ == 0) {
                tima_ = tma_; interrupts_.request(Interrupt::timer); reload_delay_ = -1;
            }
            set_div_counter(div_counter_ + 1);
        }
    }
    [[nodiscard]] int read_div() const noexcept { return (div_counter_ >> 8) & 0xff; }
    void write_div() noexcept { set_div_counter(0); }
    [[nodiscard]] int read_tima() const noexcept { return tima_; }
    void write_tima(int value) noexcept { if (reload_delay_ > 0) reload_delay_ = -1; tima_ = byte(value); }
    [[nodiscard]] int read_tac() const noexcept { return tac_ | 0xf8; }
    void write_tac(int value) noexcept {
        const bool before = signal(div_counter_, tac_);
        tac_ = (value & 7) | 0xf8;
        if (before && !signal(div_counter_, tac_)) increment_tima();
    }
    [[nodiscard]] int tma() const noexcept { return tma_; }
    void set_tma(int value) noexcept { tma_ = byte(value); }
    void save(BinaryWriter& out) const {
        out.i32(div_counter_); out.i32(tima_); out.i32(tma_); out.i32(read_tac()); out.i32(reload_delay_);
    }
    void load(BinaryReader& in) {
        div_counter_ = word(in.i32()); tima_ = byte(in.i32()); tma_ = byte(in.i32());
        tac_ = in.i32() | 0xf8; reload_delay_ = in.i32();
    }

private:
    static int selected_mask(int tac) noexcept {
        switch (tac & 3) { case 0: return 1 << 9; case 1: return 1 << 3; case 2: return 1 << 5; default: return 1 << 7; }
    }
    static bool signal(int counter, int tac) noexcept {
        return (tac & 4) != 0 && (counter & selected_mask(tac)) != 0;
    }
    void increment_tima() noexcept { tima_ = byte(tima_ + 1); if (tima_ == 0) reload_delay_ = 4; }
    void set_div_counter(int value) noexcept {
        const bool before = signal(div_counter_, tac_);
        div_counter_ = word(value);
        if (before && !signal(div_counter_, tac_)) increment_tima();
    }

    InterruptController& interrupts_;
    int div_counter_{0xab00};
    int tima_{};
    int tma_{};
    int tac_{0xf8};
    int reload_delay_{-1};
};

class Apu {
public:
    static constexpr int sample_rate = 32768;

    void tick(int cycles) {
        int remaining = cycles;
        while (remaining > 0) {
            const int slice = std::min(sample_timer_, remaining);
            if (power_on_) {
                square1_.step(slice); square2_.step(slice); wave_.step(slice); noise_.step(slice);
                frame_timer_ -= slice;
                while (frame_timer_ <= 0) { frame_timer_ += frame_period; clock_frame_sequencer(); }
            }
            sample_timer_ -= slice;
            remaining -= slice;
            if (sample_timer_ <= 0) { sample_timer_ += cycles_per_sample; emit_sample(); }
        }
    }

    [[nodiscard]] std::size_t read_samples(std::span<std::int16_t> destination) noexcept {
        std::size_t copied{};
        const auto maximum = destination.size() & ~std::size_t{1};
        while (copied < maximum && ring_count_ > 0) {
            destination[copied++] = ring_[ring_read_];
            ring_read_ = (ring_read_ + 1) & (ring_capacity - 1);
            --ring_count_;
        }
        return copied;
    }

    [[nodiscard]] int read(int address) const noexcept {
        if (address >= 0xff30 && address <= 0xff3f) return wave_.ram[static_cast<std::size_t>(address - 0xff30)];
        const int offset = address - 0xff10;
        if (offset < 0 || offset >= static_cast<int>(raw_registers_.size())) return 0xff;
        if (address == 0xff26) {
            int status = power_on_ ? 0x80 : 0;
            if (square1_.enabled) status |= 1;
            if (square2_.enabled) status |= 2;
            if (wave_.enabled) status |= 4;
            if (noise_.enabled) status |= 8;
            return status | 0x70;
        }
        return raw_registers_[static_cast<std::size_t>(offset)] | read_masks[static_cast<std::size_t>(offset)];
    }

    void write(int address, int value) {
        value = byte(value);
        if (address >= 0xff30 && address <= 0xff3f) {
            wave_.ram[static_cast<std::size_t>(address - 0xff30)] = static_cast<std::uint8_t>(value);
            return;
        }
        const int offset = address - 0xff10;
        if (offset < 0 || offset >= static_cast<int>(raw_registers_.size())) return;
        if (!power_on_ && address != 0xff26) return;
        raw_registers_[static_cast<std::size_t>(offset)] = value;
        switch (address) {
        case 0xff10:
            square1_.sweep_period = (value >> 4) & 7; square1_.sweep_negate = (value & 8) != 0;
            square1_.sweep_shift = value & 7; break;
        case 0xff11: square1_.duty = (value >> 6) & 3; square1_.length = 64 - (value & 0x3f); break;
        case 0xff12: configure_envelope(square1_, value); break;
        case 0xff13: square1_.frequency = (square1_.frequency & 0x700) | value; break;
        case 0xff14:
            square1_.frequency = (square1_.frequency & 0xff) | ((value & 7) << 8);
            square1_.length_enabled = (value & 0x40) != 0; if ((value & 0x80) != 0) square1_.trigger(); break;
        case 0xff16: square2_.duty = (value >> 6) & 3; square2_.length = 64 - (value & 0x3f); break;
        case 0xff17: configure_envelope(square2_, value); break;
        case 0xff18: square2_.frequency = (square2_.frequency & 0x700) | value; break;
        case 0xff19:
            square2_.frequency = (square2_.frequency & 0xff) | ((value & 7) << 8);
            square2_.length_enabled = (value & 0x40) != 0; if ((value & 0x80) != 0) square2_.trigger(); break;
        case 0xff1a: wave_.dac_enabled = (value & 0x80) != 0; if (!wave_.dac_enabled) wave_.enabled = false; break;
        case 0xff1b: wave_.length = 256 - value; break;
        case 0xff1c: wave_.volume_code = (value >> 5) & 3; break;
        case 0xff1d: wave_.frequency = (wave_.frequency & 0x700) | value; break;
        case 0xff1e:
            wave_.frequency = (wave_.frequency & 0xff) | ((value & 7) << 8);
            wave_.length_enabled = (value & 0x40) != 0; if ((value & 0x80) != 0) wave_.trigger(); break;
        case 0xff20: noise_.length = 64 - (value & 0x3f); break;
        case 0xff21:
            noise_.envelope_initial = (value >> 4) & 0x0f; noise_.envelope_add = (value & 8) != 0;
            noise_.envelope_period = value & 7; noise_.dac_enabled = (value & 0xf8) != 0;
            if (!noise_.dac_enabled) noise_.enabled = false;
            break;
        case 0xff22:
            noise_.clock_shift = (value >> 4) & 0x0f; noise_.width_mode7 = (value & 8) != 0;
            noise_.divisor_code = value & 7; break;
        case 0xff23:
            noise_.length_enabled = (value & 0x40) != 0; if ((value & 0x80) != 0) noise_.trigger(); break;
        case 0xff24: nr50_ = value; break;
        case 0xff25: nr51_ = value; break;
        case 0xff26: {
            const bool turn_on = (value & 0x80) != 0;
            if (power_on_ && !turn_on) power_down();
            else if (!power_on_ && turn_on) { power_on_ = true; frame_step_ = 0; frame_timer_ = frame_period; }
            break;
        }
        default: break;
        }
    }

    void save(BinaryWriter& out) const {
        out.boolean(power_on_); out.i32(nr50_); out.i32(nr51_);
        for (const int value : raw_registers_) out.i32(value);
        out.i32(frame_timer_); out.i32(frame_step_); out.i32(sample_timer_);
        out.f64(capacitor_left_); out.f64(capacitor_right_);
        square1_.save(out); square2_.save(out); wave_.save(out); noise_.save(out);
    }
    void load(BinaryReader& in) {
        power_on_ = in.boolean(); nr50_ = in.i32(); nr51_ = in.i32();
        for (auto& value : raw_registers_) value = in.i32();
        frame_timer_ = in.i32(); frame_step_ = in.i32(); sample_timer_ = in.i32();
        capacitor_left_ = in.f64(); capacitor_right_ = in.f64();
        square1_.load(in); square2_.load(in); wave_.load(in); noise_.load(in);
        ring_read_ = 0; ring_write_ = 0; ring_count_ = 0;
    }

private:
    struct Square {
        explicit Square(bool sweep) : has_sweep(sweep) {}
        bool has_sweep{};
        bool enabled{};
        bool dac_enabled{};
        int duty{};
        int length{};
        bool length_enabled{};
        int envelope_initial{};
        bool envelope_add{};
        int envelope_period{};
        int volume{};
        int envelope_timer{};
        int frequency{};
        int timer{2048 * 4};
        int duty_position{};
        int sweep_period{};
        bool sweep_negate{};
        int sweep_shift{};
        int sweep_timer{};
        bool sweep_enabled{};
        int shadow_frequency{};
        int accumulator{};

        [[nodiscard]] int output() const noexcept {
            return enabled && dac_enabled && duty_table[static_cast<std::size_t>(duty)][static_cast<std::size_t>(duty_position)] == 1 ? volume : 0;
        }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? output() * 2 - 15 : 0; }
        void step(int cycles) noexcept {
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) { timer = (2048 - frequency) * 4; duty_position = (duty_position + 1) & 7; }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        void clock_envelope() noexcept {
            if (envelope_period == 0) return;
            if (--envelope_timer <= 0) {
                envelope_timer = envelope_period;
                volume = envelope_add ? std::min(15, volume + 1) : std::max(0, volume - 1);
            }
        }
        int next_sweep_frequency() noexcept {
            const int delta = shadow_frequency >> sweep_shift;
            const int next = sweep_negate ? shadow_frequency - delta : shadow_frequency + delta;
            if (next > 2047) enabled = false;
            return next;
        }
        void clock_sweep() noexcept {
            if (!has_sweep || !sweep_enabled) return;
            if (--sweep_timer <= 0) {
                sweep_timer = sweep_period != 0 ? sweep_period : 8;
                if (sweep_period != 0) {
                    const int next = next_sweep_frequency();
                    if (next <= 2047 && sweep_shift != 0) {
                        shadow_frequency = next; frequency = next; static_cast<void>(next_sweep_frequency());
                    }
                }
            }
        }
        void trigger() noexcept {
            enabled = dac_enabled; if (length == 0) length = 64; timer = (2048 - frequency) * 4;
            volume = envelope_initial; envelope_timer = envelope_period;
            if (has_sweep) {
                shadow_frequency = frequency; sweep_timer = sweep_period != 0 ? sweep_period : 8;
                sweep_enabled = sweep_period != 0 || sweep_shift != 0;
                if (sweep_shift != 0) static_cast<void>(next_sweep_frequency());
            }
        }
        void reset() noexcept {
            enabled = false; dac_enabled = false; duty = 0; length = 0; length_enabled = false;
            envelope_initial = 0; envelope_add = false; envelope_period = 0; volume = 0;
            envelope_timer = 0; frequency = 0; duty_position = 0; sweep_period = 0;
            sweep_negate = false; sweep_shift = 0; sweep_timer = 0; sweep_enabled = false;
            shadow_frequency = 0;
        }
        void save(BinaryWriter& out) const {
            out.boolean(enabled); out.boolean(dac_enabled); out.i32(duty); out.i32(length);
            out.boolean(length_enabled); out.i32(envelope_initial); out.boolean(envelope_add);
            out.i32(envelope_period); out.i32(volume); out.i32(envelope_timer);
            out.i32(frequency); out.i32(timer); out.i32(duty_position); out.i32(sweep_period);
            out.boolean(sweep_negate); out.i32(sweep_shift); out.i32(sweep_timer);
            out.boolean(sweep_enabled); out.i32(shadow_frequency);
        }
        void load(BinaryReader& in) {
            enabled = in.boolean(); dac_enabled = in.boolean(); duty = in.i32(); length = in.i32();
            length_enabled = in.boolean(); envelope_initial = in.i32(); envelope_add = in.boolean();
            envelope_period = in.i32(); volume = in.i32(); envelope_timer = in.i32();
            frequency = in.i32(); timer = in.i32(); duty_position = in.i32(); sweep_period = in.i32();
            sweep_negate = in.boolean(); sweep_shift = in.i32(); sweep_timer = in.i32();
            sweep_enabled = in.boolean(); shadow_frequency = in.i32(); accumulator = 0;
        }
    };

    struct Wave {
        std::array<std::uint8_t, 16> ram{};
        bool enabled{};
        bool dac_enabled{};
        int length{};
        bool length_enabled{};
        int volume_code{};
        int frequency{};
        int timer{2048 * 2};
        int position{};
        int sample_buffer{};
        int accumulator{};

        [[nodiscard]] int output() const noexcept {
            if (!enabled || !dac_enabled) return 0;
            if (volume_code == 0) return 0;
            if (volume_code == 1) return sample_buffer;
            return volume_code == 2 ? sample_buffer >> 1 : sample_buffer >> 2;
        }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? output() * 2 - 15 : 0; }
        void step(int cycles) noexcept {
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) {
                    timer = (2048 - frequency) * 2; position = (position + 1) & 31;
                    const int value = ram[static_cast<std::size_t>(position >> 1)];
                    sample_buffer = (position & 1) == 0 ? value >> 4 : value & 0x0f;
                }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        void trigger() noexcept { enabled = dac_enabled; if (length == 0) length = 256; timer = (2048 - frequency) * 2; position = 0; }
        void reset() noexcept {
            enabled = false; dac_enabled = false; length = 0; length_enabled = false;
            volume_code = 0; frequency = 0; position = 0; sample_buffer = 0;
        }
        void save(BinaryWriter& out) const {
            out.raw(ram); out.boolean(enabled); out.boolean(dac_enabled); out.i32(length);
            out.boolean(length_enabled); out.i32(volume_code); out.i32(frequency);
            out.i32(timer); out.i32(position); out.i32(sample_buffer);
        }
        void load(BinaryReader& in) {
            in.raw(ram); enabled = in.boolean(); dac_enabled = in.boolean(); length = in.i32();
            length_enabled = in.boolean(); volume_code = in.i32(); frequency = in.i32();
            timer = in.i32(); position = in.i32(); sample_buffer = in.i32(); accumulator = 0;
        }
    };

    struct Noise {
        bool enabled{};
        bool dac_enabled{};
        int length{};
        bool length_enabled{};
        int envelope_initial{};
        bool envelope_add{};
        int envelope_period{};
        int volume{};
        int envelope_timer{};
        int divisor_code{};
        bool width_mode7{};
        int clock_shift{};
        int lfsr{0x7fff};
        int timer{8};
        int accumulator{};

        [[nodiscard]] int period() const noexcept { return divisors[static_cast<std::size_t>(divisor_code)] << clock_shift; }
        [[nodiscard]] int output() const noexcept { return enabled && dac_enabled && (lfsr & 1) == 0 ? volume : 0; }
        [[nodiscard]] int dac_output() const noexcept { return dac_enabled ? output() * 2 - 15 : 0; }
        void step(int cycles) noexcept {
            int remaining = cycles;
            while (remaining > 0) {
                const int slice = std::min(timer, remaining);
                accumulator += dac_output() * slice; timer -= slice; remaining -= slice;
                if (timer <= 0) {
                    timer += period(); const int feedback = (lfsr ^ (lfsr >> 1)) & 1;
                    lfsr = (lfsr >> 1) | (feedback << 14);
                    if (width_mode7) lfsr = (lfsr & ~(1 << 6)) | (feedback << 6);
                }
            }
        }
        double drain_average() noexcept {
            const double average = static_cast<double>(accumulator) / cycles_per_sample;
            accumulator = 0; return average;
        }
        void clock_length() noexcept { if (length_enabled && length > 0 && --length == 0) enabled = false; }
        void clock_envelope() noexcept {
            if (envelope_period == 0) return;
            if (--envelope_timer <= 0) {
                envelope_timer = envelope_period;
                volume = envelope_add ? std::min(15, volume + 1) : std::max(0, volume - 1);
            }
        }
        void trigger() noexcept {
            enabled = dac_enabled; if (length == 0) length = 64; timer = period();
            volume = envelope_initial; envelope_timer = envelope_period; lfsr = 0x7fff;
        }
        void reset() noexcept {
            enabled = false; dac_enabled = false; length = 0; length_enabled = false;
            envelope_initial = 0; envelope_add = false; envelope_period = 0; volume = 0;
            envelope_timer = 0; divisor_code = 0; width_mode7 = false; clock_shift = 0; lfsr = 0x7fff;
        }
        void save(BinaryWriter& out) const {
            out.boolean(enabled); out.boolean(dac_enabled); out.i32(length); out.boolean(length_enabled);
            out.i32(envelope_initial); out.boolean(envelope_add); out.i32(envelope_period);
            out.i32(volume); out.i32(envelope_timer); out.i32(divisor_code);
            out.boolean(width_mode7); out.i32(clock_shift); out.i32(lfsr); out.i32(timer);
        }
        void load(BinaryReader& in) {
            enabled = in.boolean(); dac_enabled = in.boolean(); length = in.i32(); length_enabled = in.boolean();
            envelope_initial = in.i32(); envelope_add = in.boolean(); envelope_period = in.i32();
            volume = in.i32(); envelope_timer = in.i32(); divisor_code = in.i32();
            width_mode7 = in.boolean(); clock_shift = in.i32(); lfsr = in.i32(); timer = in.i32(); accumulator = 0;
        }
    };

    static void configure_envelope(Square& square, int value) noexcept {
        square.envelope_initial = (value >> 4) & 0x0f; square.envelope_add = (value & 8) != 0;
        square.envelope_period = value & 7; square.dac_enabled = (value & 0xf8) != 0;
        if (!square.dac_enabled) square.enabled = false;
    }
    void clock_frame_sequencer() noexcept {
        if (frame_step_ == 0 || frame_step_ == 4) clock_lengths();
        else if (frame_step_ == 2 || frame_step_ == 6) { clock_lengths(); square1_.clock_sweep(); }
        else if (frame_step_ == 7) { square1_.clock_envelope(); square2_.clock_envelope(); noise_.clock_envelope(); }
        frame_step_ = (frame_step_ + 1) & 7;
    }
    void clock_lengths() noexcept {
        square1_.clock_length(); square2_.clock_length(); wave_.clock_length(); noise_.clock_length();
    }
    static std::int16_t clamp_short(double value) noexcept {
        return static_cast<std::int16_t>(std::clamp(static_cast<int>(value), -32768, 32767));
    }
    void emit_sample() noexcept {
        const double c1 = square1_.drain_average(); const double c2 = square2_.drain_average();
        const double c3 = wave_.drain_average(); const double c4 = noise_.drain_average();
        double left{}; double right{};
        if (power_on_) {
            if ((nr51_ & 0x10) != 0) left += c1;
            if ((nr51_ & 0x20) != 0) left += c2;
            if ((nr51_ & 0x40) != 0) left += c3;
            if ((nr51_ & 0x80) != 0) left += c4;
            if ((nr51_ & 0x01) != 0) right += c1;
            if ((nr51_ & 0x02) != 0) right += c2;
            if ((nr51_ & 0x04) != 0) right += c3;
            if ((nr51_ & 0x08) != 0) right += c4;
            left *= static_cast<double>(((nr50_ >> 4) & 7) + 1) * mix_gain;
            right *= static_cast<double>((nr50_ & 7) + 1) * mix_gain;
        }
        const double filtered_left = left - capacitor_left_;
        capacitor_left_ = left - filtered_left * charge_factor;
        const double filtered_right = right - capacitor_right_;
        capacitor_right_ = right - filtered_right * charge_factor;
        push_sample(clamp_short(filtered_left), clamp_short(filtered_right));
    }
    void push_sample(std::int16_t left, std::int16_t right) noexcept {
        if (ring_count_ > ring_capacity - 2) { ring_read_ = (ring_read_ + 2) & (ring_capacity - 1); ring_count_ -= 2; }
        ring_[ring_write_] = left; ring_[(ring_write_ + 1) & (ring_capacity - 1)] = right;
        ring_write_ = (ring_write_ + 2) & (ring_capacity - 1); ring_count_ += 2;
    }
    void power_down() noexcept {
        power_on_ = false; square1_.reset(); square2_.reset(); wave_.reset(); noise_.reset();
        nr50_ = 0; nr51_ = 0; raw_registers_.fill(0);
    }

    static constexpr int cycles_per_sample = 128;
    static constexpr int frame_period = 8192;
    static constexpr std::size_t ring_capacity = 8192;
    static constexpr int mix_gain = 64;
    static constexpr double charge_factor = 0.994638;
    inline static constexpr std::array<std::array<int, 8>, 4> duty_table{{
        {{0, 0, 0, 0, 0, 0, 0, 1}}, {{1, 0, 0, 0, 0, 0, 0, 1}},
        {{1, 0, 0, 0, 0, 1, 1, 1}}, {{0, 1, 1, 1, 1, 1, 1, 0}},
    }};
    inline static constexpr std::array<int, 8> divisors{{8, 16, 32, 48, 64, 80, 96, 112}};
    inline static constexpr std::array<int, 48> read_masks{{
        0x80, 0x3f, 0x00, 0xff, 0xbf, 0xff, 0x3f, 0x00, 0xff, 0xbf,
        0x7f, 0xff, 0x9f, 0xff, 0xbf, 0xff, 0xff, 0x00, 0x00, 0xbf,
        0x00, 0x00, 0x70, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff,
        0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    }};

    Square square1_{true};
    Square square2_{false};
    Wave wave_{};
    Noise noise_{};
    bool power_on_{true};
    int nr50_{0x77};
    int nr51_{0xf3};
    std::array<int, 48> raw_registers_ = [] { std::array<int, 48> values{}; values[0x14] = 0x77; values[0x15] = 0xf3; return values; }();
    int frame_timer_{frame_period};
    int frame_step_{};
    int sample_timer_{cycles_per_sample};
    std::array<std::int16_t, ring_capacity> ring_{};
    std::size_t ring_read_{};
    std::size_t ring_write_{};
    std::size_t ring_count_{};
    double capacitor_left_{};
    double capacitor_right_{};
};

class Ppu {
public:
    static constexpr int width = 160;
    static constexpr int height = 144;
    static constexpr int frame_pixels = width * height;
    static constexpr int mode_hblank = 0;
    static constexpr int mode_vblank = 1;
    static constexpr int mode_oam = 2;
    static constexpr int mode_transfer = 3;

    Ppu(InterruptController& interrupts, bool cgb_mode)
        : interrupts_(interrupts), cgb_mode_(cgb_mode) {}

    [[nodiscard]] bool lcd_enabled() const noexcept { return (lcdc_ & 0x80) != 0; }
    [[nodiscard]] int read_vram(int address) const noexcept {
        if (lcd_enabled() && mode_ == mode_transfer) return 0xff;
        return vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))];
    }
    void write_vram(int address, int value) noexcept {
        if (lcd_enabled() && mode_ == mode_transfer) return;
        vram[static_cast<std::size_t>(vram_bank_ * 0x2000 + (address & 0x1fff))] = static_cast<std::uint8_t>(value);
    }
    void write_vram_bank(int value) noexcept { if (cgb_mode_) vram_bank_ = value & 1; }
    [[nodiscard]] int read_vram_bank() const noexcept { return cgb_mode_ ? vram_bank_ | 0xfe : 0xff; }
    [[nodiscard]] int vram_bank() const noexcept { return vram_bank_; }

    [[nodiscard]] int read_oam(int address) const noexcept {
        if (lcd_enabled() && (mode_ == mode_oam || mode_ == mode_transfer)) return 0xff;
        return oam[static_cast<std::size_t>(address & 0xff)];
    }
    void write_oam(int address, int value) noexcept {
        if (lcd_enabled() && (mode_ == mode_oam || mode_ == mode_transfer)) return;
        oam[static_cast<std::size_t>(address & 0xff)] = static_cast<std::uint8_t>(value);
    }
    void write_oam_direct(int index, int value) noexcept { oam[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(value); }

    void write_bcps(int value) noexcept { bcps_index_ = value & 0x3f; bcps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_bcps() const noexcept { return bcps_index_ | (bcps_auto_ ? 0x80 : 0) | 0x40; }
    void write_bcpd(int value) noexcept {
        bg_cram[static_cast<std::size_t>(bcps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(bg_cram, bg_argb_, bcps_index_);
        if (bcps_auto_) bcps_index_ = (bcps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_bcpd() const noexcept { return bg_cram[static_cast<std::size_t>(bcps_index_)]; }
    void write_ocps(int value) noexcept { ocps_index_ = value & 0x3f; ocps_auto_ = (value & 0x80) != 0; }
    [[nodiscard]] int read_ocps() const noexcept { return ocps_index_ | (ocps_auto_ ? 0x80 : 0) | 0x40; }
    void write_ocpd(int value) noexcept {
        obj_cram[static_cast<std::size_t>(ocps_index_)] = static_cast<std::uint8_t>(value);
        recompute_argb(obj_cram, obj_argb_, ocps_index_);
        if (ocps_auto_) ocps_index_ = (ocps_index_ + 1) & 0x3f;
    }
    [[nodiscard]] int read_ocpd() const noexcept { return obj_cram[static_cast<std::size_t>(ocps_index_)]; }

    void write_lcdc(int value) noexcept {
        const bool was_enabled = lcd_enabled();
        lcdc_ = byte(value);
        if (was_enabled && !lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; mode_ = mode_hblank; window_line_ = 0;
            completed_frame.fill(0); frame_ready_ = true; stat_line_ = false;
        } else if (!was_enabled && lcd_enabled()) {
            ly_ = 0; line_dot_ = 0; window_line_ = 0; set_mode(mode_oam); check_stat();
        }
    }
    [[nodiscard]] int read_stat() const noexcept {
        return 0x80 | stat_enable_ | (ly_ == lyc_ ? 4 : 0) | (lcd_enabled() ? mode_ : 0);
    }
    void write_stat(int value) noexcept { stat_enable_ = value & 0x78; check_stat(); }
    void write_lyc(int value) noexcept { lyc_ = byte(value); check_stat(); }

    void tick(int cycles) {
        if (!lcd_enabled()) return;
        int remaining = cycles;
        while (remaining > 0) {
            const int step = std::min(remaining, next_event_delay());
            line_dot_ += step; remaining -= step; process_line_position();
        }
    }

    [[nodiscard]] int lcdc() const noexcept { return lcdc_; }
    [[nodiscard]] int scy() const noexcept { return scy_; }
    [[nodiscard]] int scx() const noexcept { return scx_; }
    [[nodiscard]] int ly() const noexcept { return ly_; }
    [[nodiscard]] int lyc() const noexcept { return lyc_; }
    [[nodiscard]] int bgp() const noexcept { return bgp_; }
    [[nodiscard]] int obp0() const noexcept { return obp0_; }
    [[nodiscard]] int obp1() const noexcept { return obp1_; }
    [[nodiscard]] int wy() const noexcept { return wy_; }
    [[nodiscard]] int wx() const noexcept { return wx_; }
    void set_scy(int v) noexcept { scy_ = byte(v); } void set_scx(int v) noexcept { scx_ = byte(v); }
    void set_bgp(int v) noexcept { bgp_ = byte(v); } void set_obp0(int v) noexcept { obp0_ = byte(v); }
    void set_obp1(int v) noexcept { obp1_ = byte(v); } void set_wy(int v) noexcept { wy_ = byte(v); }
    void set_wx(int v) noexcept { wx_ = byte(v); }
    [[nodiscard]] bool take_hblank_entry() noexcept { const bool value = entered_hblank_; entered_hblank_ = false; return value; }

    void save(BinaryWriter& out) const {
        constexpr int field_count = 20;
        out.i32(field_count);
        const std::array fields{
            lcdc_, stat_enable_, scy_, scx_, ly_, lyc_, bgp_, obp0_, obp1_, wy_, wx_,
            mode_, line_dot_, window_line_, stat_line_ ? 1 : 0, vram_bank_, bcps_index_,
            bcps_auto_ ? 1 : 0, ocps_index_, ocps_auto_ ? 1 : 0,
        };
        for (const int field : fields) out.i32(field);
        out.raw(vram); out.raw(oam); out.raw(bg_cram); out.raw(obj_cram);
    }
    void load(BinaryReader& in) {
        if (in.i32() != 20) throw SaveStateError("État instantané corrompu (PPU)");
        lcdc_ = in.i32(); stat_enable_ = in.i32(); scy_ = in.i32(); scx_ = in.i32();
        ly_ = in.i32(); lyc_ = in.i32(); bgp_ = in.i32(); obp0_ = in.i32(); obp1_ = in.i32();
        wy_ = in.i32(); wx_ = in.i32(); mode_ = in.i32(); line_dot_ = in.i32();
        window_line_ = in.i32(); stat_line_ = in.i32() != 0; vram_bank_ = in.i32();
        bcps_index_ = in.i32(); bcps_auto_ = in.i32() != 0; ocps_index_ = in.i32();
        ocps_auto_ = in.i32() != 0;
        in.raw(vram); in.raw(oam); in.raw(bg_cram); in.raw(obj_cram); rebuild_color_cache();
    }

    std::array<std::uint8_t, 0x4000> vram{};
    std::array<std::uint8_t, 0xa0> oam{};
    std::array<std::uint8_t, 64> bg_cram{};
    std::array<std::uint8_t, 64> obj_cram{};
    std::array<std::int32_t, frame_pixels> completed_frame{};

private:
    [[nodiscard]] int next_event_delay() const noexcept {
        if (ly_ >= height) return 456 - line_dot_;
        if (line_dot_ < 80) return 80 - line_dot_;
        if (line_dot_ < 252) return 252 - line_dot_;
        return 456 - line_dot_;
    }
    void process_line_position() {
        if (line_dot_ >= 456) {
            line_dot_ -= 456; ++ly_;
            if (ly_ == height) enter_vblank();
            else if (ly_ > 153) { ly_ = 0; window_line_ = 0; set_mode(mode_oam); }
            else if (ly_ < height) set_mode(mode_oam);
            check_stat(); return;
        }
        if (ly_ < height) {
            const int expected = line_dot_ < 80 ? mode_oam : (line_dot_ < 252 ? mode_transfer : mode_hblank);
            if (expected != mode_) {
                set_mode(expected);
                if (expected == mode_transfer) render_line();
                else if (expected == mode_hblank) entered_hblank_ = true;
            }
        }
    }
    void enter_vblank() {
        set_mode(mode_vblank); interrupts_.request(Interrupt::vblank);
        completed_frame = working_frame_; frame_ready_ = true;
    }
    void set_mode(int mode) noexcept { mode_ = mode; check_stat(); }
    void check_stat() noexcept {
        if (!lcd_enabled()) { stat_line_ = false; return; }
        const bool line = ((stat_enable_ & 8) != 0 && mode_ == mode_hblank) ||
            ((stat_enable_ & 0x10) != 0 && mode_ == mode_vblank) ||
            ((stat_enable_ & 0x20) != 0 && mode_ == mode_oam) ||
            ((stat_enable_ & 0x40) != 0 && ly_ == lyc_);
        if (line && !stat_line_) interrupts_.request(Interrupt::stat);
        stat_line_ = line;
    }

    [[nodiscard]] int vram_byte(int bank, int address) const noexcept {
        return vram[static_cast<std::size_t>(bank * 0x2000 + (address & 0x1fff))];
    }
    [[nodiscard]] int tile_pixel_dmg(int tile, int row, int column) const noexcept {
        const int address = (lcdc_ & 0x10) != 0 ? tile * 16 : 0x1000 + static_cast<std::int8_t>(tile) * 16;
        const int lo = vram_byte(0, address + row * 2); const int hi = vram_byte(0, address + row * 2 + 1);
        const int bit_index = 7 - column;
        return (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
    }
    void render_line() {
        const int base = ly_ * width;
        if (cgb_mode_) { render_background_cgb(base); render_sprites_cgb(base); }
        else { render_background_dmg(base); render_sprites_dmg(base); }
    }
    void render_background_dmg(int base) {
        const bool enabled = (lcdc_ & 1) != 0;
        if (!enabled) {
            for (int x = 0; x < width; ++x) { bg_color_line_[static_cast<std::size_t>(x)] = 0; working_frame_[static_cast<std::size_t>(base + x)] = 0; }
        } else {
            const int map = (lcdc_ & 8) != 0 ? 0x1c00 : 0x1800;
            const int y = (ly_ + scy_) & 0xff;
            for (int x = 0; x < width; ++x) {
                const int bg_x = (x + scx_) & 0xff;
                const int tile = vram_byte(0, map + (y >> 3) * 32 + (bg_x >> 3));
                const int color = tile_pixel_dmg(tile, y & 7, bg_x & 7);
                bg_color_line_[static_cast<std::size_t>(x)] = color;
                working_frame_[static_cast<std::size_t>(base + x)] = (bgp_ >> (color * 2)) & 3;
            }
        }
        if (enabled && (lcdc_ & 0x20) != 0 && ly_ >= wy_ && wx_ <= 166) {
            const int start = std::max(0, wx_ - 7);
            if (start < width) {
                const int map = (lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800;
                for (int x = start; x < width; ++x) {
                    const int win_x = x - (wx_ - 7);
                    const int tile = vram_byte(0, map + (window_line_ >> 3) * 32 + (win_x >> 3));
                    const int color = tile_pixel_dmg(tile, window_line_ & 7, win_x & 7);
                    bg_color_line_[static_cast<std::size_t>(x)] = color;
                    working_frame_[static_cast<std::size_t>(base + x)] = (bgp_ >> (color * 2)) & 3;
                }
                ++window_line_;
            }
        }
    }
    int scan_sprites(int sprite_height) noexcept {
        int count{};
        for (int i = 0; i < 40 && count < 10; ++i) {
            const int y = oam[static_cast<std::size_t>(i * 4)] - 16;
            if (ly_ >= y && ly_ < y + sprite_height) sprite_indices_[static_cast<std::size_t>(count++)] = i;
        }
        return count;
    }
    template <typename Priority>
    void sort_sprites(int count, Priority priority) noexcept {
        for (int a = 1; a < count; ++a) {
            const int key = sprite_indices_[static_cast<std::size_t>(a)]; int b = a - 1;
            while (b >= 0 && !priority(sprite_indices_[static_cast<std::size_t>(b)], key)) {
                sprite_indices_[static_cast<std::size_t>(b + 1)] = sprite_indices_[static_cast<std::size_t>(b)]; --b;
            }
            sprite_indices_[static_cast<std::size_t>(b + 1)] = key;
        }
    }
    void render_sprites_dmg(int base) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8; const int count = scan_sprites(sprite_height);
        sort_sprites(count, [&](int a, int b) {
            const int ax = oam[static_cast<std::size_t>(a * 4 + 1)]; const int bx = oam[static_cast<std::size_t>(b * 4 + 1)];
            return ax != bx ? ax > bx : a > b;
        });
        for (int s = 0; s < count; ++s) {
            const int index = sprite_indices_[static_cast<std::size_t>(s)] * 4;
            const int sy = oam[static_cast<std::size_t>(index)] - 16; const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            int tile = oam[static_cast<std::size_t>(index + 2)]; const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy; if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int lo = vram_byte(0, tile * 16 + row * 2); const int hi = vram_byte(0, tile * 16 + row * 2 + 1);
            const int palette = (attr & 0x10) != 0 ? obp1_ : obp0_;
            for (int px = 0; px < 8; ++px) {
                const int x = sx + px; if (x < 0 || x >= width) continue;
                const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
                const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
                if (color == 0 || ((attr & 0x80) != 0 && bg_color_line_[static_cast<std::size_t>(x)] != 0)) continue;
                working_frame_[static_cast<std::size_t>(base + x)] = (palette >> (color * 2)) & 3;
            }
        }
    }
    void draw_bg_pixel_cgb(int base, int x, int map, int tile_row, int pixel_row, int tile_col, int pixel_col) {
        const int map_address = map + tile_row * 32 + tile_col;
        const int tile = vram_byte(0, map_address); const int attr = vram_byte(1, map_address);
        const int palette = attr & 7; const int bank = (attr >> 3) & 1;
        const int address = (lcdc_ & 0x10) != 0 ? tile * 16 : 0x1000 + static_cast<std::int8_t>(tile) * 16;
        const int row = (attr & 0x40) != 0 ? 7 - pixel_row : pixel_row;
        const int lo = vram_byte(bank, address + row * 2); const int hi = vram_byte(bank, address + row * 2 + 1);
        const int bit_index = (attr & 0x20) != 0 ? pixel_col : 7 - pixel_col;
        const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
        bg_color_line_[static_cast<std::size_t>(x)] = color; bg_priority_line_[static_cast<std::size_t>(x)] = (attr & 0x80) != 0;
        working_frame_[static_cast<std::size_t>(base + x)] = bg_argb_[static_cast<std::size_t>(palette * 4 + color)];
    }
    void render_background_cgb(int base) {
        const int map = (lcdc_ & 8) != 0 ? 0x1c00 : 0x1800; const int y = (ly_ + scy_) & 0xff;
        for (int x = 0; x < width; ++x) {
            const int bg_x = (x + scx_) & 0xff;
            draw_bg_pixel_cgb(base, x, map, y >> 3, y & 7, bg_x >> 3, bg_x & 7);
        }
        if ((lcdc_ & 0x20) != 0 && ly_ >= wy_ && wx_ <= 166) {
            const int start = std::max(0, wx_ - 7);
            if (start < width) {
                const int win_map = (lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800;
                for (int x = start; x < width; ++x) {
                    const int win_x = x - (wx_ - 7);
                    draw_bg_pixel_cgb(base, x, win_map, window_line_ >> 3, window_line_ & 7, win_x >> 3, win_x & 7);
                }
                ++window_line_;
            }
        }
    }
    void render_sprites_cgb(int base) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8; const int count = scan_sprites(sprite_height);
        sort_sprites(count, [](int a, int b) { return a > b; });
        const bool master_priority = (lcdc_ & 1) != 0;
        for (int s = 0; s < count; ++s) {
            const int index = sprite_indices_[static_cast<std::size_t>(s)] * 4;
            const int sy = oam[static_cast<std::size_t>(index)] - 16; const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            int tile = oam[static_cast<std::size_t>(index + 2)]; const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy; if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int bank = (attr >> 3) & 1; const int palette = attr & 7;
            const int lo = vram_byte(bank, tile * 16 + row * 2); const int hi = vram_byte(bank, tile * 16 + row * 2 + 1);
            for (int px = 0; px < 8; ++px) {
                const int x = sx + px; if (x < 0 || x >= width) continue;
                const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
                const int color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
                if (color == 0) continue;
                const bool bg_visible = bg_color_line_[static_cast<std::size_t>(x)] != 0;
                if (bg_visible && master_priority && (bg_priority_line_[static_cast<std::size_t>(x)] || (attr & 0x80) != 0)) continue;
                working_frame_[static_cast<std::size_t>(base + x)] = obj_argb_[static_cast<std::size_t>(palette * 4 + color)];
            }
        }
    }
    static std::int32_t bgr555_to_argb(int value) noexcept {
        const int r5 = value & 0x1f; const int g5 = (value >> 5) & 0x1f; const int b5 = (value >> 10) & 0x1f;
        const auto argb = 0xff000000U | static_cast<std::uint32_t>(((r5 << 3) | (r5 >> 2)) << 16) |
            static_cast<std::uint32_t>(((g5 << 3) | (g5 >> 2)) << 8) | static_cast<std::uint32_t>((b5 << 3) | (b5 >> 2));
        return static_cast<std::int32_t>(argb);
    }
    static void recompute_argb(const std::array<std::uint8_t, 64>& cram,
                               std::array<std::int32_t, 32>& colors, int byte_index) noexcept {
        const int color = byte_index / 2;
        colors[static_cast<std::size_t>(color)] = bgr555_to_argb(
            cram[static_cast<std::size_t>(color * 2)] | (cram[static_cast<std::size_t>(color * 2 + 1)] << 8)
        );
    }
    void rebuild_color_cache() noexcept {
        for (int i = 0; i < 32; ++i) { recompute_argb(bg_cram, bg_argb_, i * 2); recompute_argb(obj_cram, obj_argb_, i * 2); }
    }

    InterruptController& interrupts_;
    bool cgb_mode_{};
    int lcdc_{0x91};
    int stat_enable_{};
    int scy_{};
    int scx_{};
    int ly_{};
    int lyc_{};
    int bgp_{0xfc};
    int obp0_{};
    int obp1_{};
    int wy_{};
    int wx_{};
    int vram_bank_{};
    int bcps_index_{};
    bool bcps_auto_{};
    int ocps_index_{};
    bool ocps_auto_{};
    std::array<std::int32_t, 32> bg_argb_{};
    std::array<std::int32_t, 32> obj_argb_{};
    int mode_{mode_oam};
    int line_dot_{};
    int window_line_{};
    bool stat_line_{};
    bool entered_hblank_{};
    bool frame_ready_{};
    std::array<std::int32_t, frame_pixels> working_frame_{};
    std::array<int, width> bg_color_line_{};
    std::array<bool, width> bg_priority_line_{};
    std::array<int, 10> sprite_indices_{};
};

class Bus {
public:
    virtual ~Bus() = default;
    [[nodiscard]] virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;
    virtual void on_stop() {}
    [[nodiscard]] int read_word(int address) {
        return read(address) | (read(word(address + 1)) << 8);
    }
    void write_word(int address, int value) {
        write(address, value); write(word(address + 1), value >> 8);
    }
};

class MemoryBus final : public Bus {
public:
    MemoryBus(Cartridge& cartridge, Ppu& ppu, InterruptController& interrupts, Timer& timer,
              SerialPort& serial, Joypad& joypad, Apu& apu, bool cgb_mode, SpeedController& speed)
        : cartridge_(cartridge), ppu_(ppu), interrupts_(interrupts), timer_(timer), serial_(serial),
          joypad_(joypad), apu_(apu), cgb_mode_(cgb_mode), speed_(speed) {}

    [[nodiscard]] int read(int address) override {
        const int addr = word(address);
        if (dma_active() && !dma_accessible(addr)) return 0xff;
        return read_internal(addr);
    }
    void write(int address, int value) override {
        const int addr = word(address);
        if (dma_active() && !dma_accessible(addr)) return;
        write_internal(addr, byte(value));
    }
    void on_stop() override { static_cast<void>(speed_.on_stop()); }
    void tick(int cycles) noexcept { if (dma_cycles_ > 0) dma_cycles_ = std::max(0, dma_cycles_ - cycles); }
    [[nodiscard]] bool dma_active() const noexcept { return dma_cycles_ > 0; }
    void notify_hblank() {
        if (!hdma_active_ || !hdma_hblank_) return;
        transfer_hdma_block(); if (hdma_length_ == 0) hdma_active_ = false;
    }
    void save(BinaryWriter& out) const {
        out.raw(wram); out.raw(hram); out.i32(8);
        out.i32(dma_register_); out.i32(dma_cycles_); out.i32(svbk_); out.i32(hdma_source_);
        out.i32(hdma_destination_); out.i32(hdma_length_); out.i32(hdma_hblank_ ? 1 : 0);
        out.i32(hdma_active_ ? 1 : 0);
    }
    void load(BinaryReader& in) {
        in.raw(wram); in.raw(hram);
        if (in.i32() != 8) throw SaveStateError("État instantané corrompu (DMA)");
        dma_register_ = in.i32(); dma_cycles_ = in.i32(); svbk_ = in.i32(); hdma_source_ = in.i32();
        hdma_destination_ = in.i32(); hdma_length_ = in.i32(); hdma_hblank_ = in.i32() != 0;
        hdma_active_ = in.i32() != 0;
    }

    std::array<std::uint8_t, 0x8000> wram{};
    std::array<std::uint8_t, 0x7f> hram{};

private:
    [[nodiscard]] int wram_bank() const noexcept {
        if (!cgb_mode_) return 1;
        const int value = svbk_ & 7; return value == 0 ? 1 : value;
    }
    static bool dma_accessible(int address) noexcept {
        return (address >= 0xff80 && address <= 0xfffe) || address == 0xff46;
    }
    [[nodiscard]] int read_internal(int address) {
        if (address <= 0x7fff) return cartridge_.read_rom(address);
        if (address <= 0x9fff) return ppu_.read_vram(address);
        if (address <= 0xbfff) return cartridge_.read_ram(address);
        if (address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        if (address <= 0xdfff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
        if (address <= 0xefff) return wram[static_cast<std::size_t>(address - 0xe000)];
        if (address <= 0xfdff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xf000)];
        if (address <= 0xfe9f) return ppu_.read_oam(address);
        if (address <= 0xfeff) return 0;
        switch (address) {
        case 0xff00: return joypad_.read(); case 0xff01: return serial_.read_data();
        case 0xff02: return serial_.read_control(); case 0xff04: return timer_.read_div();
        case 0xff05: return timer_.read_tima(); case 0xff06: return timer_.tma();
        case 0xff07: return timer_.read_tac(); case 0xff0f: return interrupts_.read_flags();
        case 0xff40: return ppu_.lcdc(); case 0xff41: return ppu_.read_stat();
        case 0xff42: return ppu_.scy(); case 0xff43: return ppu_.scx(); case 0xff44: return ppu_.ly();
        case 0xff45: return ppu_.lyc(); case 0xff46: return dma_register_; case 0xff47: return ppu_.bgp();
        case 0xff48: return ppu_.obp0(); case 0xff49: return ppu_.obp1(); case 0xff4a: return ppu_.wy();
        case 0xff4b: return ppu_.wx(); case 0xff4d: return cgb_mode_ ? speed_.read_key1() : 0xff;
        case 0xff4f: return ppu_.read_vram_bank();
        case 0xff51: return cgb_mode_ ? (hdma_source_ >> 8) & 0xff : 0xff;
        case 0xff52: return cgb_mode_ ? hdma_source_ & 0xff : 0xff;
        case 0xff53: return cgb_mode_ ? (hdma_destination_ >> 8) & 0xff : 0xff;
        case 0xff54: return cgb_mode_ ? hdma_destination_ & 0xff : 0xff;
        case 0xff55: return read_hdma_status(); case 0xff68: return cgb_mode_ ? ppu_.read_bcps() : 0xff;
        case 0xff69: return cgb_mode_ ? ppu_.read_bcpd() : 0xff; case 0xff6a: return cgb_mode_ ? ppu_.read_ocps() : 0xff;
        case 0xff6b: return cgb_mode_ ? ppu_.read_ocpd() : 0xff; case 0xff70: return cgb_mode_ ? svbk_ | 0xf8 : 0xff;
        case 0xffff: return interrupts_.read_enable(); default: break;
        }
        if (address >= 0xff10 && address <= 0xff3f) return apu_.read(address);
        if (address >= 0xff80 && address <= 0xfffe) return hram[static_cast<std::size_t>(address - 0xff80)];
        return 0xff;
    }
    void write_internal(int address, int value) {
        if (address <= 0x7fff) { cartridge_.write_control(address, value); return; }
        if (address <= 0x9fff) { ppu_.write_vram(address, value); return; }
        if (address <= 0xbfff) { cartridge_.write_ram(address, value); return; }
        if (address <= 0xcfff) { wram[static_cast<std::size_t>(address - 0xc000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xdfff) { wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xefff) { wram[static_cast<std::size_t>(address - 0xe000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xfdff) { wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xf000)] = static_cast<std::uint8_t>(value); return; }
        if (address <= 0xfe9f) { ppu_.write_oam(address, value); return; }
        if (address <= 0xfeff) return;
        switch (address) {
        case 0xff00: joypad_.write(value); break; case 0xff01: serial_.write_data(value); break;
        case 0xff02: serial_.write_control(value); break; case 0xff04: timer_.write_div(); break;
        case 0xff05: timer_.write_tima(value); break; case 0xff06: timer_.set_tma(value); break;
        case 0xff07: timer_.write_tac(value); break; case 0xff0f: interrupts_.write_flags(value); break;
        case 0xff40: ppu_.write_lcdc(value); break; case 0xff41: ppu_.write_stat(value); break;
        case 0xff42: ppu_.set_scy(value); break; case 0xff43: ppu_.set_scx(value); break; case 0xff44: break;
        case 0xff45: ppu_.write_lyc(value); break; case 0xff46: start_dma(value); break;
        case 0xff47: ppu_.set_bgp(value); break; case 0xff48: ppu_.set_obp0(value); break;
        case 0xff49: ppu_.set_obp1(value); break; case 0xff4a: ppu_.set_wy(value); break;
        case 0xff4b: ppu_.set_wx(value); break; case 0xff4d: if (cgb_mode_) speed_.write_key1(value); break;
        case 0xff4f: ppu_.write_vram_bank(value); break;
        case 0xff51: if (cgb_mode_) hdma_source_ = (hdma_source_ & 0x00ff) | (value << 8); break;
        case 0xff52: if (cgb_mode_) hdma_source_ = (hdma_source_ & 0xff00) | (value & 0xf0); break;
        case 0xff53: if (cgb_mode_) hdma_destination_ = (hdma_destination_ & 0x00ff) | ((value & 0x1f) << 8); break;
        case 0xff54: if (cgb_mode_) hdma_destination_ = (hdma_destination_ & 0xff00) | (value & 0xf0); break;
        case 0xff55: if (cgb_mode_) start_hdma(value); break; case 0xff68: if (cgb_mode_) ppu_.write_bcps(value); break;
        case 0xff69: if (cgb_mode_) ppu_.write_bcpd(value); break; case 0xff6a: if (cgb_mode_) ppu_.write_ocps(value); break;
        case 0xff6b: if (cgb_mode_) ppu_.write_ocpd(value); break; case 0xff70: if (cgb_mode_) svbk_ = value & 7; break;
        case 0xffff: interrupts_.write_enable(value); break; default:
            if (address >= 0xff10 && address <= 0xff3f) apu_.write(address, value);
            else if (address >= 0xff80 && address <= 0xfffe) hram[static_cast<std::size_t>(address - 0xff80)] = static_cast<std::uint8_t>(value);
            break;
        }
    }
    void start_dma(int high) {
        dma_register_ = high; const int base = high << 8;
        for (int i = 0; i < 0xa0; ++i) ppu_.write_oam_direct(i, read_for_dma(word(base + i)));
        dma_cycles_ = 640;
    }
    [[nodiscard]] int read_for_dma(int address) const {
        if (address <= 0x7fff) return cartridge_.read_rom(address);
        if (address <= 0x9fff) return ppu_.vram[static_cast<std::size_t>(ppu_.vram_bank() * 0x2000 + (address & 0x1fff))];
        if (address <= 0xbfff) return cartridge_.read_ram(address);
        if (address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        if (address <= 0xdfff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
        if (address <= 0xefff) return wram[static_cast<std::size_t>(address - 0xe000)];
        if (address <= 0xfdff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xf000)];
        return 0xff;
    }
    [[nodiscard]] int read_hdma_status() const noexcept {
        if (!cgb_mode_) return 0xff;
        return hdma_active_ ? ((hdma_length_ / 16) - 1) & 0x7f : 0xff;
    }
    void start_hdma(int value) {
        const bool hblank = (value & 0x80) != 0;
        if (hdma_active_ && !hblank) { hdma_active_ = false; return; }
        hdma_length_ = ((value & 0x7f) + 1) * 16; hdma_hblank_ = hblank; hdma_active_ = true;
        if (!hblank) { while (hdma_length_ > 0) transfer_hdma_block(); hdma_active_ = false; }
    }
    void transfer_hdma_block() {
        for (int i = 0; i < 16; ++i) {
            const int value = read_for_hdma(word(hdma_source_));
            ppu_.vram[static_cast<std::size_t>(ppu_.vram_bank() * 0x2000 + (hdma_destination_ & 0x1fff))] = static_cast<std::uint8_t>(value);
            hdma_source_ = word(hdma_source_ + 1); hdma_destination_ = word(hdma_destination_ + 1);
        }
        hdma_length_ -= 16;
    }
    [[nodiscard]] int read_for_hdma(int address) const {
        if (address <= 0x7fff) return cartridge_.read_rom(address);
        if (address >= 0xa000 && address <= 0xbfff) return cartridge_.read_ram(address);
        if (address >= 0xc000 && address <= 0xcfff) return wram[static_cast<std::size_t>(address - 0xc000)];
        if (address >= 0xd000 && address <= 0xdfff) return wram[static_cast<std::size_t>(wram_bank() * 0x1000 + address - 0xd000)];
        return 0xff;
    }

    Cartridge& cartridge_;
    Ppu& ppu_;
    InterruptController& interrupts_;
    Timer& timer_;
    SerialPort& serial_;
    Joypad& joypad_;
    Apu& apu_;
    bool cgb_mode_{};
    SpeedController& speed_;
    int svbk_{1};
    int dma_register_{0xff};
    int dma_cycles_{};
    int hdma_source_{};
    int hdma_destination_{};
    int hdma_length_{};
    bool hdma_hblank_{};
    bool hdma_active_{};
};

class Cpu {
public:
    Cpu(Bus& bus, InterruptController& interrupts) : bus_(bus), interrupts_(interrupts) {}

    int step() {
        if (locked) return 4;
        const bool enable_ime = ei_pending; ei_pending = false;
        if (halted) {
            if ((interrupts_.enable & interrupts_.flags & 0x1f) != 0) halted = false;
            else return 4;
        }
        if (ime) {
            if (const auto interrupt = interrupts_.highest_pending()) return service_interrupt(*interrupt);
        }
        const int opcode = fetch_opcode(); const int cycles = execute(opcode);
        if (enable_ime && opcode != 0xf3) ime = true;
        return cycles;
    }

    [[nodiscard]] int af() const noexcept { return (a << 8) | f; }
    [[nodiscard]] int bc() const noexcept { return (b << 8) | c; }
    [[nodiscard]] int de() const noexcept { return (d << 8) | e; }
    [[nodiscard]] int hl() const noexcept { return (h << 8) | l; }
    void set_af(int value) noexcept { a = byte(value >> 8); f = value & 0xf0; }
    void set_bc(int value) noexcept { b = byte(value >> 8); c = byte(value); }
    void set_de(int value) noexcept { d = byte(value >> 8); e = byte(value); }
    void set_hl(int value) noexcept { h = byte(value >> 8); l = byte(value); }

    void save(BinaryWriter& out) const {
        out.i32(af()); out.i32(bc()); out.i32(de()); out.i32(hl()); out.i32(sp); out.i32(pc);
        out.boolean(ime); out.boolean(halted); out.boolean(ei_pending); out.boolean(halt_bug); out.boolean(locked);
    }
    void load(BinaryReader& in) {
        set_af(in.i32()); set_bc(in.i32()); set_de(in.i32()); set_hl(in.i32()); sp = in.i32(); pc = in.i32();
        ime = in.boolean(); halted = in.boolean(); ei_pending = in.boolean(); halt_bug = in.boolean(); locked = in.boolean();
    }

    int a{0x01}; int f{0xb0}; int b{}; int c{0x13}; int d{}; int e{0xd8}; int h{0x01}; int l{0x4d};
    int sp{0xfffe}; int pc{0x0100};
    bool ime{}; bool halted{}; bool locked{}; bool ei_pending{}; bool halt_bug{};

private:
    static constexpr int flag_z = 0x80;
    static constexpr int flag_n = 0x40;
    static constexpr int flag_h = 0x20;
    static constexpr int flag_c = 0x10;
    [[nodiscard]] bool z() const noexcept { return (f & flag_z) != 0; }
    [[nodiscard]] bool n() const noexcept { return (f & flag_n) != 0; }
    [[nodiscard]] bool half() const noexcept { return (f & flag_h) != 0; }
    [[nodiscard]] bool carry() const noexcept { return (f & flag_c) != 0; }
    void flags(bool zero, bool subtract, bool half_carry, bool carry_value) noexcept {
        f = (zero ? flag_z : 0) | (subtract ? flag_n : 0) | (half_carry ? flag_h : 0) | (carry_value ? flag_c : 0);
    }
    int service_interrupt(Interrupt interrupt) {
        ime = false; ei_pending = false; interrupts_.acknowledge(interrupt); push(pc); pc = interrupt_vector(interrupt); return 20;
    }
    int fetch_opcode() {
        const int value = bus_.read(pc); if (halt_bug) halt_bug = false; else pc = word(pc + 1); return value;
    }
    int fetch_byte() { const int value = bus_.read(pc); pc = word(pc + 1); return value; }
    int fetch_word() { const int low = fetch_byte(); return low | (fetch_byte() << 8); }
    void push(int value) { sp = word(sp - 1); bus_.write(sp, value >> 8); sp = word(sp - 1); bus_.write(sp, value); }
    int pop() { const int low = bus_.read(sp); sp = word(sp + 1); const int high = bus_.read(sp); sp = word(sp + 1); return (high << 8) | low; }
    int read_r8(int index) {
        switch (index) { case 0: return b; case 1: return c; case 2: return d; case 3: return e;
        case 4: return h; case 5: return l; case 6: return bus_.read(hl()); default: return a; }
    }
    void write_r8(int index, int value) {
        value = byte(value);
        switch (index) { case 0: b = value; break; case 1: c = value; break; case 2: d = value; break;
        case 3: e = value; break; case 4: h = value; break; case 5: l = value; break;
        case 6: bus_.write(hl(), value); break; default: a = value; break; }
    }
    void add8(int value, int carry_in) noexcept {
        const int result = a + value + carry_in;
        flags(byte(result) == 0, false, (a & 0x0f) + (value & 0x0f) + carry_in > 0x0f, result > 0xff);
        a = byte(result);
    }
    void sub8(int value, int carry_in, bool store) noexcept {
        const int result = a - value - carry_in;
        flags(byte(result) == 0, true, (a & 0x0f) - (value & 0x0f) - carry_in < 0, result < 0);
        if (store) a = byte(result);
    }
    void and8(int value) noexcept { a &= value; flags(a == 0, false, true, false); }
    void xor8(int value) noexcept { a = byte(a ^ value); flags(a == 0, false, false, false); }
    void or8(int value) noexcept { a = byte(a | value); flags(a == 0, false, false, false); }
    int inc8(int value) noexcept {
        const int result = byte(value + 1); f = (f & flag_c) | (result == 0 ? flag_z : 0) | ((value & 0x0f) == 0x0f ? flag_h : 0); return result;
    }
    int dec8(int value) noexcept {
        const int result = byte(value - 1); f = (f & flag_c) | flag_n | (result == 0 ? flag_z : 0) | ((value & 0x0f) == 0 ? flag_h : 0); return result;
    }
    void add_hl(int value) noexcept {
        const int current = hl(); const int result = current + value;
        f = (f & flag_z) | ((current & 0x0fff) + (value & 0x0fff) > 0x0fff ? flag_h : 0) | (result > 0xffff ? flag_c : 0);
        set_hl(word(result));
    }
    int sp_plus_immediate() {
        const int offset = static_cast<std::int8_t>(fetch_byte());
        flags(false, false, (sp & 0x0f) + (offset & 0x0f) > 0x0f, (sp & 0xff) + (offset & 0xff) > 0xff);
        return word(sp + offset);
    }
    void daa() noexcept {
        int result = a;
        if (!n()) {
            if (carry() || result > 0x99) { result += 0x60; f |= flag_c; }
            if (half() || (result & 0x0f) > 9) result += 6;
        } else { if (carry()) result -= 0x60; if (half()) result -= 6; }
        result = byte(result); f = (f & (flag_n | flag_c)) | (result == 0 ? flag_z : 0); a = result;
    }
    int rlc(int value) noexcept { const int c = (value >> 7) & 1; const int result = byte((value << 1) | c); flags(result == 0, false, false, c != 0); return result; }
    int rrc(int value) noexcept { const int c = value & 1; const int result = byte((value >> 1) | (c << 7)); flags(result == 0, false, false, c != 0); return result; }
    int rl(int value) noexcept { const int in = carry() ? 1 : 0; const int c = (value >> 7) & 1; const int result = byte((value << 1) | in); flags(result == 0, false, false, c != 0); return result; }
    int rr(int value) noexcept { const int in = carry() ? 0x80 : 0; const int c = value & 1; const int result = (value >> 1) | in; flags(result == 0, false, false, c != 0); return result; }
    int sla(int value) noexcept { const int c = (value >> 7) & 1; const int result = byte(value << 1); flags(result == 0, false, false, c != 0); return result; }
    int sra(int value) noexcept { const int c = value & 1; const int result = (value >> 1) | (value & 0x80); flags(result == 0, false, false, c != 0); return result; }
    int swap(int value) noexcept { const int result = byte((value << 4) | (value >> 4)); flags(result == 0, false, false, false); return result; }
    int srl(int value) noexcept { const int c = value & 1; const int result = value >> 1; flags(result == 0, false, false, c != 0); return result; }
    int jump_relative(bool condition) { const int offset = static_cast<std::int8_t>(fetch_byte()); if (!condition) return 8; pc = word(pc + offset); return 12; }
    int jump_absolute(bool condition) { const int target = fetch_word(); if (!condition) return 12; pc = target; return 16; }
    int call(bool condition) { const int target = fetch_word(); if (!condition) return 12; push(pc); pc = target; return 24; }
    int return_if(bool condition) { if (!condition) return 8; pc = pop(); return 20; }
    int rst(int vector) { push(pc); pc = vector; return 16; }

    int execute(int opcode) {
        if (opcode == 0x76) {
            if (!ime && (interrupts_.enable & interrupts_.flags & 0x1f) != 0) halt_bug = true; else halted = true;
            return 4;
        }
        if (opcode >= 0x40 && opcode <= 0x7f) {
            const int source = opcode & 7; const int destination = (opcode >> 3) & 7;
            write_r8(destination, read_r8(source)); return source == 6 || destination == 6 ? 8 : 4;
        }
        if (opcode >= 0x80 && opcode <= 0xbf) {
            const int source = opcode & 7; const int value = read_r8(source); const int carry_in = carry() ? 1 : 0;
            switch ((opcode >> 3) & 7) { case 0: add8(value, 0); break; case 1: add8(value, carry_in); break;
            case 2: sub8(value, 0, true); break; case 3: sub8(value, carry_in, true); break;
            case 4: and8(value); break; case 5: xor8(value); break; case 6: or8(value); break; default: sub8(value, 0, false); break; }
            return source == 6 ? 8 : 4;
        }
        switch (opcode) {
        case 0x00: return 4; case 0x01: set_bc(fetch_word()); return 12; case 0x02: bus_.write(bc(), a); return 8;
        case 0x03: set_bc(word(bc() + 1)); return 8; case 0x04: b = inc8(b); return 4; case 0x05: b = dec8(b); return 4;
        case 0x06: b = fetch_byte(); return 8; case 0x07: a = rlc(a); f &= ~flag_z; return 4;
        case 0x08: bus_.write_word(fetch_word(), sp); return 20; case 0x09: add_hl(bc()); return 8;
        case 0x0a: a = bus_.read(bc()); return 8; case 0x0b: set_bc(word(bc() - 1)); return 8;
        case 0x0c: c = inc8(c); return 4; case 0x0d: c = dec8(c); return 4; case 0x0e: c = fetch_byte(); return 8;
        case 0x0f: a = rrc(a); f &= ~flag_z; return 4;
        case 0x10: static_cast<void>(fetch_byte()); bus_.on_stop(); return 4; case 0x11: set_de(fetch_word()); return 12;
        case 0x12: bus_.write(de(), a); return 8; case 0x13: set_de(word(de() + 1)); return 8;
        case 0x14: d = inc8(d); return 4; case 0x15: d = dec8(d); return 4; case 0x16: d = fetch_byte(); return 8;
        case 0x17: a = rl(a); f &= ~flag_z; return 4; case 0x18: return jump_relative(true); case 0x19: add_hl(de()); return 8;
        case 0x1a: a = bus_.read(de()); return 8; case 0x1b: set_de(word(de() - 1)); return 8;
        case 0x1c: e = inc8(e); return 4; case 0x1d: e = dec8(e); return 4; case 0x1e: e = fetch_byte(); return 8;
        case 0x1f: a = rr(a); f &= ~flag_z; return 4;
        case 0x20: return jump_relative(!z()); case 0x21: set_hl(fetch_word()); return 12;
        case 0x22: bus_.write(hl(), a); set_hl(word(hl() + 1)); return 8; case 0x23: set_hl(word(hl() + 1)); return 8;
        case 0x24: h = inc8(h); return 4; case 0x25: h = dec8(h); return 4; case 0x26: h = fetch_byte(); return 8;
        case 0x27: daa(); return 4; case 0x28: return jump_relative(z()); case 0x29: add_hl(hl()); return 8;
        case 0x2a: a = bus_.read(hl()); set_hl(word(hl() + 1)); return 8; case 0x2b: set_hl(word(hl() - 1)); return 8;
        case 0x2c: l = inc8(l); return 4; case 0x2d: l = dec8(l); return 4; case 0x2e: l = fetch_byte(); return 8;
        case 0x2f: a = byte(~a); f |= flag_n | flag_h; return 4;
        case 0x30: return jump_relative(!carry()); case 0x31: sp = fetch_word(); return 12;
        case 0x32: bus_.write(hl(), a); set_hl(word(hl() - 1)); return 8; case 0x33: sp = word(sp + 1); return 8;
        case 0x34: bus_.write(hl(), inc8(bus_.read(hl()))); return 12; case 0x35: bus_.write(hl(), dec8(bus_.read(hl()))); return 12;
        case 0x36: bus_.write(hl(), fetch_byte()); return 12; case 0x37: f = (f & flag_z) | flag_c; return 4;
        case 0x38: return jump_relative(carry()); case 0x39: add_hl(sp); return 8;
        case 0x3a: a = bus_.read(hl()); set_hl(word(hl() - 1)); return 8; case 0x3b: sp = word(sp - 1); return 8;
        case 0x3c: a = inc8(a); return 4; case 0x3d: a = dec8(a); return 4; case 0x3e: a = fetch_byte(); return 8;
        case 0x3f: f = (f & flag_z) | ((f & flag_c) ^ flag_c); return 4;
        case 0xc0: return return_if(!z()); case 0xc1: set_bc(pop()); return 12; case 0xc2: return jump_absolute(!z());
        case 0xc3: return jump_absolute(true); case 0xc4: return call(!z()); case 0xc5: push(bc()); return 16;
        case 0xc6: add8(fetch_byte(), 0); return 8; case 0xc7: return rst(0x00); case 0xc8: return return_if(z());
        case 0xc9: pc = pop(); return 16; case 0xca: return jump_absolute(z()); case 0xcb: return execute_cb();
        case 0xcc: return call(z()); case 0xcd: return call(true); case 0xce: add8(fetch_byte(), carry() ? 1 : 0); return 8;
        case 0xcf: return rst(0x08); case 0xd0: return return_if(!carry()); case 0xd1: set_de(pop()); return 12;
        case 0xd2: return jump_absolute(!carry()); case 0xd4: return call(!carry()); case 0xd5: push(de()); return 16;
        case 0xd6: sub8(fetch_byte(), 0, true); return 8; case 0xd7: return rst(0x10); case 0xd8: return return_if(carry());
        case 0xd9: pc = pop(); ime = true; return 16; case 0xda: return jump_absolute(carry()); case 0xdc: return call(carry());
        case 0xde: sub8(fetch_byte(), carry() ? 1 : 0, true); return 8; case 0xdf: return rst(0x18);
        case 0xe0: bus_.write(0xff00 + fetch_byte(), a); return 12; case 0xe1: set_hl(pop()); return 12;
        case 0xe2: bus_.write(0xff00 + c, a); return 8; case 0xe5: push(hl()); return 16; case 0xe6: and8(fetch_byte()); return 8;
        case 0xe7: return rst(0x20); case 0xe8: sp = sp_plus_immediate(); return 16; case 0xe9: pc = hl(); return 4;
        case 0xea: bus_.write(fetch_word(), a); return 16; case 0xee: xor8(fetch_byte()); return 8; case 0xef: return rst(0x28);
        case 0xf0: a = bus_.read(0xff00 + fetch_byte()); return 12; case 0xf1: set_af(pop()); return 12;
        case 0xf2: a = bus_.read(0xff00 + c); return 8; case 0xf3: ime = false; ei_pending = false; return 4;
        case 0xf5: push(af()); return 16; case 0xf6: or8(fetch_byte()); return 8; case 0xf7: return rst(0x30);
        case 0xf8: set_hl(sp_plus_immediate()); return 12; case 0xf9: sp = hl(); return 8;
        case 0xfa: a = bus_.read(fetch_word()); return 16; case 0xfb: ei_pending = true; return 4;
        case 0xfe: sub8(fetch_byte(), 0, false); return 8; case 0xff: return rst(0x38);
        default: locked = true; return 4;
        }
    }
    int execute_cb() {
        const int opcode = fetch_byte(); const int reg = opcode & 7; const int selector = (opcode >> 3) & 7;
        const bool memory = reg == 6; const int value = read_r8(reg);
        switch (opcode >> 6) {
        case 0: {
            int result{}; switch (selector) { case 0: result = rlc(value); break; case 1: result = rrc(value); break;
            case 2: result = rl(value); break; case 3: result = rr(value); break; case 4: result = sla(value); break;
            case 5: result = sra(value); break; case 6: result = swap(value); break; default: result = srl(value); break; }
            write_r8(reg, result); return memory ? 16 : 8;
        }
        case 1: f = (f & flag_c) | flag_h | (((value >> selector) & 1) == 0 ? flag_z : 0); return memory ? 12 : 8;
        case 2: write_r8(reg, value & ~(1 << selector)); return memory ? 16 : 8;
        default: write_r8(reg, value | (1 << selector)); return memory ? 16 : 8;
        }
    }

    Bus& bus_;
    InterruptController& interrupts_;
};

class Machine {
public:
    Machine(RomImage rom, Cartridge::Clock clock)
        : cartridge(Cartridge::create(rom, std::move(clock))), cgb_mode(cartridge->header().uses_color),
          timer(interrupts), serial(interrupts), joypad(interrupts), speed(cgb_mode),
          ppu(interrupts, cgb_mode), bus(*cartridge, ppu, interrupts, timer, serial, joypad, apu, cgb_mode, speed),
          cpu(bus, interrupts) {
        if (cgb_mode) cpu.a = 0x11;
    }

    void tick(int cpu_cycles) {
        timer.tick(cpu_cycles); serial.tick(cpu_cycles);
        const int peripheral_cycles = cpu_cycles >> speed.peripheral_shift();
        ppu.tick(peripheral_cycles);
        if (ppu.take_hblank_entry()) bus.notify_hblank();
        apu.tick(peripheral_cycles); bus.tick(cpu_cycles); cartridge->tick(cpu_cycles);
    }

    std::unique_ptr<Cartridge> cartridge;
    bool cgb_mode{};
    InterruptController interrupts;
    Timer timer;
    SerialPort serial;
    Joypad joypad;
    SpeedController speed;
    Ppu ppu;
    Apu apu;
    MemoryBus bus;
    Cpu cpu;
};

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
            const int consumed = machine_->cpu.step(); machine_->tick(consumed);
            ppu_cycles += consumed >> machine_->speed.peripheral_shift();
        }
        std::copy(machine_->ppu.completed_frame.begin(), machine_->ppu.completed_frame.end(), framebuffer.begin());
    }
    void set_button(Button button, bool pressed) override { if (machine_) machine_->joypad.set_button(button, pressed); }
    std::size_t read_audio(std::span<std::int16_t> destination) override {
        return machine_ ? machine_->apu.read_samples(destination) : 0;
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
        out.u32(0x52564e53U); out.u16(4); out.u8(static_cast<std::uint8_t>(Console::game_boy)); out.raw(rom_hash_);
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
        const auto version = in.u16(); if (version != 4) throw SaveStateError("Version d'état non prise en charge");
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

} // namespace

std::unique_ptr<Core> make_game_boy_core() {
    return std::make_unique<GameBoyCore>();
}

} // namespace ravenemu
