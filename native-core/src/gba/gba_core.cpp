#include "ravenemu/core.hpp"

#include "binary_io.hpp"
#include "sha256.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <deque>
#include <functional>
#include <limits>
#include <memory>
#include <numbers>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace ravenemu {
namespace {

using detail::BinaryReader;
using detail::BinaryWriter;

constexpr std::uint32_t u32(std::int32_t value) noexcept {
    return static_cast<std::uint32_t>(value);
}

constexpr std::int32_t i32(std::uint32_t value) noexcept {
    return static_cast<std::int32_t>(value);
}

constexpr std::int32_t add32(std::int32_t left, std::int32_t right) noexcept {
    return i32(u32(left) + u32(right));
}

constexpr std::int32_t sign_extend(std::uint32_t value, unsigned bits) noexcept {
    const auto shift = 32U - bits;
    return i32(value << shift) >> shift;
}

using RomImage = std::shared_ptr<const std::vector<std::uint8_t>>;

class Diagnostics {
public:
    void begin_frame() noexcept {
        instructions_last_frame = instructions_this_frame;
        instructions_this_frame = 0;
        ppu_nanos_last_frame = std::exchange(ppu_nanos, 0);
        dma_nanos_last_frame = std::exchange(dma_nanos, 0);
        apu_nanos_last_frame = std::exchange(apu_nanos, 0);
    }

    void instruction() noexcept { ++instructions_this_frame; }
    void swi(int number) noexcept {
        last_swi = number;
        if (number >= 0 && number < static_cast<int>(swi_counts.size())) {
            ++swi_counts[static_cast<std::size_t>(number)];
        }
    }
    void interrupt(int mask) noexcept { last_interrupt_mask = mask; }
    void audio_underrun() noexcept { ++audio_underruns; }
    void bg2_matrix_write() noexcept { ++bg2_matrix_writes; }
    void bg2_reference_write() noexcept { ++bg2_reference_writes; }
    void wait_step(int cycles, int mask) {
        const auto before = wait_cycles;
        wait_cycles += cycles;
        if (before < stuck_wait_cycles && wait_cycles >= stuck_wait_cycles) {
            report(DiagnosticEvent::missing_interrupt,
                   "attente prolongée de l'interruption " + std::to_string(mask));
        }
    }
    void wait_resolved() noexcept { wait_cycles = 0; }
    void unsupported_access(std::int32_t address) {
        if (counts[index(DiagnosticEvent::unsupported_access)] == 0) {
            first_unsupported_address = address;
        }
        report(DiagnosticEvent::unsupported_access,
               "accès mémoire GBA hors plan à " + std::to_string(u32(address)));
    }
    void report(DiagnosticEvent event, std::string detail) {
        const auto slot = index(event);
        ++counts[slot];
        if (reported[slot] >= max_reports_per_event) return;
        ++reported[slot];
        messages.push_back({event, std::move(detail)});
    }
    std::vector<DiagnosticMessage> drain() {
        auto result = std::move(messages);
        messages.clear();
        return result;
    }
    [[nodiscard]] int count(DiagnosticEvent event) const noexcept {
        return counts[index(event)];
    }

    bool measuring_time{};
    int instructions_this_frame{};
    int instructions_last_frame{};
    int last_swi{-1};
    int last_interrupt_mask{};
    int audio_underruns{};
    std::int32_t first_unsupported_address{};
    int bg2_matrix_writes{};
    int bg2_reference_writes{};
    std::array<int, 0x30> swi_counts{};
    std::int64_t ppu_nanos_last_frame{};
    std::int64_t dma_nanos_last_frame{};
    std::int64_t apu_nanos_last_frame{};
    std::int64_t ppu_nanos{};
    std::int64_t dma_nanos{};
    std::int64_t apu_nanos{};

private:
    static constexpr std::size_t index(DiagnosticEvent event) noexcept {
        return static_cast<std::size_t>(event);
    }
    static constexpr int max_reports_per_event = 8;
    static constexpr int stuck_wait_cycles = 2 * 280'896;
    std::array<int, 5> counts{};
    std::array<int, 5> reported{};
    int wait_cycles{};
    std::vector<DiagnosticMessage> messages;
};

constexpr std::size_t save_size(GbaSaveType type) noexcept {
    switch (type) {
    case GbaSaveType::sram: return 32U * 1024U;
    case GbaSaveType::flash_64k: return 64U * 1024U;
    case GbaSaveType::flash_128k: return 128U * 1024U;
    case GbaSaveType::eeprom_512: return 512U;
    case GbaSaveType::eeprom_8k: return 8U * 1024U;
    case GbaSaveType::none: return 0;
    }
    return 0;
}

constexpr bool is_eeprom(GbaSaveType type) noexcept {
    return type == GbaSaveType::eeprom_512 || type == GbaSaveType::eeprom_8k;
}

class SaveMemory {
public:
    explicit SaveMemory(GbaSaveType type) : type_(type), data_(save_size(type)) {}
    virtual ~SaveMemory() = default;

    [[nodiscard]] GbaSaveType type() const noexcept { return type_; }
    [[nodiscard]] bool dirty() const noexcept { return generation_ != saved_generation_; }
    [[nodiscard]] std::uint64_t generation() const noexcept { return generation_; }
    [[nodiscard]] const std::vector<std::uint8_t>& data() const noexcept { return data_; }
    [[nodiscard]] std::vector<std::uint8_t>& data() noexcept { return data_; }

    void import(std::span<const std::uint8_t> saved) {
        const auto count = std::min(saved.size(), data_.size());
        std::copy_n(saved.begin(), static_cast<std::ptrdiff_t>(count), data_.begin());
        std::fill(data_.begin() + static_cast<std::ptrdiff_t>(count), data_.end(), 0);
        saved_generation_ = generation_;
    }
    void acknowledge(std::uint64_t generation) noexcept {
        if (generation == generation_) saved_generation_ = generation;
    }
    virtual int read(int address) = 0;
    virtual void write(int address, int value) = 0;
    virtual void hint_transfer_length(int) noexcept {}

protected:
    void written() noexcept { ++generation_; }

private:
    GbaSaveType type_;
    std::vector<std::uint8_t> data_;
    std::uint64_t generation_{};
    std::uint64_t saved_generation_{};
};

class Sram final : public SaveMemory {
public:
    Sram() : SaveMemory(GbaSaveType::sram) {}
    int read(int address) override {
        return data()[static_cast<std::size_t>(address) & (data().size() - 1U)];
    }
    void write(int address, int value) override {
        data()[static_cast<std::size_t>(address) & (data().size() - 1U)] =
            static_cast<std::uint8_t>(value);
        written();
    }
};

class Flash final : public SaveMemory {
public:
    explicit Flash(GbaSaveType type) : SaveMemory(type) {
        std::fill(data().begin(), data().end(), 0xffU);
    }
    int read(int address) override {
        const auto offset = static_cast<std::size_t>(address) & 0xffffU;
        if (id_mode_) {
            if (offset == 0) return type() == GbaSaveType::flash_128k ? 0x62 : 0x32;
            if (offset == 1) return type() == GbaSaveType::flash_128k ? 0x13 : 0x1b;
            return 0;
        }
        return data()[static_cast<std::size_t>(bank_) * bank_size + offset];
    }
    void write(int address, int value) override {
        const auto offset = static_cast<std::size_t>(address) & 0xffffU;
        const auto byte = static_cast<std::uint8_t>(value);
        if (write_armed_) {
            data()[static_cast<std::size_t>(bank_) * bank_size + offset] = byte;
            write_armed_ = false;
            written();
            return;
        }
        if (bank_switch_armed_) {
            bank_ = static_cast<int>(byte & 1U);
            bank_switch_armed_ = false;
            return;
        }
        if (command_step_ == 0 && offset == 0x5555U && byte == 0xaaU) {
            command_step_ = 1;
        } else if (command_step_ == 1 && offset == 0x2aaaU && byte == 0x55U) {
            command_step_ = 2;
        } else if (command_step_ == 2 && byte == 0x30U && erase_armed_) {
            command_step_ = 0;
            erase_armed_ = false;
            const auto start = static_cast<std::size_t>(bank_) * bank_size + (offset & 0xf000U);
            std::fill_n(data().begin() + static_cast<std::ptrdiff_t>(start), sector_size, 0xffU);
            written();
        } else if (command_step_ == 2 && offset == 0x5555U) {
            command_step_ = 0;
            switch (byte) {
            case 0x90: id_mode_ = true; break;
            case 0xf0: id_mode_ = false; break;
            case 0x80: erase_armed_ = true; break;
            case 0xa0: write_armed_ = true; break;
            case 0xb0:
                if (type() == GbaSaveType::flash_128k) bank_switch_armed_ = true;
                break;
            case 0x10:
                if (erase_armed_) {
                    std::fill(data().begin(), data().end(), 0xffU);
                    erase_armed_ = false;
                    written();
                }
                break;
            default: break;
            }
        } else {
            command_step_ = 0;
        }
    }

private:
    static constexpr std::size_t bank_size = 64U * 1024U;
    static constexpr std::size_t sector_size = 4096U;
    int command_step_{};
    bool id_mode_{};
    bool erase_armed_{};
    bool write_armed_{};
    bool bank_switch_armed_{};
    int bank_{};
};

class Eeprom final : public SaveMemory {
public:
    explicit Eeprom(GbaSaveType type)
        : SaveMemory(type), address_bits_(type == GbaSaveType::eeprom_8k ? 14 : 6) {}

    void hint_transfer_length(int words) noexcept override {
        if (words == 9 || words == 73) address_bits_ = 6;
        if (words == 17 || words == 81) address_bits_ = 14;
    }
    int read(int) override {
        if (state_ != State::reading) return 1;
        ++read_bits_sent_;
        if (read_bits_sent_ <= 4) return 0;
        const auto bit_index = 64 - (read_bits_sent_ - 4);
        const auto bit = static_cast<int>((read_shift_ >> bit_index) & 1U);
        if (read_bits_sent_ - 4 >= 64) {
            state_ = State::idle;
            read_bits_sent_ = 0;
        }
        return bit;
    }
    void write(int, int value) override {
        const auto bit = static_cast<std::uint64_t>(value & 1);
        switch (state_) {
        case State::idle:
            bit_buffer_ = bit;
            bit_count_ = 1;
            state_ = State::command;
            break;
        case State::command:
            bit_buffer_ = (bit_buffer_ << 1U) | bit;
            if (++bit_count_ == 2 + address_bits_) begin_transfer();
            break;
        case State::writing:
            bit_buffer_ = (bit_buffer_ << 1U) | bit;
            if (++bit_count_ == 64) {
                commit_write();
                state_ = State::write_stop;
            }
            break;
        case State::write_stop: state_ = State::idle; break;
        case State::reading: break;
        }
    }

private:
    enum class State { idle, command, writing, write_stop, reading };
    void begin_transfer() {
        const auto command = static_cast<int>((bit_buffer_ >> address_bits_) & 3U);
        address_ = static_cast<int>(bit_buffer_ & ((1ULL << address_bits_) - 1ULL)) * 8;
        if (command == 3) {
            const auto base = static_cast<std::size_t>(address_) & (data().size() - 1U);
            read_shift_ = 0;
            for (std::size_t i = 0; i < 8; ++i) read_shift_ = (read_shift_ << 8U) | data()[base + i];
            read_bits_sent_ = 0;
            state_ = State::reading;
        } else {
            bit_buffer_ = 0;
            bit_count_ = 0;
            state_ = State::writing;
        }
    }
    void commit_write() {
        const auto base = static_cast<std::size_t>(address_) & (data().size() - 1U);
        for (std::size_t i = 0; i < 8; ++i) {
            data()[base + i] = static_cast<std::uint8_t>(bit_buffer_ >> ((7U - i) * 8U));
        }
        written();
    }

    int address_bits_;
    State state_{State::idle};
    std::uint64_t bit_buffer_{};
    int bit_count_{};
    int address_{};
    std::uint64_t read_shift_{};
    int read_bits_sent_{};
};

std::unique_ptr<SaveMemory> make_save(GbaSaveType type) {
    switch (type) {
    case GbaSaveType::sram: return std::make_unique<Sram>();
    case GbaSaveType::flash_64k:
    case GbaSaveType::flash_128k: return std::make_unique<Flash>(type);
    case GbaSaveType::eeprom_512:
    case GbaSaveType::eeprom_8k: return std::make_unique<Eeprom>(type);
    case GbaSaveType::none: return nullptr;
    }
    return nullptr;
}

GbaSaveType detect_save(std::span<const std::uint8_t> rom) {
    constexpr std::array markers{
        std::pair{std::string_view{"FLASH1M_V"}, GbaSaveType::flash_128k},
        std::pair{std::string_view{"FLASH512_V"}, GbaSaveType::flash_64k},
        std::pair{std::string_view{"FLASH_V"}, GbaSaveType::flash_64k},
        std::pair{std::string_view{"EEPROM_V"}, GbaSaveType::eeprom_512},
        std::pair{std::string_view{"SRAM_F_V"}, GbaSaveType::sram},
        std::pair{std::string_view{"SRAM_V"}, GbaSaveType::sram},
    };
    for (const auto& [marker, type] : markers) {
        for (std::size_t offset = 0; offset + marker.size() <= rom.size(); offset += 4) {
            if (std::equal(marker.begin(), marker.end(), rom.begin() + static_cast<std::ptrdiff_t>(offset))) {
                return type;
            }
        }
    }
    return GbaSaveType::none;
}

class Rtc {
public:
    using Clock = std::function<std::int64_t()>;
    explicit Rtc(Clock clock) : clock_(std::move(clock)) {}

    int pin_state(int data, int direction) const noexcept {
        auto result = data & direction;
        if ((direction & 2) == 0) result |= sio_out_ << 1;
        return result & 0x0f;
    }
    void write(int data, int direction) {
        const auto new_sck = pin(data, direction, 0, sck_);
        const auto new_sio = pin(data, direction, 1, sio_);
        const auto new_cs = pin(data, direction, 2, chip_select_);
        if (new_cs == 0) phase_ = idle;
        else if (chip_select_ == 0) {
            phase_ = command_phase;
            shift_ = 0;
            bit_index_ = 0;
        } else if (sck_ == 0 && new_sck == 1) on_rise(new_sio);
        else if (sck_ == 1 && new_sck == 0) on_fall();
        sck_ = new_sck;
        sio_ = new_sio;
        chip_select_ = new_cs;
    }
    std::array<std::int32_t, 20> export_state() const noexcept {
        std::array<std::int32_t, 20> result{
            sck_, sio_, chip_select_, sio_out_, phase_, shift_, bit_index_, command_,
            byte_index_, byte_count_, status_, static_cast<std::int32_t>(static_cast<std::uint64_t>(offset_seconds_) >> 32U),
            static_cast<std::int32_t>(offset_seconds_),
        };
        std::copy(transfer_.begin(), transfer_.end(), result.begin() + 13);
        return result;
    }
    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != 20) throw SaveStateError("État RTC GBA invalide");
        sck_ = values[0]; sio_ = values[1]; chip_select_ = values[2]; sio_out_ = values[3];
        phase_ = values[4]; shift_ = values[5]; bit_index_ = values[6]; command_ = values[7];
        byte_index_ = values[8]; byte_count_ = values[9]; status_ = values[10];
        offset_seconds_ = static_cast<std::int64_t>(
            (static_cast<std::uint64_t>(u32(values[11])) << 32U) | u32(values[12]));
        std::copy_n(values.begin() + 13, 7, transfer_.begin());
    }

private:
    static constexpr int idle = 0;
    static constexpr int command_phase = 1;
    static constexpr int reading = 2;
    static constexpr int writing = 3;
    static int pin(int data, int direction, int bit, int previous) noexcept {
        return (direction & (1 << bit)) != 0 ? (data >> bit) & 1 : previous;
    }
    static int reverse_bits(int value) noexcept {
        auto input = static_cast<unsigned>(value) & 0xffU;
        unsigned result{};
        for (unsigned bit = 0; bit < 8; ++bit) result |= ((input >> bit) & 1U) << (7U - bit);
        return static_cast<int>(result);
    }
    static int bcd(int value) noexcept { return ((value / 10) << 4) | (value % 10); }
    static int from_bcd(int value) noexcept { return ((value >> 4) & 15) * 10 + (value & 15); }
    std::tm game_time() const {
        const auto seconds = static_cast<std::time_t>(clock_() + offset_seconds_);
        std::tm result{};
#if defined(_WIN32)
        localtime_s(&result, &seconds);
#else
        localtime_r(&seconds, &result);
#endif
        return result;
    }
    void load_time(const std::tm& value, int at) noexcept {
        transfer_[static_cast<std::size_t>(at)] = bcd(value.tm_hour);
        transfer_[static_cast<std::size_t>(at + 1)] = bcd(value.tm_min);
        transfer_[static_cast<std::size_t>(at + 2)] = bcd(value.tm_sec);
    }
    void decode_command() {
        auto byte = shift_ & 0xff;
        if ((byte & 0xf0) != 0x60) byte = reverse_bits(byte);
        command_ = (byte >> 1) & 7;
        const auto is_reading = (byte & 1) != 0;
        shift_ = 0; bit_index_ = 0; byte_index_ = 0; transfer_.fill(0);
        if (command_ == 0) {
            status_ = 0x40;
            offset_seconds_ = 0;
            phase_ = idle;
        } else if (command_ == 1) {
            byte_count_ = 1;
            if (is_reading) transfer_[0] = status_;
            phase_ = is_reading ? reading : writing;
        } else if (command_ == 2 || command_ == 3) {
            const auto now = game_time();
            byte_count_ = command_ == 2 ? 7 : 3;
            if (is_reading && command_ == 2) {
                transfer_[0] = bcd((now.tm_year + 1900) % 100);
                transfer_[1] = bcd(now.tm_mon + 1);
                transfer_[2] = bcd(now.tm_mday);
                transfer_[3] = now.tm_wday;
                load_time(now, 4);
            } else if (is_reading) {
                load_time(now, 0);
            }
            phase_ = is_reading ? reading : writing;
        } else {
            phase_ = idle;
        }
    }
    void on_rise(int bit) {
        if (phase_ == command_phase) {
            shift_ = (shift_ << 1) | bit;
            if (++bit_index_ == 8) decode_command();
        } else if (phase_ == writing) {
            shift_ |= bit << bit_index_;
            if (++bit_index_ == 8) {
                transfer_[static_cast<std::size_t>(byte_index_)] = shift_ & 0xff;
                shift_ = 0; bit_index_ = 0;
                if (++byte_index_ == byte_count_) {
                    apply_written();
                    phase_ = idle;
                }
            }
        }
    }
    void on_fall() noexcept {
        if (phase_ != reading) return;
        sio_out_ = (transfer_[static_cast<std::size_t>(byte_index_)] >> bit_index_) & 1;
        if (++bit_index_ == 8) {
            bit_index_ = 0;
            if (++byte_index_ >= byte_count_) phase_ = idle;
        }
    }
    void apply_written() {
        if (command_ == 1) {
            status_ = 0x40 | (transfer_[0] & 0x0e);
            return;
        }
        auto target = game_time();
        const auto at = command_ == 2 ? 4 : 0;
        if (command_ == 2) {
            target.tm_year = 100 + from_bcd(transfer_[0]);
            target.tm_mon = from_bcd(transfer_[1]) - 1;
            target.tm_mday = from_bcd(transfer_[2]);
        }
        target.tm_hour = from_bcd(transfer_[static_cast<std::size_t>(at)] & 0x7f);
        target.tm_min = from_bcd(transfer_[static_cast<std::size_t>(at + 1)]);
        target.tm_sec = from_bcd(transfer_[static_cast<std::size_t>(at + 2)]);
        const auto epoch = std::mktime(&target);
        if (epoch != static_cast<std::time_t>(-1)) offset_seconds_ = epoch - clock_();
    }

    Clock clock_;
    int sck_{};
    int sio_{};
    int chip_select_{};
    int sio_out_{};
    int phase_{};
    int shift_{};
    int bit_index_{};
    int command_{};
    int byte_index_{};
    int byte_count_{};
    std::array<int, 7> transfer_{};
    int status_{0x40};
    std::int64_t offset_seconds_{};
};

class Gpio {
public:
    static constexpr int data_offset = 0xc4;
    static constexpr int direction_offset = 0xc6;
    static constexpr int control_offset = 0xc8;
    static constexpr int state_words = 23;

    explicit Gpio(Rtc::Clock clock) : rtc_(std::move(clock)) {}
    static bool covers(int offset) noexcept { return offset >= 0xc4 && offset <= 0xc9; }
    int read16(int offset) const noexcept {
        if (offset == data_offset) return rtc_.pin_state(data_, direction_);
        if (offset == direction_offset) return direction_;
        if (offset == control_offset) return readable_ ? 1 : 0;
        return 0;
    }
    [[nodiscard]] bool readable() const noexcept { return readable_; }
    void write16(int offset, int value) {
        if (offset == data_offset) { data_ = value & 15; rtc_.write(data_, direction_); }
        else if (offset == direction_offset) { direction_ = value & 15; rtc_.write(data_, direction_); }
        else if (offset == control_offset) readable_ = (value & 1) != 0;
    }
    std::array<std::int32_t, state_words> export_state() const noexcept {
        std::array<std::int32_t, state_words> result{};
        result[0] = data_; result[1] = direction_; result[2] = readable_ ? 1 : 0;
        const auto rtc = rtc_.export_state();
        std::copy(rtc.begin(), rtc.end(), result.begin() + 3);
        return result;
    }
    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != state_words) throw SaveStateError("État GPIO GBA invalide");
        data_ = values[0]; direction_ = values[1]; readable_ = values[2] != 0;
        rtc_.import_state(values.subspan(3));
    }

private:
    Rtc rtc_;
    int data_{};
    int direction_{};
    bool readable_{};
};

class Cartridge {
public:
    static constexpr std::size_t max_rom_size = 0x0200'0000U;
    Cartridge(RomImage rom, std::optional<GbaSaveType> forced, Rtc::Clock clock)
        : rom_(std::move(rom)),
          save_type_(forced.value_or(detect_save(std::span<const std::uint8_t>{*rom_}))),
          save_(make_save(save_type_)) {
        const auto view = std::span<const std::uint8_t>{*rom_};
        if (view.size() > max_rom_size) throw RomLoadError("ROM GBA trop volumineuse");
        if (view.size() < 0xc0U) throw RomLoadError("ROM GBA trop courte");
        if (view[0xb2] != 0x96U) throw RomLoadError("Marqueur GBA 0x96 absent");
        constexpr std::string_view rtc_marker = "SIIRTC_V";
        if (std::search(view.begin(), view.end(), rtc_marker.begin(), rtc_marker.end()) != view.end()) {
            gpio_ = std::make_unique<Gpio>(std::move(clock));
        }
    }
    int read8(int offset) const noexcept {
        const auto index = static_cast<std::size_t>(offset);
        return index < rom_->size() ? (*rom_)[index] : 0;
    }
    [[nodiscard]] GbaSaveType save_type() const noexcept { return save_type_; }
    [[nodiscard]] SaveMemory* save() noexcept { return save_.get(); }
    [[nodiscard]] const SaveMemory* save() const noexcept { return save_.get(); }
    [[nodiscard]] Gpio* gpio() noexcept { return gpio_.get(); }
    [[nodiscard]] const Gpio* gpio() const noexcept { return gpio_.get(); }

private:
    RomImage rom_;
    GbaSaveType save_type_;
    std::unique_ptr<SaveMemory> save_;
    std::unique_ptr<Gpio> gpio_;
};

class InterruptController {
public:
    enum Bit { vblank = 0, hblank = 1, vcount = 2, timer0 = 3, dma0 = 8 };
    void request(int bit) {
        const auto mask = 1 << bit;
        flags |= mask;
        if (on_request) on_request(mask);
    }
    void acknowledge(int value) noexcept { flags &= ~value; }
    [[nodiscard]] bool pending() const noexcept {
        return master_enable && (enable & flags & 0x3fff) != 0;
    }
    void reset() noexcept { enable = 0; flags = 0; master_enable = false; }
    int enable{};
    int flags{};
    bool master_enable{};
    std::function<void(int)> on_request;
};

class Keypad {
public:
    void set_button(Button button, bool pressed) noexcept {
        int bit{};
        switch (button) {
        case Button::a: bit = 0; break;
        case Button::b: bit = 1; break;
        case Button::select: bit = 2; break;
        case Button::start: bit = 3; break;
        case Button::right: bit = 4; break;
        case Button::left: bit = 5; break;
        case Button::up: bit = 6; break;
        case Button::down: bit = 7; break;
        case Button::r: bit = 8; break;
        case Button::l: bit = 9; break;
        }
        if (pressed) pressed_bits |= 1 << bit;
        else pressed_bits &= ~(1 << bit);
    }
    [[nodiscard]] int input() const noexcept { return ~pressed_bits & 0x03ff; }
    int pressed_bits{};
};

class MemoryTiming {
public:
    [[nodiscard]] int bus_width(std::int32_t address) const noexcept {
        const auto region = (u32(address) >> 24U) & 0xffU;
        if (region == 0x02U || region == 0x05U || region == 0x06U ||
            (region >= 0x08U && region <= 0x0dU)) return 2;
        if (region == 0x0eU || region == 0x0fU) return 1;
        return 4;
    }
    [[nodiscard]] int wait_states(std::int32_t address, int width, bool sequential) const noexcept {
        const auto first = unit_wait(address, sequential);
        const auto extra = width / bus_width(address) - 1;
        return extra <= 0 ? first : first + extra * (unit_wait(address, true) + 1);
    }
    [[nodiscard]] int instruction_wait(std::int32_t address, int width, bool sequential) const noexcept {
        const auto region = (u32(address) >> 24U) & 0xffU;
        if (sequential && (wait_control & 0x4000) != 0 && region >= 0x08U && region <= 0x0dU) return 0;
        return wait_states(address, width, sequential);
    }
    int wait_control{};

private:
    [[nodiscard]] int unit_wait(std::int32_t address, bool sequential) const noexcept {
        static constexpr std::array n_wait{4, 3, 2, 8};
        const auto raw = u32(address);
        const auto region = (raw >> 24U) & 0xffU;
        if (region == 0x02U) return 2;
        if (region == 0x0eU || region == 0x0fU) return n_wait[static_cast<std::size_t>(wait_control & 3)];
        if (region < 0x08U || region > 0x0dU) return 0;
        switch ((raw >> 25U) & 3U) {
        case 0: return sequential ? ((wait_control & 0x10) != 0 ? 1 : 2)
                                  : n_wait[static_cast<std::size_t>((wait_control >> 2) & 3)];
        case 1: return sequential ? ((wait_control & 0x80) != 0 ? 1 : 4)
                                  : n_wait[static_cast<std::size_t>((wait_control >> 5) & 3)];
        default: return sequential ? ((wait_control & 0x400) != 0 ? 1 : 8)
                                    : n_wait[static_cast<std::size_t>((wait_control >> 8) & 3)];
        }
    }
};

class Ppu;
class Timers;
class DmaController;
class Apu;

int ppu_dispstat_low(const Ppu*) noexcept;
int ppu_vcount(const Ppu*) noexcept;
void ppu_affine_reference_write(Ppu*, int) noexcept;
void dma_control_write(DmaController*, int, int);

class Bus {
public:
    explicit Bus(Cartridge& cartridge);

    int read8(std::int32_t address);
    int read16(std::int32_t address);
    std::int32_t read32(std::int32_t address);
    int fetch16(std::int32_t address);
    std::int32_t fetch32(std::int32_t address);
    void write8(std::int32_t address, int value);
    void write16(std::int32_t address, int value);
    void write32(std::int32_t address, std::int32_t value);
    int take_wait_cycles() noexcept { return std::exchange(wait_cycles, 0); }
    void break_access_sequence() noexcept { next_sequential_address = -1; }
    void reset_affine_matrices();
    void sync_timing_from_io();
    [[nodiscard]] Eeprom* eeprom() noexcept { return dynamic_cast<Eeprom*>(cartridge.save()); }
    [[nodiscard]] SaveMemory* save_memory() noexcept { return cartridge.save(); }

    Cartridge& cartridge;
    Keypad keypad;
    std::array<std::uint8_t, 0x4000> bios{};
    std::array<std::uint8_t, 0x40000> ewram{};
    std::array<std::uint8_t, 0x8000> iwram{};
    std::array<std::uint8_t, 0x400> io{};
    std::array<std::uint8_t, 0x400> palette{};
    std::array<std::uint8_t, 0x18000> vram{};
    std::array<std::uint8_t, 0x400> oam{};
    std::array<std::uint8_t, 0x10000> fallback_sram{};
    Ppu* ppu{};
    InterruptController* interrupts{};
    Timers* timers{};
    DmaController* dma{};
    Apu* apu{};
    MemoryTiming timing;
    Diagnostics diagnostics;
    int wait_cycles{};

private:
    static constexpr int io_mask = 0x3ff;
    void account(std::int32_t address, int width, bool fetch = false) noexcept;
    [[nodiscard]] int read8_raw(std::int32_t address);
    void write8_raw(std::int32_t address, int value);
    void write16_raw(std::int32_t address, int value);
    [[nodiscard]] int read_io(int offset);
    [[nodiscard]] int read_timer_byte(int offset);
    [[nodiscard]] int io_half(int offset) const noexcept;
    void write_io_half(int offset, int value) noexcept;
    void propagate_io_write(int offset, std::int32_t value, int bytes);
    void handle_io_write(int offset, int value);
    [[nodiscard]] bool eeprom_window(std::int32_t address) const noexcept;
    [[nodiscard]] bool gpio_register(std::int32_t address) const noexcept;
    [[nodiscard]] static int rom_offset(std::int32_t address) noexcept { return static_cast<int>(u32(address) & 0x01ff'ffffU); }
    [[nodiscard]] static int vram_offset(std::int32_t address) noexcept {
        auto offset = static_cast<int>(u32(address) & 0x1ffffU);
        if (offset >= 0x18000) offset -= 0x8000;
        return offset;
    }
    std::int32_t next_sequential_address{-1};
};

class Timers {
public:
    explicit Timers(InterruptController& interrupts) : interrupts_(interrupts) {}
    void reload_write(int timer, int value) {
        flush();
        reload_[static_cast<std::size_t>(timer)] = value & 0xffff;
    }
    void control_write(int timer, int value) {
        flush();
        const auto index = static_cast<std::size_t>(timer);
        const auto was_enabled = (control_[index] & 0x80) != 0;
        control_[index] = value & 0xffff;
        if (!was_enabled && (value & 0x80) != 0) {
            counter_[index] = reload_[index];
            prescaler_counter_[index] = 0;
        }
        cycles_until_overflow_ = compute_budget();
    }
    int counter(int timer) {
        const auto index = static_cast<std::size_t>(timer);
        if (pending_cycles_ < cycles_until_overflow_) {
            const auto settings = control_[index];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) return counter_[index];
            const auto prescale = prescale_[static_cast<std::size_t>(settings & 3)];
            return counter_[index] + (prescaler_counter_[index] + pending_cycles_) / prescale;
        }
        flush();
        return counter_[index];
    }
    [[nodiscard]] int control(int timer) const noexcept { return control_[static_cast<std::size_t>(timer)]; }
    [[nodiscard]] int cycles_until_next_overflow() const noexcept {
        if (cycles_until_overflow_ == no_overflow) return no_overflow;
        return std::max(1, cycles_until_overflow_ - pending_cycles_);
    }
    void tick(int cycles) {
        if (cycles_until_overflow_ == no_overflow) return;
        pending_cycles_ += cycles;
        if (pending_cycles_ >= cycles_until_overflow_) flush();
    }
    std::array<std::int32_t, 16> export_state() {
        flush();
        std::array<std::int32_t, 16> result{};
        std::copy(counter_.begin(), counter_.end(), result.begin());
        std::copy(reload_.begin(), reload_.end(), result.begin() + 4);
        std::copy(control_.begin(), control_.end(), result.begin() + 8);
        std::copy(prescaler_counter_.begin(), prescaler_counter_.end(), result.begin() + 12);
        return result;
    }
    void import_state(std::span<const std::int32_t> state) {
        if (state.size() != 16) throw SaveStateError("État timers GBA invalide");
        std::copy_n(state.begin(), 4, counter_.begin());
        std::copy_n(state.begin() + 4, 4, reload_.begin());
        std::copy_n(state.begin() + 8, 4, control_.begin());
        std::copy_n(state.begin() + 12, 4, prescaler_counter_.begin());
        pending_cycles_ = 0;
        cycles_until_overflow_ = compute_budget();
    }
    void reset() noexcept {
        counter_.fill(0); reload_.fill(0); control_.fill(0); prescaler_counter_.fill(0);
        pending_cycles_ = 0; cycles_until_overflow_ = no_overflow;
    }
    std::function<void(int)> on_overflow;

private:
    static constexpr int no_overflow = std::numeric_limits<int>::max();
    static constexpr std::array prescale_{1, 64, 256, 1024};
    void flush() {
        const auto cycles = std::exchange(pending_cycles_, 0);
        if (cycles > 0) apply_cycles(cycles);
        cycles_until_overflow_ = compute_budget();
    }
    [[nodiscard]] int compute_budget() const noexcept {
        auto budget = no_overflow;
        for (std::size_t i = 0; i < 4; ++i) {
            const auto settings = control_[i];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) continue;
            const auto divisor = prescale_[static_cast<std::size_t>(settings & 3)];
            const auto cycles = (0x10000 - counter_[i]) * divisor - prescaler_counter_[i];
            budget = std::min(budget, cycles);
        }
        return budget;
    }
    void apply_cycles(int cycles) {
        for (std::size_t i = 0; i < 4; ++i) {
            const auto settings = control_[i];
            if ((settings & 0x80) == 0 || (settings & 0x04) != 0) continue;
            const auto divisor = prescale_[static_cast<std::size_t>(settings & 3)];
            const auto total = prescaler_counter_[i] + cycles;
            prescaler_counter_[i] = total % divisor;
            if (total >= divisor) advance(static_cast<int>(i), total / divisor);
        }
    }
    void advance(int timer, int ticks) {
        const auto index = static_cast<std::size_t>(timer);
        const auto value = counter_[index] + ticks;
        if (value <= 0xffff) {
            counter_[index] = value;
            return;
        }
        const auto period = 0x10000 - reload_[index];
        const auto excess = value - 0x10000;
        const auto overflows = 1 + excess / period;
        counter_[index] = reload_[index] + excess % period;
        for (int i = 0; i < overflows; ++i) overflow(timer);
    }
    void overflow(int timer) {
        const auto index = static_cast<std::size_t>(timer);
        if ((control_[index] & 0x40) != 0) interrupts_.request(InterruptController::timer0 + timer);
        if (on_overflow) on_overflow(timer);
        const auto next = timer + 1;
        if (next < 4) {
            const auto settings = control_[static_cast<std::size_t>(next)];
            if ((settings & 0x84) == 0x84) advance(next, 1);
        }
    }

    InterruptController& interrupts_;
    std::array<int, 4> counter_{};
    std::array<int, 4> reload_{};
    std::array<int, 4> control_{};
    std::array<int, 4> prescaler_counter_{};
    int pending_cycles_{};
    int cycles_until_overflow_{no_overflow};
};

class Envelope {
public:
    void trigger() noexcept { volume = initial_volume; timer_ = period; }
    void clock() noexcept {
        if (period == 0) return;
        if (timer_ > 0) --timer_;
        if (timer_ != 0) return;
        timer_ = period;
        if (increasing && volume < 15) ++volume;
        else if (!increasing && volume > 0) --volume;
    }
    void write(int value) noexcept {
        initial_volume = (value >> 4) & 15;
        increasing = (value & 8) != 0;
        period = value & 7;
        volume = initial_volume;
    }
    [[nodiscard]] bool dac_enabled() const noexcept { return initial_volume != 0 || increasing; }
    int initial_volume{};
    bool increasing{};
    int period{};
    int volume{};
private:
    int timer_{};
};

class SquareChannel {
public:
    explicit SquareChannel(bool sweep) : has_sweep_(sweep) {}
    void trigger() {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = (2048 - frequency) * 4;
        envelope.trigger();
        if (has_sweep_) {
            sweep_shadow_ = frequency;
            sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
            sweep_active_ = sweep_period_ != 0 || sweep_shift_ != 0;
            if (sweep_shift_ != 0) compute_sweep(false);
        }
    }
    void write_sweep(int value) noexcept {
        sweep_period_ = (value >> 4) & 7;
        sweep_negate_ = (value & 8) != 0;
        sweep_shift_ = value & 7;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        if (timer_ > 0) return;
        const auto period = std::max(1, (2048 - frequency) * 4);
        const auto steps = 1 + (-timer_) / period;
        timer_ += steps * period;
        duty_step_ = (duty_step_ + steps) & 7;
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    void clock_sweep() {
        if (!has_sweep_ || !sweep_active_) return;
        if (sweep_timer_ > 0) --sweep_timer_;
        if (sweep_timer_ != 0) return;
        sweep_timer_ = sweep_period_ == 0 ? 8 : sweep_period_;
        if (sweep_period_ != 0) compute_sweep(true);
    }
    [[nodiscard]] int output() const noexcept {
        static constexpr std::array masks{0x80, 0x81, 0xe1, 0x7e};
        return enabled && ((masks[static_cast<std::size_t>(duty)] >> duty_step_) & 1) != 0
            ? envelope.volume : 0;
    }
    void reset() noexcept { enabled = false; timer_ = 0; duty_step_ = 0; length_counter = 0; }
    bool enabled{};
    int duty{2};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    void compute_sweep(bool apply) noexcept {
        const auto delta = sweep_shadow_ >> sweep_shift_;
        const auto next = sweep_negate_ ? sweep_shadow_ - delta : sweep_shadow_ + delta;
        if (next > 2047) { enabled = false; return; }
        if (apply && sweep_shift_ != 0) { sweep_shadow_ = next; frequency = next; }
    }
    bool has_sweep_{};
    int timer_{};
    int duty_step_{};
    int sweep_period_{};
    bool sweep_negate_{};
    int sweep_shift_{};
    int sweep_timer_{};
    int sweep_shadow_{};
    bool sweep_active_{};
};

class WaveChannel {
public:
    void trigger() noexcept {
        enabled = dac_enabled;
        if (length_counter == 0) length_counter = 256;
        timer_ = (2048 - frequency) * 2;
        position_ = 0;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        if (timer_ > 0) return;
        const auto period = std::max(1, (2048 - frequency) * 2);
        const auto steps = 1 + (-timer_) / period;
        timer_ += steps * period;
        position_ = (position_ + steps) & 31;
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    [[nodiscard]] int output() const noexcept {
        if (!enabled || !dac_enabled || volume_code == 0) return 0;
        const auto sample = wave_ram[static_cast<std::size_t>(position_)];
        return volume_code == 1 ? sample : volume_code == 2 ? sample >> 1 : sample >> 2;
    }
    void reset() noexcept { enabled = false; timer_ = 0; position_ = 0; length_counter = 0; }
    bool enabled{};
    bool dac_enabled{};
    int frequency{};
    int length_counter{};
    bool length_enabled{};
    int volume_code{};
    std::array<int, 32> wave_ram{};
private:
    int timer_{};
    int position_{};
};

class NoiseChannel {
public:
    void trigger() noexcept {
        enabled = envelope.dac_enabled();
        if (length_counter == 0) length_counter = 64;
        timer_ = period(); lfsr_ = 0x7fff; envelope.trigger();
    }
    void write_polynomial(int value) noexcept {
        shift_clock_ = (value >> 4) & 15;
        width_mode_ = (value & 8) != 0;
        divisor_code_ = value & 7;
    }
    void tick(int cycles) noexcept {
        if (!enabled) return;
        timer_ -= cycles;
        const auto timer_period = period();
        while (timer_ <= 0) {
            timer_ += timer_period;
            const auto feedback = (lfsr_ & 1) ^ ((lfsr_ >> 1) & 1);
            lfsr_ = (lfsr_ >> 1) | (feedback << 14);
            if (width_mode_) lfsr_ = (lfsr_ & ~0x40) | (feedback << 6);
        }
    }
    void clock_length() noexcept {
        if (length_enabled && length_counter > 0 && --length_counter == 0) enabled = false;
    }
    [[nodiscard]] int output() const noexcept { return enabled && (lfsr_ & 1) == 0 ? envelope.volume : 0; }
    void reset() noexcept { enabled = false; timer_ = 0; lfsr_ = 0x7fff; length_counter = 0; }
    bool enabled{};
    int length_counter{};
    bool length_enabled{};
    Envelope envelope;
private:
    [[nodiscard]] int period() const noexcept {
        const auto divisor = divisor_code_ == 0 ? 8 : divisor_code_ * 16;
        return std::max(1, divisor << shift_clock_);
    }
    int shift_clock_{};
    bool width_mode_{};
    int divisor_code_{};
    int timer_{};
    int lfsr_{0x7fff};
};

class Apu {
public:
    static constexpr int sample_rate = 32768;
    Apu() : square1(true), square2(false) {}
    [[nodiscard]] int cycles_until_next_sample() const noexcept {
        return ((cycles_per_sample - sample_timer_) << 2) - cpu_remainder_;
    }
    void tick(int cpu_cycles) {
        cpu_remainder_ += cpu_cycles;
        if (cycles_until_next_sample() > 0) return;
        auto audio_cycles = cpu_remainder_ >> 2;
        cpu_remainder_ -= audio_cycles << 2;
        const auto start = measuring_time_ ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point{};
        while (audio_cycles > 0) {
            const auto step = std::min(audio_cycles, cycles_per_sample - sample_timer_);
            square1.tick(step); square2.tick(step); wave.tick(step); noise.tick(step);
            sequencer_timer_ += step;
            while (sequencer_timer_ >= 8192) { sequencer_timer_ -= 8192; clock_sequencer(); }
            sample_timer_ += step;
            if (sample_timer_ >= cycles_per_sample) { sample_timer_ -= cycles_per_sample; emit_sample(); }
            audio_cycles -= step;
        }
        if (measuring_time_ && on_batch_nanos) {
            on_batch_nanos(std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - start).count());
        }
    }
    void timer_overflow(int timer) {
        if (timer > 1) return;
        if (((control_h >> 10) & 1) == timer) pop_fifo(0);
        if (((control_h >> 14) & 1) == timer) pop_fifo(1);
    }
    void push_fifo(int channel, std::int32_t value, int bytes) {
        auto& fifo = channel == 0 ? fifo_a_ : fifo_b_;
        for (int i = 0; i < bytes; ++i) fifo.push(static_cast<std::int8_t>(u32(value) >> (static_cast<unsigned>(i) * 8U)));
    }
    void write_register(int offset, int value) {
        switch (offset) {
        case 0x60: square1.write_sweep(value & 0xff); break;
        case 0x62: write_square_duty(square1, value); break;
        case 0x64: write_square_frequency(square1, value); break;
        case 0x68: write_square_duty(square2, value); break;
        case 0x6c: write_square_frequency(square2, value); break;
        case 0x70: wave.dac_enabled = (value & 0x80) != 0; if (!wave.dac_enabled) wave.enabled = false; break;
        case 0x72: wave.length_counter = 256 - (value & 0xff); wave.volume_code = (value >> 13) & 3; break;
        case 0x74:
            wave.frequency = value & 0x7ff; wave.length_enabled = (value & 0x4000) != 0;
            if ((value & 0x8000) != 0) wave.trigger();
            break;
        case 0x78:
            noise.length_counter = 64 - (value & 0x3f); noise.envelope.write((value >> 8) & 0xff);
            if (!noise.envelope.dac_enabled()) noise.enabled = false;
            break;
        case 0x7c:
            noise.write_polynomial(value & 0xff); noise.length_enabled = (value & 0x4000) != 0;
            if ((value & 0x8000) != 0) noise.trigger();
            break;
        case 0x80: control_l = value; break;
        case 0x82:
            control_h = value;
            if ((value & 0x0800) != 0) reset_fifo(0);
            if ((value & 0x8000) != 0) reset_fifo(1);
            break;
        case 0x84:
            master_enable = (value & 0x80) != 0;
            if (!master_enable) { square1.reset(); square2.reset(); wave.reset(); noise.reset(); }
            break;
        default:
            if (offset >= 0x90 && offset <= 0x9f) {
                const auto index = static_cast<std::size_t>((offset - 0x90) * 2);
                if (index + 3 < wave.wave_ram.size()) {
                    wave.wave_ram[index] = (value >> 4) & 15;
                    wave.wave_ram[index + 1] = value & 15;
                    wave.wave_ram[index + 2] = (value >> 12) & 15;
                    wave.wave_ram[index + 3] = (value >> 8) & 15;
                }
            }
            break;
        }
    }
    std::size_t read_samples(std::span<std::int16_t> destination) {
        if (available_ == 0 && !destination.empty()) ++underruns;
        const auto count = std::min(destination.size(), available_);
        for (std::size_t i = 0; i < count; ++i) {
            destination[i] = ring_[read_index_];
            read_index_ = (read_index_ + 1U) & ring_mask;
        }
        available_ -= count;
        return count;
    }
    [[nodiscard]] int fifo_size(int channel) const noexcept { return channel == 0 ? fifo_a_.size : fifo_b_.size; }
    [[nodiscard]] int fifo_empty_reads(int channel) const noexcept { return fifo_empty_reads_[static_cast<std::size_t>(channel)]; }
    void set_measuring(bool value) noexcept { measuring_time_ = value; }
    void reset() noexcept {
        square1.reset(); square2.reset(); wave.reset(); noise.reset(); fifo_a_.clear(); fifo_b_.clear();
        direct_a_ = direct_b_ = 0; control_l = control_h = 0; master_enable = false;
        write_index_ = read_index_ = available_ = 0; underruns = 0; fifo_empty_reads_.fill(0);
        cpu_remainder_ = sample_timer_ = sequencer_timer_ = sequencer_step_ = 0;
    }
    std::function<void(int)> on_fifo_request;
    std::function<void(std::int64_t)> on_batch_nanos;
    SquareChannel square1;
    SquareChannel square2;
    WaveChannel wave;
    NoiseChannel noise;
    int control_l{};
    int control_h{};
    bool master_enable{};
    int underruns{};

private:
    struct Fifo {
        std::array<std::int8_t, 32> data{};
        int head{}; int tail{}; int size{};
        void push(std::int8_t value) noexcept {
            if (size >= 32) return;
            data[static_cast<std::size_t>(tail)] = value; tail = (tail + 1) & 31; ++size;
        }
        int pop() noexcept {
            if (size == 0) return 0;
            const auto value = data[static_cast<std::size_t>(head)]; head = (head + 1) & 31; --size;
            return value;
        }
        void clear() noexcept { head = 0; tail = 0; size = 0; }
    };
    void pop_fifo(int channel) {
        auto& fifo = channel == 0 ? fifo_a_ : fifo_b_;
        if (fifo.size == 0) ++fifo_empty_reads_[static_cast<std::size_t>(channel)];
        const auto sample = fifo.pop();
        if (channel == 0) direct_a_ = sample; else direct_b_ = sample;
        if (fifo.size <= 16 && on_fifo_request) on_fifo_request(channel);
    }
    void reset_fifo(int channel) noexcept {
        if (channel == 0) { fifo_a_.clear(); direct_a_ = 0; }
        else { fifo_b_.clear(); direct_b_ = 0; }
    }
    static void write_square_duty(SquareChannel& channel, int value) {
        channel.length_counter = 64 - (value & 0x3f); channel.duty = (value >> 6) & 3;
        channel.envelope.write((value >> 8) & 0xff);
        if (!channel.envelope.dac_enabled()) channel.enabled = false;
    }
    static void write_square_frequency(SquareChannel& channel, int value) {
        channel.frequency = value & 0x7ff; channel.length_enabled = (value & 0x4000) != 0;
        if ((value & 0x8000) != 0) channel.trigger();
    }
    void clock_sequencer() {
        if (sequencer_step_ == 0 || sequencer_step_ == 4) clock_lengths();
        else if (sequencer_step_ == 2 || sequencer_step_ == 6) { clock_lengths(); square1.clock_sweep(); }
        else if (sequencer_step_ == 7) { square1.envelope.clock(); square2.envelope.clock(); noise.envelope.clock(); }
        sequencer_step_ = (sequencer_step_ + 1) & 7;
    }
    void clock_lengths() noexcept { square1.clock_length(); square2.clock_length(); wave.clock_length(); noise.clock_length(); }
    void emit_sample() {
        if (!master_enable) { push_sample(0, 0); return; }
        const std::array outputs{square1.output(), square2.output(), wave.output(), noise.output()};
        int left{}; int right{};
        for (std::size_t i = 0; i < outputs.size(); ++i) {
            if ((control_l & (1 << (8 + static_cast<int>(i)))) != 0) left += outputs[i];
            if ((control_l & (1 << (12 + static_cast<int>(i)))) != 0) right += outputs[i];
        }
        static constexpr std::array ratio{64, 128, 256, 256};
        left = left * (((control_l >> 4) & 7) + 1) * 24 * ratio[static_cast<std::size_t>(control_h & 3)] >> 8;
        right = right * ((control_l & 7) + 1) * 24 * ratio[static_cast<std::size_t>(control_h & 3)] >> 8;
        const auto direct_a = direct_a_ * 32 * ((control_h & 4) != 0 ? 2 : 1);
        const auto direct_b = direct_b_ * 32 * ((control_h & 8) != 0 ? 2 : 1);
        if ((control_h & 0x0200) != 0) left += direct_a;
        if ((control_h & 0x0100) != 0) right += direct_a;
        if ((control_h & 0x2000) != 0) left += direct_b;
        if ((control_h & 0x1000) != 0) right += direct_b;
        push_sample(std::clamp(left, -32768, 32767), std::clamp(right, -32768, 32767));
    }
    void push_sample(int left, int right) noexcept {
        if (available_ + 2U > ring_.size()) return;
        ring_[write_index_] = static_cast<std::int16_t>(left); write_index_ = (write_index_ + 1U) & ring_mask;
        ring_[write_index_] = static_cast<std::int16_t>(right); write_index_ = (write_index_ + 1U) & ring_mask;
        available_ += 2;
    }
    static constexpr int cycles_per_sample = 128;
    static constexpr std::size_t ring_mask = 8191;
    Fifo fifo_a_;
    Fifo fifo_b_;
    std::array<int, 2> fifo_empty_reads_{};
    int direct_a_{};
    int direct_b_{};
    int cpu_remainder_{};
    int sample_timer_{};
    int sequencer_timer_{};
    int sequencer_step_{};
    std::array<std::int16_t, 8192> ring_{};
    std::size_t write_index_{};
    std::size_t read_index_{};
    std::size_t available_{};
    bool measuring_time_{};
};

class CpuState {
public:
    static constexpr int mode_user = 0x10;
    static constexpr int mode_fiq = 0x11;
    static constexpr int mode_irq = 0x12;
    static constexpr int mode_supervisor = 0x13;
    static constexpr int mode_abort = 0x17;
    static constexpr int mode_undefined = 0x1b;
    static constexpr int mode_system = 0x1f;

    void reset() noexcept {
        regs.fill(0); banked_r13_.fill(0); banked_r14_.fill(0);
        user_r8_r12_.fill(0); fiq_r8_r12_.fill(0); spsr_.fill(0);
        negative = zero = carry = overflow = false;
        irq_disabled = fiq_disabled = true;
        thumb = halted = false;
        mode = mode_supervisor;
    }
    [[nodiscard]] std::int32_t cpsr() const noexcept {
        auto result = static_cast<std::uint32_t>(mode & 0x1f);
        if (thumb) result |= 1U << 5U;
        if (fiq_disabled) result |= 1U << 6U;
        if (irq_disabled) result |= 1U << 7U;
        if (overflow) result |= 1U << 28U;
        if (carry) result |= 1U << 29U;
        if (zero) result |= 1U << 30U;
        if (negative) result |= 1U << 31U;
        return i32(result);
    }
    void set_cpsr(std::int32_t value, bool control) {
        const auto raw = u32(value);
        negative = (raw >> 31U) != 0; zero = ((raw >> 30U) & 1U) != 0;
        carry = ((raw >> 29U) & 1U) != 0; overflow = ((raw >> 28U) & 1U) != 0;
        if (control) {
            thumb = ((raw >> 5U) & 1U) != 0;
            fiq_disabled = ((raw >> 6U) & 1U) != 0;
            irq_disabled = ((raw >> 7U) & 1U) != 0;
            switch_mode(static_cast<int>(raw & 0x1fU));
        }
    }
    [[nodiscard]] std::int32_t spsr() const noexcept { return spsr_[static_cast<std::size_t>(bank_slot(mode))]; }
    void set_spsr(std::int32_t value) noexcept {
        const auto slot = bank_slot(mode);
        if (slot != 0) spsr_[static_cast<std::size_t>(slot)] = value;
    }
    [[nodiscard]] bool has_spsr() const noexcept { return bank_slot(mode) != 0; }
    void switch_mode(int next) noexcept {
        next &= 0x1f;
        if (next == mode) return;
        store_banks(mode); load_banks(next); mode = next;
    }
    std::array<std::int32_t, 28> export_banks() noexcept {
        store_banks(mode);
        std::array<std::int32_t, 28> result{};
        auto out = result.begin();
        out = std::copy(banked_r13_.begin(), banked_r13_.end(), out);
        out = std::copy(banked_r14_.begin(), banked_r14_.end(), out);
        out = std::copy(spsr_.begin(), spsr_.end(), out);
        out = std::copy(user_r8_r12_.begin(), user_r8_r12_.end(), out);
        std::copy(fiq_r8_r12_.begin(), fiq_r8_r12_.end(), out);
        return result;
    }
    void import_banks(std::span<const std::int32_t> values) {
        if (values.size() != 28) throw SaveStateError("Banques CPU GBA invalides");
        std::copy_n(values.begin(), 6, banked_r13_.begin());
        std::copy_n(values.begin() + 6, 6, banked_r14_.begin());
        std::copy_n(values.begin() + 12, 6, spsr_.begin());
        std::copy_n(values.begin() + 18, 5, user_r8_r12_.begin());
        std::copy_n(values.begin() + 23, 5, fiq_r8_r12_.begin());
    }
    void set_control_raw(std::int32_t value) noexcept {
        const auto raw = u32(value);
        negative = (raw >> 31U) != 0; zero = ((raw >> 30U) & 1U) != 0;
        carry = ((raw >> 29U) & 1U) != 0; overflow = ((raw >> 28U) & 1U) != 0;
        thumb = ((raw >> 5U) & 1U) != 0; fiq_disabled = ((raw >> 6U) & 1U) != 0;
        irq_disabled = ((raw >> 7U) & 1U) != 0; mode = static_cast<int>(raw & 0x1fU);
    }

    std::array<std::int32_t, 16> regs{};
    bool negative{}; bool zero{}; bool carry{}; bool overflow{};
    bool irq_disabled{true}; bool fiq_disabled{true}; bool thumb{}; bool halted{};
    int mode{mode_supervisor};

private:
    static int bank_slot(int value) noexcept {
        switch (value & 0x1f) {
        case mode_fiq: return 1; case mode_irq: return 2; case mode_supervisor: return 3;
        case mode_abort: return 4; case mode_undefined: return 5; default: return 0;
        }
    }
    void store_banks(int from) noexcept {
        const auto slot = static_cast<std::size_t>(bank_slot(from));
        banked_r13_[slot] = regs[13]; banked_r14_[slot] = regs[14];
        auto& values = from == mode_fiq ? fiq_r8_r12_ : user_r8_r12_;
        std::copy_n(regs.begin() + 8, 5, values.begin());
    }
    void load_banks(int to) noexcept {
        const auto slot = static_cast<std::size_t>(bank_slot(to));
        regs[13] = banked_r13_[slot]; regs[14] = banked_r14_[slot];
        const auto& values = to == mode_fiq ? fiq_r8_r12_ : user_r8_r12_;
        std::copy(values.begin(), values.end(), regs.begin() + 8);
    }
    std::array<std::int32_t, 6> banked_r13_{};
    std::array<std::int32_t, 6> banked_r14_{};
    std::array<std::int32_t, 5> user_r8_r12_{};
    std::array<std::int32_t, 5> fiq_r8_r12_{};
    std::array<std::int32_t, 6> spsr_{};
};

class Cpu {
public:
    explicit Cpu(Bus& bus) : bus(bus) {}
    void reset(std::int32_t entry) {
        state.reset();
        state.switch_mode(CpuState::mode_supervisor); state.regs[13] = i32(0x03007fe0U);
        state.switch_mode(CpuState::mode_irq); state.regs[13] = i32(0x03007fa0U);
        state.switch_mode(CpuState::mode_system); state.regs[13] = i32(0x03007f00U);
        state.thumb = false; state.irq_disabled = false; state.regs[15] = entry;
    }
    [[nodiscard]] std::int32_t read_reg(int index) const noexcept {
        if (index != 15) return state.regs[static_cast<std::size_t>(index)];
        return add32(state.regs[15], state.thumb ? 4 : 8);
    }
    void write_reg(int index, std::int32_t value) noexcept {
        if (index == 15) branch_to(value); else state.regs[static_cast<std::size_t>(index)] = value;
    }
    void branch_to(std::int32_t address) noexcept {
        state.regs[15] = i32(u32(address) & (state.thumb ? ~1U : ~3U)); branched_ = true;
    }
    void branch_exchange(std::int32_t address) noexcept {
        state.thumb = (u32(address) & 1U) != 0;
        state.regs[15] = i32(u32(address) & (state.thumb ? ~1U : ~3U)); branched_ = true;
    }
    void execute_swi(int number, std::int32_t return_address) {
        if (swi_handler) swi_handler(number);
        else raise_exception(CpuState::mode_supervisor, 0x08, return_address);
    }
    void raise_exception(int mode, std::int32_t vector, std::int32_t return_address) {
        const auto saved = state.cpsr();
        state.switch_mode(mode); state.set_spsr(saved); state.regs[14] = return_address;
        state.irq_disabled = true; state.thumb = false; state.regs[15] = vector; branched_ = true;
    }
    int step() {
        branched_ = false; bus.take_wait_cycles();
        int cost{};
        if (state.thumb) {
            const auto instruction = bus.fetch16(state.regs[15]) & 0xffff;
            cost = execute_thumb(instruction);
            if (!branched_) state.regs[15] = add32(state.regs[15], 2);
        } else {
            const auto instruction = bus.fetch32(state.regs[15]);
            cost = check_condition(static_cast<int>(u32(instruction) >> 28U)) ? execute_arm(instruction) : 1;
            if (!branched_) state.regs[15] = add32(state.regs[15], 4);
        }
        if (branched_) bus.break_access_sequence();
        return cost + bus.take_wait_cycles();
    }
    [[nodiscard]] bool check_condition(int condition) const noexcept {
        switch (condition & 15) {
        case 0: return state.zero; case 1: return !state.zero; case 2: return state.carry;
        case 3: return !state.carry; case 4: return state.negative; case 5: return !state.negative;
        case 6: return state.overflow; case 7: return !state.overflow;
        case 8: return state.carry && !state.zero; case 9: return !state.carry || state.zero;
        case 10: return state.negative == state.overflow; case 11: return state.negative != state.overflow;
        case 12: return !state.zero && state.negative == state.overflow;
        case 13: return state.zero || state.negative != state.overflow; case 14: return true;
        default: return false;
        }
    }
    std::int32_t shift_immediate(std::int32_t value, int type, int amount) noexcept {
        const auto raw = u32(value);
        switch (type & 3) {
        case 0:
            if (amount == 0) { shifter_carry = state.carry; return value; }
            shifter_carry = ((raw >> (32U - static_cast<unsigned>(amount))) & 1U) != 0;
            return i32(raw << static_cast<unsigned>(amount));
        case 1: {
            const auto n = amount == 0 ? 32U : static_cast<unsigned>(amount);
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0;
            return n == 32U ? 0 : i32(raw >> n);
        }
        case 2: {
            const auto n = amount == 0 ? 32U : static_cast<unsigned>(amount);
            if (n >= 32U) { shifter_carry = value < 0; return value < 0 ? -1 : 0; }
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0;
            return value >> n;
        }
        default:
            if (amount == 0) {
                shifter_carry = (raw & 1U) != 0;
                return i32((raw >> 1U) | (state.carry ? 0x80000000U : 0U));
            }
            shifter_carry = ((raw >> (static_cast<unsigned>(amount) - 1U)) & 1U) != 0;
            return i32(std::rotr(raw, amount));
        }
    }
    std::int32_t shift_register(std::int32_t value, int type, int amount) noexcept {
        const auto n = static_cast<unsigned>(amount) & 0xffU;
        const auto raw = u32(value);
        if (n == 0) { shifter_carry = state.carry; return value; }
        switch (type & 3) {
        case 0:
            if (n < 32U) { shifter_carry = ((raw >> (32U - n)) & 1U) != 0; return i32(raw << n); }
            shifter_carry = n == 32U && (raw & 1U) != 0; return 0;
        case 1:
            if (n < 32U) { shifter_carry = ((raw >> (n - 1U)) & 1U) != 0; return i32(raw >> n); }
            shifter_carry = n == 32U && value < 0; return 0;
        case 2:
            if (n >= 32U) { shifter_carry = value < 0; return value < 0 ? -1 : 0; }
            shifter_carry = ((raw >> (n - 1U)) & 1U) != 0; return value >> n;
        default: {
            const auto rotate = n & 31U;
            if (rotate == 0) { shifter_carry = value < 0; return value; }
            shifter_carry = ((raw >> (rotate - 1U)) & 1U) != 0;
            return i32(std::rotr(raw, static_cast<int>(rotate)));
        }
        }
    }
    std::int32_t add_with_carry(std::int32_t a, std::int32_t b, int carry_in) noexcept {
        const auto sum = static_cast<std::uint64_t>(u32(a)) + u32(b) + static_cast<unsigned>(carry_in);
        const auto result = i32(static_cast<std::uint32_t>(sum));
        last_carry = sum > 0xffff'ffffULL;
        last_overflow = ((a ^ result) & (b ^ result)) < 0;
        return result;
    }
    void set_nz(std::int32_t value) noexcept { state.negative = value < 0; state.zero = value == 0; }
    std::int32_t load_word_rotated(std::int32_t address) {
        const auto word = bus.read32(i32(u32(address) & ~3U));
        return i32(std::rotr(u32(word), static_cast<int>((u32(address) & 3U) * 8U)));
    }

    Bus& bus;
    CpuState state;
    std::function<void(int)> swi_handler;
    bool shifter_carry{};
    bool last_carry{};
    bool last_overflow{};

private:
    int execute_arm(std::int32_t instruction);
    int arm_undefined(std::int32_t instruction);
    int arm_branch(std::int32_t instruction);
    int arm_data_or_psr(std::int32_t instruction);
    int arm_data(std::int32_t instruction, bool immediate, int opcode, bool set_flags);
    std::int32_t arm_operand2(std::int32_t instruction, bool immediate, int pc_extra);
    int arm_psr(std::int32_t instruction);
    int arm_single_transfer(std::int32_t instruction);
    int arm_multiply(std::int32_t instruction);
    int arm_multiply_long(std::int32_t instruction);
    int arm_swap(std::int32_t instruction);
    int arm_halfword(std::int32_t instruction);
    int arm_block(std::int32_t instruction);
    int arm_swi(std::int32_t instruction);
    int execute_thumb(int instruction);
    int thumb_undefined(int instruction);
    void thumb_arithmetic_flags(std::int32_t value) noexcept;
    bool branched_{};
};

int Cpu::execute_arm(std::int32_t instruction) {
    const auto raw = u32(instruction);
    if ((raw & 0x0fff'fff0U) == 0x012f'ff10U) { branch_exchange(read_reg(static_cast<int>(raw & 15U))); return 3; }
    switch ((raw >> 25U) & 7U) {
    case 0:
        if ((raw & 0x0fb0'0ff0U) == 0x0100'0090U) return arm_swap(instruction);
        if ((raw & 0x0fc0'00f0U) == 0x0000'0090U) return arm_multiply(instruction);
        if ((raw & 0x0f80'00f0U) == 0x0080'0090U) return arm_multiply_long(instruction);
        if ((raw & 0x0e00'0090U) == 0x0000'0090U && (raw & 0x60U) != 0) return arm_halfword(instruction);
        return arm_data_or_psr(instruction);
    case 1: return arm_data_or_psr(instruction);
    case 2: case 3: return arm_single_transfer(instruction);
    case 4: return arm_block(instruction);
    case 5: return arm_branch(instruction);
    case 7: return ((raw >> 24U) & 1U) != 0 ? arm_swi(instruction) : arm_undefined(instruction);
    default: return arm_undefined(instruction);
    }
}

int Cpu::arm_undefined(std::int32_t instruction) {
    bus.diagnostics.report(DiagnosticEvent::undefined_instruction,
        "instruction ARM indéfinie " + std::to_string(u32(instruction)));
    return 1;
}

int Cpu::arm_branch(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto offset = sign_extend((raw & 0x00ff'ffffU) << 2U, 26);
    if ((raw & 0x0100'0000U) != 0) write_reg(14, add32(state.regs[15], 4));
    branch_to(add32(read_reg(15), offset));
    return 3;
}

int Cpu::arm_data_or_psr(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto immediate = ((raw >> 25U) & 1U) != 0;
    const auto opcode = static_cast<int>((raw >> 21U) & 15U);
    const auto set_flags = ((raw >> 20U) & 1U) != 0;
    if (!set_flags && opcode >= 8 && opcode <= 11) return arm_psr(instruction);
    return arm_data(instruction, immediate, opcode, set_flags);
}

int Cpu::arm_data(std::int32_t instruction, bool immediate, int opcode, bool set_flags) {
    const auto raw = u32(instruction);
    const auto rn = static_cast<int>((raw >> 16U) & 15U);
    const auto rd = static_cast<int>((raw >> 12U) & 15U);
    const auto register_shift = !immediate && ((raw >> 4U) & 1U) != 0;
    const auto pc_extra = register_shift ? 4 : 0;
    const auto a = add32(read_reg(rn), rn == 15 ? pc_extra : 0);
    const auto b = arm_operand2(instruction, immediate, pc_extra);
    bool arithmetic{};
    std::int32_t result{};
    switch (opcode) {
    case 0: result = a & b; break; case 1: result = a ^ b; break;
    case 2: arithmetic = true; result = add_with_carry(a, ~b, 1); break;
    case 3: arithmetic = true; result = add_with_carry(b, ~a, 1); break;
    case 4: arithmetic = true; result = add_with_carry(a, b, 0); break;
    case 5: arithmetic = true; result = add_with_carry(a, b, state.carry ? 1 : 0); break;
    case 6: arithmetic = true; result = add_with_carry(a, ~b, state.carry ? 1 : 0); break;
    case 7: arithmetic = true; result = add_with_carry(b, ~a, state.carry ? 1 : 0); break;
    case 8: result = a & b; break; case 9: result = a ^ b; break;
    case 10: arithmetic = true; result = add_with_carry(a, ~b, 1); break;
    case 11: arithmetic = true; result = add_with_carry(a, b, 0); break;
    case 12: result = a | b; break; case 13: result = b; break;
    case 14: result = a & ~b; break; default: result = ~b; break;
    }
    const auto writes = opcode < 8 || opcode > 11;
    if (set_flags) {
        if (rd == 15 && writes) { if (state.has_spsr()) state.set_cpsr(state.spsr(), true); }
        else { set_nz(result); state.carry = arithmetic ? last_carry : shifter_carry; if (arithmetic) state.overflow = last_overflow; }
    }
    if (writes) write_reg(rd, result);
    return rd == 15 && writes ? 3 : 1;
}

std::int32_t Cpu::arm_operand2(std::int32_t instruction, bool immediate, int pc_extra) {
    const auto raw = u32(instruction);
    if (immediate) {
        const auto value = raw & 0xffU;
        const auto rotate = static_cast<int>(((raw >> 8U) & 15U) * 2U);
        if (rotate == 0) { shifter_carry = state.carry; return i32(value); }
        const auto result = std::rotr(value, rotate);
        shifter_carry = (result & 0x8000'0000U) != 0;
        return i32(result);
    }
    const auto rm = static_cast<int>(raw & 15U);
    const auto type = static_cast<int>((raw >> 5U) & 3U);
    if (((raw >> 4U) & 1U) != 0) {
        const auto rs = static_cast<int>((raw >> 8U) & 15U);
        return shift_register(add32(read_reg(rm), rm == 15 ? pc_extra : 0), type, read_reg(rs) & 0xff);
    }
    return shift_immediate(read_reg(rm), type, static_cast<int>((raw >> 7U) & 31U));
}

int Cpu::arm_psr(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto use_spsr = ((raw >> 22U) & 1U) != 0;
    if (((raw >> 21U) & 1U) == 0) {
        write_reg(static_cast<int>((raw >> 12U) & 15U), use_spsr ? state.spsr() : state.cpsr());
        return 1;
    }
    std::int32_t operand{};
    if (((raw >> 25U) & 1U) != 0) {
        operand = i32(std::rotr(raw & 0xffU, static_cast<int>(((raw >> 8U) & 15U) * 2U)));
    } else operand = read_reg(static_cast<int>(raw & 15U));
    const auto fields = static_cast<int>((raw >> 16U) & 15U);
    std::uint32_t mask{};
    if ((fields & 1) != 0) mask |= 0x000000ffU;
    if ((fields & 2) != 0) mask |= 0x0000ff00U;
    if ((fields & 4) != 0) mask |= 0x00ff0000U;
    if ((fields & 8) != 0) mask |= 0xff000000U;
    if (use_spsr) {
        if (state.has_spsr()) state.set_spsr(i32((u32(state.spsr()) & ~mask) | (u32(operand) & mask)));
    } else {
        if (state.mode == CpuState::mode_user) mask &= 0xff000000U;
        state.set_cpsr(i32((u32(state.cpsr()) & ~mask) | (u32(operand) & mask)), (mask & 0xffU) != 0);
    }
    return 1;
}

int Cpu::arm_single_transfer(std::int32_t instruction) {
    const auto raw = u32(instruction);
    const auto register_offset = ((raw >> 25U) & 1U) != 0;
    const auto pre = ((raw >> 24U) & 1U) != 0; const auto add = ((raw >> 23U) & 1U) != 0;
    const auto byte = ((raw >> 22U) & 1U) != 0; const auto write_back = ((raw >> 21U) & 1U) != 0;
    const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto rd = static_cast<int>((raw >> 12U) & 15U);
    auto offset = i32(raw & 0xfffU);
    if (register_offset) offset = shift_immediate(read_reg(static_cast<int>(raw & 15U)),
        static_cast<int>((raw >> 5U) & 3U), static_cast<int>((raw >> 7U) & 31U));
    const auto base = read_reg(rn); const auto delta = add ? offset : i32(0U - u32(offset));
    const auto address = pre ? add32(base, delta) : base; const auto new_base = add32(base, delta);
    if (load) {
        const auto value = byte ? i32(static_cast<std::uint32_t>(bus.read8(address))) : load_word_rotated(address);
        if ((!pre || write_back) && rn != rd) write_reg(rn, new_base);
        write_reg(rd, value);
    } else {
        if (byte) bus.write8(address, read_reg(rd) & 0xff); else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        if (!pre || write_back) write_reg(rn, new_base);
    }
    return 2;
}

int Cpu::arm_multiply(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto rd = static_cast<int>((raw >> 16U) & 15U);
    auto result = i32(u32(read_reg(static_cast<int>(raw & 15U))) * u32(read_reg(static_cast<int>((raw >> 8U) & 15U))));
    if (((raw >> 21U) & 1U) != 0) result = add32(result, read_reg(static_cast<int>((raw >> 12U) & 15U)));
    write_reg(rd, result); if (((raw >> 20U) & 1U) != 0) set_nz(result); return 2;
}

int Cpu::arm_multiply_long(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto hi = static_cast<int>((raw >> 16U) & 15U);
    const auto lo = static_cast<int>((raw >> 12U) & 15U); const auto rs = static_cast<int>((raw >> 8U) & 15U);
    const auto rm = static_cast<int>(raw & 15U); const auto signed_multiply = ((raw >> 22U) & 1U) != 0;
    std::uint64_t product{};
    if (signed_multiply) product = static_cast<std::uint64_t>(static_cast<std::int64_t>(read_reg(rm)) * read_reg(rs));
    else product = static_cast<std::uint64_t>(u32(read_reg(rm))) * u32(read_reg(rs));
    if (((raw >> 21U) & 1U) != 0) product += (static_cast<std::uint64_t>(u32(read_reg(hi))) << 32U) | u32(read_reg(lo));
    write_reg(lo, i32(static_cast<std::uint32_t>(product))); write_reg(hi, i32(static_cast<std::uint32_t>(product >> 32U)));
    if (((raw >> 20U) & 1U) != 0) { state.negative = (product >> 63U) != 0; state.zero = product == 0; }
    return 3;
}

int Cpu::arm_swap(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto address = read_reg(static_cast<int>((raw >> 16U) & 15U));
    const auto rd = static_cast<int>((raw >> 12U) & 15U); const auto source = read_reg(static_cast<int>(raw & 15U));
    if (((raw >> 22U) & 1U) != 0) { const auto old = bus.read8(address); bus.write8(address, source); write_reg(rd, old); }
    else { const auto old = load_word_rotated(address); bus.write32(i32(u32(address) & ~3U), source); write_reg(rd, old); }
    return 4;
}

int Cpu::arm_halfword(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto pre = ((raw >> 24U) & 1U) != 0;
    const auto add = ((raw >> 23U) & 1U) != 0; const auto immediate = ((raw >> 22U) & 1U) != 0;
    const auto write_back = ((raw >> 21U) & 1U) != 0; const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto rd = static_cast<int>((raw >> 12U) & 15U);
    const auto sh = static_cast<int>((raw >> 5U) & 3U);
    const auto offset = immediate ? i32(((raw >> 8U) & 15U) << 4U | (raw & 15U)) : read_reg(static_cast<int>(raw & 15U));
    const auto base = read_reg(rn); const auto delta = add ? offset : i32(0U - u32(offset));
    const auto address = pre ? add32(base, delta) : base; const auto new_base = add32(base, delta);
    if (load) {
        std::int32_t value{};
        if (sh == 1) value = bus.read16(address) & 0xffff;
        else if (sh == 2) value = sign_extend(static_cast<unsigned>(bus.read8(address)), 8);
        else value = sign_extend(static_cast<unsigned>(bus.read16(address)), 16);
        if ((!pre || write_back) && rn != rd) write_reg(rn, new_base);
        write_reg(rd, value);
    } else { bus.write16(address, read_reg(rd)); if (!pre || write_back) write_reg(rn, new_base); }
    return 2;
}

int Cpu::arm_block(std::int32_t instruction) {
    const auto raw = u32(instruction); const auto pre = ((raw >> 24U) & 1U) != 0;
    const auto add = ((raw >> 23U) & 1U) != 0; const auto psr_user = ((raw >> 22U) & 1U) != 0;
    const auto write_back = ((raw >> 21U) & 1U) != 0; const auto load = ((raw >> 20U) & 1U) != 0;
    const auto rn = static_cast<int>((raw >> 16U) & 15U); const auto list = raw & 0xffffU;
    const auto count = std::popcount(list); if (count == 0) return 2;
    const auto byte_count = static_cast<std::int32_t>(count) * 4;
    const auto base = read_reg(rn); std::int32_t address{};
    if (add) address = add32(base, pre ? 4 : 0);
    else address = add32(base, -byte_count + (pre ? 0 : 4));
    const auto write_value = add32(base, add ? byte_count : -byte_count);
    for (int reg = 0; reg < 16; ++reg) if ((list & (1U << static_cast<unsigned>(reg))) != 0) {
        if (load) write_reg(reg, bus.read32(address)); else bus.write32(address, read_reg(reg)); address = add32(address, 4);
    }
    if (write_back && !(load && (list & (1U << static_cast<unsigned>(rn))) != 0)) write_reg(rn, write_value);
    if (load && (list & 0x8000U) != 0 && psr_user && state.has_spsr()) state.set_cpsr(state.spsr(), true);
    return static_cast<int>(count) + 2;
}

int Cpu::arm_swi(std::int32_t instruction) {
    execute_swi(static_cast<int>((u32(instruction) >> 16U) & 0xffU), add32(state.regs[15], 4)); return 3;
}

void Cpu::thumb_arithmetic_flags(std::int32_t value) noexcept {
    set_nz(value); state.carry = last_carry; state.overflow = last_overflow;
}

int Cpu::thumb_undefined(int instruction) {
    bus.diagnostics.report(DiagnosticEvent::undefined_instruction,
        "instruction Thumb indéfinie " + std::to_string(instruction)); return 1;
}

int Cpu::execute_thumb(int instruction) {
    const auto instr = static_cast<unsigned>(instruction) & 0xffffU;
    if ((instr & 0xf800U) == 0x1800U) {
        const auto immediate = ((instr >> 10U) & 1U) != 0; const auto subtract = ((instr >> 9U) & 1U) != 0;
        const auto operand = static_cast<int>((instr >> 6U) & 7U); const auto rs = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto b = immediate ? operand : read_reg(operand);
        const auto result = subtract ? add_with_carry(read_reg(rs), ~b, 1) : add_with_carry(read_reg(rs), b, 0);
        write_reg(rd, result); thumb_arithmetic_flags(result); return 1;
    }
    if ((instr & 0xe000U) == 0) {
        const auto type = static_cast<int>((instr >> 11U) & 3U); const auto amount = static_cast<int>((instr >> 6U) & 31U);
        const auto rs = static_cast<int>((instr >> 3U) & 7U); const auto rd = static_cast<int>(instr & 7U);
        const auto result = shift_immediate(read_reg(rs), type, amount); write_reg(rd, result); set_nz(result); state.carry = shifter_carry; return 1;
    }
    if ((instr & 0xe000U) == 0x2000U) {
        const auto opcode = static_cast<int>((instr >> 11U) & 3U); const auto rd = static_cast<int>((instr >> 8U) & 7U);
        const auto value = i32(instr & 0xffU); std::int32_t result{};
        if (opcode == 0) { write_reg(rd, value); set_nz(value); }
        else if (opcode == 1) { result = add_with_carry(read_reg(rd), ~value, 1); thumb_arithmetic_flags(result); }
        else if (opcode == 2) { result = add_with_carry(read_reg(rd), value, 0); write_reg(rd, result); thumb_arithmetic_flags(result); }
        else { result = add_with_carry(read_reg(rd), ~value, 1); write_reg(rd, result); thumb_arithmetic_flags(result); }
        return 1;
    }
    if ((instr & 0xfc00U) == 0x4000U) {
        const auto opcode = static_cast<int>((instr >> 6U) & 15U); const auto rs = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto a = read_reg(rd); const auto b = read_reg(rs);
        std::int32_t result{}; bool write = true; bool arithmetic = false; bool shift = false;
        switch (opcode) {
        case 0: result = a & b; break; case 1: result = a ^ b; break;
        case 2: result = shift_register(a, 0, b); shift = true; break; case 3: result = shift_register(a, 1, b); shift = true; break;
        case 4: result = shift_register(a, 2, b); shift = true; break;
        case 5: result = add_with_carry(a, b, state.carry ? 1 : 0); arithmetic = true; break;
        case 6: result = add_with_carry(a, ~b, state.carry ? 1 : 0); arithmetic = true; break;
        case 7: result = shift_register(a, 3, b); shift = true; break;
        case 8: result = a & b; write = false; break; case 9: result = add_with_carry(0, ~b, 1); arithmetic = true; break;
        case 10: result = add_with_carry(a, ~b, 1); arithmetic = true; write = false; break;
        case 11: result = add_with_carry(a, b, 0); arithmetic = true; write = false; break;
        case 12: result = a | b; break; case 13: result = i32(u32(a) * u32(b)); break;
        case 14: result = a & ~b; break; default: result = ~b; break;
        }
        if (write) write_reg(rd, result);
        set_nz(result);
        if (arithmetic) { state.carry = last_carry; state.overflow = last_overflow; }
        else if (shift) state.carry = shifter_carry;
        return 1;
    }
    if ((instr & 0xfc00U) == 0x4400U) {
        const auto opcode = static_cast<int>((instr >> 8U) & 3U);
        const auto rd = static_cast<int>((instr & 7U) | (((instr >> 7U) & 1U) << 3U));
        const auto rs = static_cast<int>(((instr >> 3U) & 7U) | (((instr >> 6U) & 1U) << 3U));
        if (opcode == 0) write_reg(rd, add32(read_reg(rd), read_reg(rs)));
        else if (opcode == 1) thumb_arithmetic_flags(add_with_carry(read_reg(rd), ~read_reg(rs), 1));
        else if (opcode == 2) write_reg(rd, read_reg(rs)); else branch_exchange(read_reg(rs));
        return opcode != 1 && rd == 15 ? 3 : 1;
    }
    if ((instr & 0xf800U) == 0x4800U) {
        const auto rd = static_cast<int>((instr >> 8U) & 7U);
        write_reg(rd, bus.read32(add32(i32(u32(read_reg(15)) & ~2U), i32((instr & 0xffU) << 2U)))); return 3;
    }
    if ((instr & 0xf200U) == 0x5000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto byte = ((instr >> 10U) & 1U) != 0;
        const auto address = add32(read_reg(static_cast<int>((instr >> 3U) & 7U)), read_reg(static_cast<int>((instr >> 6U) & 7U)));
        const auto rd = static_cast<int>(instr & 7U);
        if (load) write_reg(rd, byte ? bus.read8(address) : load_word_rotated(address));
        else if (byte) bus.write8(address, read_reg(rd));
        else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        return 2;
    }
    if ((instr & 0xf200U) == 0x5200U) {
        const auto h = ((instr >> 11U) & 1U) != 0; const auto s = ((instr >> 10U) & 1U) != 0;
        const auto address = add32(read_reg(static_cast<int>((instr >> 3U) & 7U)), read_reg(static_cast<int>((instr >> 6U) & 7U)));
        const auto rd = static_cast<int>(instr & 7U);
        if (!s && !h) bus.write16(address, read_reg(rd)); else if (!s) write_reg(rd, bus.read16(address));
        else if (!h) write_reg(rd, sign_extend(static_cast<unsigned>(bus.read8(address)), 8));
        else write_reg(rd, sign_extend(static_cast<unsigned>(bus.read16(address)), 16));
        return 2;
    }
    if ((instr & 0xe000U) == 0x6000U) {
        const auto byte = ((instr >> 12U) & 1U) != 0; const auto load = ((instr >> 11U) & 1U) != 0;
        const auto offset = static_cast<int>((instr >> 6U) & 31U); const auto rb = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto address = add32(read_reg(rb), byte ? offset : offset << 2);
        if (load) write_reg(rd, byte ? bus.read8(address) : load_word_rotated(address));
        else if (byte) bus.write8(address, read_reg(rd));
        else bus.write32(i32(u32(address) & ~3U), read_reg(rd));
        return 2;
    }
    if ((instr & 0xf000U) == 0x8000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rb = static_cast<int>((instr >> 3U) & 7U);
        const auto rd = static_cast<int>(instr & 7U); const auto address = add32(read_reg(rb), static_cast<int>((instr >> 5U) & 0x3eU));
        if (load) write_reg(rd, bus.read16(address)); else bus.write16(address, read_reg(rd)); return 2;
    }
    if ((instr & 0xf000U) == 0x9000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rd = static_cast<int>((instr >> 8U) & 7U);
        const auto address = add32(read_reg(13), static_cast<int>((instr & 0xffU) << 2U));
        if (load) write_reg(rd, load_word_rotated(address)); else bus.write32(i32(u32(address) & ~3U), read_reg(rd)); return 2;
    }
    if ((instr & 0xf000U) == 0xa000U) {
        const auto rd = static_cast<int>((instr >> 8U) & 7U); const auto base = ((instr >> 11U) & 1U) != 0
            ? read_reg(13) : i32(u32(read_reg(15)) & ~2U);
        write_reg(rd, add32(base, static_cast<int>((instr & 0xffU) << 2U))); return 1;
    }
    if ((instr & 0xff00U) == 0xb000U) {
        const auto offset = static_cast<std::int32_t>((instr & 0x7fU) << 2U);
        write_reg(13, add32(read_reg(13), ((instr >> 7U) & 1U) != 0 ? -offset : offset)); return 1;
    }
    if ((instr & 0xf600U) == 0xb400U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto extra = ((instr >> 8U) & 1U) != 0;
        const auto list = instr & 0xffU; const auto count = static_cast<int>(std::popcount(list)) + (extra ? 1 : 0);
        if (load) {
            auto address = read_reg(13); for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
                write_reg(reg, bus.read32(address)); address = add32(address, 4);
            }
            if (extra) { write_reg(15, bus.read32(address)); address = add32(address, 4); } write_reg(13, address);
        } else {
            auto address = add32(read_reg(13), -count * 4); write_reg(13, address);
            for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
                bus.write32(address, read_reg(reg)); address = add32(address, 4);
            }
            if (extra) bus.write32(address, read_reg(14));
        }
        return count + 1;
    }
    if ((instr & 0xf000U) == 0xc000U) {
        const auto load = ((instr >> 11U) & 1U) != 0; const auto rb = static_cast<int>((instr >> 8U) & 7U);
        const auto list = instr & 0xffU; if (list == 0) return 1; auto address = read_reg(rb);
        for (int reg = 0; reg < 8; ++reg) if ((list & (1U << reg)) != 0) {
            if (load) write_reg(reg, bus.read32(address)); else bus.write32(address, read_reg(reg)); address = add32(address, 4);
        }
        if (!(load && (list & (1U << rb)) != 0)) write_reg(rb, address);
        return static_cast<int>(std::popcount(list)) + 1;
    }
    if ((instr & 0xff00U) == 0xdf00U) { execute_swi(static_cast<int>(instr & 0xffU), add32(state.regs[15], 2)); return 3; }
    if ((instr & 0xf000U) == 0xd000U) {
        const auto condition = static_cast<int>((instr >> 8U) & 15U); if (condition >= 14 || !check_condition(condition)) return 1;
        branch_to(add32(read_reg(15), sign_extend((instr & 0xffU) << 1U, 9))); return 3;
    }
    if ((instr & 0xf800U) == 0xe000U) { branch_to(add32(read_reg(15), sign_extend((instr & 0x7ffU) << 1U, 12))); return 3; }
    if ((instr & 0xf800U) == 0xf000U) { write_reg(14, add32(read_reg(15), sign_extend((instr & 0x7ffU) << 12U, 23))); return 1; }
    if ((instr & 0xf800U) == 0xf800U) {
        const auto target = add32(read_reg(14), static_cast<int>((instr & 0x7ffU) << 1U));
        write_reg(14, i32(u32(add32(state.regs[15], 2)) | 1U)); branch_to(target); return 3;
    }
    return thumb_undefined(instruction);
}

Bus::Bus(Cartridge& value) : cartridge(value) {
    reset_affine_matrices();
}

void Bus::account(std::int32_t address, int width, bool fetch) noexcept {
    const auto sequential = address == next_sequential_address;
    wait_cycles += fetch ? timing.instruction_wait(address, width, sequential)
                         : timing.wait_states(address, width, sequential);
    next_sequential_address = add32(address, width);
}

bool Bus::eeprom_window(std::int32_t address) const noexcept {
    return dynamic_cast<const Eeprom*>(cartridge.save()) != nullptr && (u32(address) >> 24U) == 0x0dU;
}

bool Bus::gpio_register(std::int32_t address) const noexcept {
    const auto region = (u32(address) >> 24U) & 0xffU;
    return region >= 0x08U && region <= 0x0dU && Gpio::covers(rom_offset(address));
}

int Bus::read_io(int offset) {
    if (offset == 0x130) return keypad.input() & 0xff;
    if (offset == 0x131) return (keypad.input() >> 8) & 0xff;
    if (offset == 0x004 && ppu) return ppu_dispstat_low(ppu);
    if (offset == 0x006 && ppu) return ppu_vcount(ppu);
    if (offset == 0x007) return 0;
    if (timers && offset >= 0x100 && offset <= 0x10f) return read_timer_byte(offset);
    if (interrupts) {
        if (offset == 0x200) return interrupts->enable & 0xff;
        if (offset == 0x201) return (interrupts->enable >> 8) & 0xff;
        if (offset == 0x202) return interrupts->flags & 0xff;
        if (offset == 0x203) return (interrupts->flags >> 8) & 0xff;
        if (offset == 0x208) return interrupts->master_enable ? 1 : 0;
        if (offset == 0x209) return 0;
    }
    return io[static_cast<std::size_t>(offset)];
}

int Bus::read_timer_byte(int offset) {
    const auto timer = (offset - 0x100) / 4;
    const auto byte = (offset - 0x100) % 4;
    const auto value = byte < 2 ? timers->counter(timer) : timers->control(timer);
    return byte % 2 == 0 ? value & 0xff : (value >> 8) & 0xff;
}

int Bus::read8_raw(std::int32_t address) {
    const auto raw = u32(address);
    const auto region = (raw >> 24U) & 0xffU;
    switch (region) {
    case 0x00: case 0x01: return bios[raw & 0x3fffU];
    case 0x02: return ewram[raw & 0x3ffffU];
    case 0x03: return iwram[raw & 0x7fffU];
    case 0x04: return read_io(static_cast<int>(raw & 0x3ffU));
    case 0x05: return palette[raw & 0x3ffU];
    case 0x06: return vram[static_cast<std::size_t>(vram_offset(address))];
    case 0x07: return oam[raw & 0x3ffU];
    case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d: {
        auto* port = cartridge.gpio();
        if (port && port->readable() && gpio_register(address)) {
            const auto offset = rom_offset(address);
            const auto half = port->read16(offset & ~1);
            return (offset & 1) == 0 ? half & 0xff : (half >> 8) & 0xff;
        }
        return cartridge.read8(rom_offset(address));
    }
    case 0x0e: case 0x0f: {
        auto* save = cartridge.save();
        if (dynamic_cast<Sram*>(save) || dynamic_cast<Flash*>(save)) return save->read(static_cast<int>(raw));
        return fallback_sram[raw & 0xffffU];
    }
    default:
        diagnostics.unsupported_access(address);
        return 0;
    }
}

int Bus::read8(std::int32_t address) {
    account(address, 1);
    return read8_raw(address);
}

int Bus::read16(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2);
    if (auto* memory = eeprom(); memory && eeprom_window(aligned)) return memory->read(0);
    if (auto* port = cartridge.gpio(); port && port->readable() && gpio_register(aligned)) {
        return port->read16(rom_offset(aligned));
    }
    return read8_raw(aligned) | read8_raw(add32(aligned, 1)) << 8;
}

std::int32_t Bus::read32(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4);
    auto result = static_cast<std::uint32_t>(read8_raw(aligned));
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 1))) << 8U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 2))) << 16U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 3))) << 24U;
    return i32(result);
}

int Bus::fetch16(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2, true);
    return read8_raw(aligned) | read8_raw(add32(aligned, 1)) << 8;
}

std::int32_t Bus::fetch32(std::int32_t address) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4, true);
    auto result = static_cast<std::uint32_t>(read8_raw(aligned));
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 1))) << 8U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 2))) << 16U;
    result |= static_cast<std::uint32_t>(read8_raw(add32(aligned, 3))) << 24U;
    return i32(result);
}

void Bus::write8_raw(std::int32_t address, int value) {
    const auto raw = u32(address);
    const auto region = (raw >> 24U) & 0xffU;
    const auto byte = static_cast<std::uint8_t>(value);
    switch (region) {
    case 0x00: case 0x01: break;
    case 0x02: ewram[raw & 0x3ffffU] = byte; break;
    case 0x03: iwram[raw & 0x7fffU] = byte; break;
    case 0x04: io[raw & 0x3ffU] = byte; break;
    case 0x05: palette[raw & 0x3ffU] = byte; break;
    case 0x06: vram[static_cast<std::size_t>(vram_offset(address))] = byte; break;
    case 0x07: oam[raw & 0x3ffU] = byte; break;
    case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d:
        if (auto* port = cartridge.gpio(); port && gpio_register(address) && (rom_offset(address) & 1) == 0) {
            port->write16(rom_offset(address), value);
        }
        break;
    case 0x0e: case 0x0f:
        if (auto* save = cartridge.save(); dynamic_cast<Sram*>(save) || dynamic_cast<Flash*>(save)) save->write(static_cast<int>(raw), value);
        else fallback_sram[raw & 0xffffU] = byte;
        break;
    default: diagnostics.unsupported_access(address); break;
    }
}

void Bus::write8(std::int32_t address, int value) {
    account(address, 1);
    const auto region = (u32(address) >> 24U) & 0xffU;
    if (region == 0x05U || region == 0x06U) {
        const auto aligned = i32(u32(address) & ~1U);
        write8_raw(aligned, value); write8_raw(add32(aligned, 1), value);
    } else if (region == 0x07U) {
        return;
    } else {
        write8_raw(address, value);
        if (region == 0x04U) propagate_io_write(static_cast<int>(u32(address) & 0x3ffU), value, 1);
    }
}

void Bus::write16_raw(std::int32_t address, int value) {
    if (auto* memory = eeprom(); memory && eeprom_window(address)) { memory->write(0, value); return; }
    if (auto* port = cartridge.gpio(); port && gpio_register(address)) {
        port->write16(rom_offset(address), value & 0xffff); return;
    }
    write8_raw(address, value); write8_raw(add32(address, 1), value >> 8);
    if ((u32(address) >> 24U) == 0x04U) propagate_io_write(static_cast<int>(u32(address) & 0x3ffU), value, 2);
}

void Bus::write16(std::int32_t address, int value) {
    const auto aligned = i32(u32(address) & ~1U);
    account(aligned, 2); write16_raw(aligned, value);
}

void Bus::write32(std::int32_t address, std::int32_t value) {
    const auto aligned = i32(u32(address) & ~3U);
    account(aligned, 4);
    if ((u32(aligned) >> 24U) == 0x04U) {
        const auto offset = static_cast<int>(u32(aligned) & 0x3ffU);
        for (unsigned index = 0; index < 4; ++index) {
            io[static_cast<std::size_t>(offset) + index] = static_cast<std::uint8_t>(u32(value) >> (index * 8U));
        }
        propagate_io_write(offset, value, 4);
    } else {
        write16_raw(aligned, static_cast<int>(u32(value) & 0xffffU));
        write16_raw(add32(aligned, 2), static_cast<int>(u32(value) >> 16U));
    }
}

int Bus::io_half(int offset) const noexcept {
    return io[static_cast<std::size_t>(offset)] | io[static_cast<std::size_t>(offset + 1)] << 8;
}

void Bus::write_io_half(int offset, int value) noexcept {
    io[static_cast<std::size_t>(offset)] = static_cast<std::uint8_t>(value);
    io[static_cast<std::size_t>(offset + 1)] = static_cast<std::uint8_t>(value >> 8);
}

void Bus::propagate_io_write(int offset, std::int32_t value, int bytes) {
    if (offset >= 0x0a0 && offset <= 0x0a7) {
        if (apu) apu->push_fifo(offset < 0x0a4 ? 0 : 1, value, bytes);
        return;
    }
    auto reg = offset & ~1;
    const auto end = offset + bytes;
    while (reg < end) { handle_io_write(reg, io_half(reg)); reg += 2; }
}

void Bus::handle_io_write(int offset, int value) {
    if (offset >= 0x020 && offset <= 0x027) diagnostics.bg2_matrix_write();
    else if (offset >= 0x028 && offset <= 0x02f) { diagnostics.bg2_reference_write(); ppu_affine_reference_write(ppu, offset); }
    else if (offset >= 0x038 && offset <= 0x03f) ppu_affine_reference_write(ppu, offset);
    else if (offset == 0x200 && interrupts) interrupts->enable = value;
    else if (offset == 0x202 && interrupts) interrupts->acknowledge(value);
    else if (offset == 0x208 && interrupts) interrupts->master_enable = (value & 1) != 0;
    else if (offset == 0x204) timing.wait_control = value;
    else if (timers && (offset == 0x100 || offset == 0x104 || offset == 0x108 || offset == 0x10c)) {
        timers->reload_write((offset - 0x100) / 4, value);
    } else if (timers && (offset == 0x102 || offset == 0x106 || offset == 0x10a || offset == 0x10e)) {
        timers->control_write((offset - 0x100) / 4, value);
    } else if (dma && (offset == 0x0ba || offset == 0x0c6 || offset == 0x0d2 || offset == 0x0de)) {
        const auto channel = offset == 0x0ba ? 0 : offset == 0x0c6 ? 1 : offset == 0x0d2 ? 2 : 3;
        dma_control_write(dma, channel, value);
    } else if (apu && offset >= 0x060 && offset <= 0x09f) {
        apu->write_register(offset, value);
    }
}

void Bus::reset_affine_matrices() {
    for (const auto base : {0x020, 0x030}) {
        write_io_half(base, 0x0100); write_io_half(base + 2, 0);
        write_io_half(base + 4, 0); write_io_half(base + 6, 0x0100);
    }
}

void Bus::sync_timing_from_io() { timing.wait_control = io_half(0x204); }

class DmaController {
public:
    DmaController(Bus& bus, InterruptController& interrupts) : bus_(bus), interrupts_(interrupts) {}
    void control_write(int channel, int control) {
        if ((control & 0x8000) == 0) return;
        source_[static_cast<std::size_t>(channel)] = i32(u32(read_io_word(source_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU);
        destination_[static_cast<std::size_t>(channel)] = i32(u32(read_io_word(destination_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU);
        if (timing(control) == 0) transfer(channel);
    }
    void trigger_vblank() { trigger_timing(1); }
    void trigger_hblank() { trigger_timing(2); }
    void trigger_sound_fifo(int fifo_channel) {
        const auto fifo_address = fifo_channel == 0 ? i32(0x040000a0U) : i32(0x040000a4U);
        for (int channel = 1; channel <= 2; ++channel) {
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) == 0 || timing(control) != 3) continue;
            if (i32(u32(read_io_word(destination_offsets_[static_cast<std::size_t>(channel)])) & 0x0fff'ffffU) != fifo_address) continue;
            const auto started = std::chrono::steady_clock::now();
            const auto source_control = (control >> 7) & 3;
            auto source = source_[static_cast<std::size_t>(channel)];
            auto cycles = 2;
            for (int word = 0; word < 4; ++word) {
                cycles += access_cycles(source, 4, word > 0) + access_cycles(fifo_address, 4, word > 0);
                bus_.write32(fifo_address, bus_.read32(source));
                source = add32(source, delta(source_control, 4));
            }
            source_[static_cast<std::size_t>(channel)] = source;
            finish(channel, cycles, started);
            if ((control & 0x4000) != 0) interrupts_.request(InterruptController::dma0 + channel);
        }
    }
    int take_pending_cycles() noexcept { return std::exchange(pending_cycles, 0); }
    [[nodiscard]] bool active() const noexcept { return pending_cycles > 0; }
    std::array<std::int32_t, 9> export_state() const noexcept {
        std::array<std::int32_t, 9> result{};
        std::copy(source_.begin(), source_.end(), result.begin());
        std::copy(destination_.begin(), destination_.end(), result.begin() + 4);
        result[8] = pending_cycles;
        return result;
    }
    void import_state(std::span<const std::int32_t> values) {
        if (values.size() != 9) throw SaveStateError("État DMA GBA invalide");
        std::copy_n(values.begin(), 4, source_.begin());
        std::copy_n(values.begin() + 4, 4, destination_.begin());
        pending_cycles = values[8];
    }
    void reset() noexcept { source_.fill(0); destination_.fill(0); pending_cycles = 0; last_channel = -1; }
    int pending_cycles{};
    int last_channel{-1};

private:
    static constexpr std::array source_offsets_{0x0b0, 0x0bc, 0x0c8, 0x0d4};
    static constexpr std::array destination_offsets_{0x0b4, 0x0c0, 0x0cc, 0x0d8};
    static constexpr std::array count_offsets_{0x0b8, 0x0c4, 0x0d0, 0x0dc};
    static constexpr std::array control_offsets_{0x0ba, 0x0c6, 0x0d2, 0x0de};
    int read_io_half(int offset) const noexcept {
        return bus_.io[static_cast<std::size_t>(offset)] | bus_.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    std::int32_t read_io_word(int offset) const noexcept {
        return i32(static_cast<std::uint32_t>(read_io_half(offset)) |
                   (static_cast<std::uint32_t>(read_io_half(offset + 2)) << 16U));
    }
    static int timing(int control) noexcept { return (control >> 12) & 3; }
    static int delta(int control, int size) noexcept {
        if (control == 0 || control == 3) return size;
        if (control == 1) return -size;
        return 0;
    }
    int word_count(int channel, int raw) const noexcept {
        const auto mask = channel == 3 ? 0xffff : 0x3fff;
        const auto value = raw & mask;
        return value == 0 ? mask + 1 : value;
    }
    int access_cycles(std::int32_t address, int size, bool sequential) const noexcept {
        return 1 + bus_.timing.wait_states(address, size, sequential);
    }
    void trigger_timing(int requested) {
        for (int channel = 0; channel < 4; ++channel) {
            const auto control = read_io_half(control_offsets_[static_cast<std::size_t>(channel)]);
            if ((control & 0x8000) != 0 && timing(control) == requested) transfer(channel);
        }
    }
    void transfer(int channel) {
        const auto started = std::chrono::steady_clock::now();
        const auto index = static_cast<std::size_t>(channel);
        const auto control = read_io_half(control_offsets_[index]);
        const auto size = (control & 0x0400) != 0 ? 4 : 2;
        const auto destination_control = (control >> 5) & 3;
        const auto source_control = (control >> 7) & 3;
        const auto count = word_count(channel, read_io_half(count_offsets_[index]));
        auto source = source_[index]; auto destination = destination_[index];
        if (auto* memory = bus_.eeprom(); memory &&
            ((u32(source) >> 24U) == 0x0dU || (u32(destination) >> 24U) == 0x0dU)) {
            memory->hint_transfer_length(count);
        }
        auto cycles = 2;
        for (int word = 0; word < count; ++word) {
            cycles += access_cycles(source, size, word > 0) + access_cycles(destination, size, word > 0);
            if (size == 4) bus_.write32(destination, bus_.read32(source));
            else bus_.write16(destination, bus_.read16(source));
            source = add32(source, delta(source_control, size));
            destination = add32(destination, delta(destination_control, size));
        }
        source_[index] = source;
        if (destination_control != 3) destination_[index] = destination;
        finish(channel, cycles, started);
        if ((control & 0x4000) != 0) interrupts_.request(InterruptController::dma0 + channel);
        if ((control & 0x0200) == 0 || timing(control) == 0) {
            const auto cleared = control & ~0x8000;
            bus_.io[static_cast<std::size_t>(control_offsets_[index] + 1)] = static_cast<std::uint8_t>(cleared >> 8);
        }
    }
    void finish(int channel, int cycles, std::chrono::steady_clock::time_point started) {
        bus_.take_wait_cycles();
        if (bus_.diagnostics.measuring_time) {
            bus_.diagnostics.dma_nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - started).count();
        }
        bus_.break_access_sequence(); pending_cycles += cycles; last_channel = channel;
    }
    Bus& bus_;
    InterruptController& interrupts_;
    std::array<std::int32_t, 4> source_{};
    std::array<std::int32_t, 4> destination_{};
};

void dma_control_write(DmaController* dma, int channel, int value) {
    if (dma) dma->control_write(channel, value);
}

class Ppu {
public:
    static constexpr int screen_width = 240;
    static constexpr int screen_height = 160;
    static constexpr int state_field_count = 9;
    explicit Ppu(Bus& bus) : bus_(bus) {}

    void tick(int cycles) {
        const auto next_event = in_hblank ? line_cycles_total : hdraw_cycles;
        if (line_cycles_ + cycles < next_event) { line_cycles_ += cycles; return; }
        auto remaining = cycles;
        while (remaining > 0) {
            const auto step = std::min(remaining, line_cycles_total - line_cycles_);
            line_cycles_ += step; remaining -= step;
            if (!in_hblank && line_cycles_ >= hdraw_cycles) {
                in_hblank = true;
                if (vcount < screen_height) {
                    if (render_enabled) {
                        if (bus_.diagnostics.measuring_time) {
                            const auto started = std::chrono::steady_clock::now(); render_scanline(vcount);
                            bus_.diagnostics.ppu_nanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
                                std::chrono::steady_clock::now() - started).count();
                        } else render_scanline(vcount);
                    }
                    bg2_ref_x_ = add32(bg2_ref_x_, signed16(reg16(0x22)));
                    bg2_ref_y_ = add32(bg2_ref_y_, signed16(reg16(0x26)));
                    bg3_ref_x_ = add32(bg3_ref_x_, signed16(reg16(0x32)));
                    bg3_ref_y_ = add32(bg3_ref_y_, signed16(reg16(0x36)));
                    if (irq_enabled(0x10) && interrupts) interrupts->request(InterruptController::hblank);
                    if (dma) dma->trigger_hblank();
                }
            }
            if (line_cycles_ >= line_cycles_total) {
                line_cycles_ = 0; in_hblank = false; vcount = (vcount + 1) % total_lines;
                in_vblank = vcount >= screen_height;
                if (vcount == 0) {
                    reload_affine_references();
                    if (collect_layer_stats) { layer_pixels = pending_layer_pixels_; pending_layer_pixels_.fill(0); }
                    if (collect_frame_stats) measure_luma();
                }
                if (vcount == screen_height) {
                    if (irq_enabled(0x08) && interrupts) interrupts->request(InterruptController::vblank);
                    if (dma) dma->trigger_vblank();
                }
                vcount_match = vcount == bus_.io[5];
                if (vcount_match && irq_enabled(0x20) && interrupts) interrupts->request(InterruptController::vcount);
            }
        }
    }
    [[nodiscard]] int dispstat_low() const noexcept {
        auto status = (in_vblank ? 1 : 0) | (in_hblank ? 2 : 0) | (vcount_match ? 4 : 0);
        return (bus_.io[4] & 0xf8) | status;
    }
    void on_affine_reference_write(int offset) noexcept {
        if (offset < 0x2c) bg2_ref_x_ = signed28(reg32(0x28));
        else if (offset < 0x30) bg2_ref_y_ = signed28(reg32(0x2c));
        else if (offset < 0x3c) bg3_ref_x_ = signed28(reg32(0x38));
        else bg3_ref_y_ = signed28(reg32(0x3c));
    }
    [[nodiscard]] std::array<std::int32_t, state_field_count> state_fields() const noexcept {
        return {vcount, line_cycles_, in_vblank ? 1 : 0, in_hblank ? 1 : 0,
                vcount_match ? 1 : 0, bg2_ref_x_, bg2_ref_y_, bg3_ref_x_, bg3_ref_y_};
    }
    void restore_state(std::span<const std::int32_t> values) {
        if (values.size() != state_field_count || values[0] < 0 || values[0] >= total_lines ||
            values[1] < 0 || values[1] >= line_cycles_total) throw SaveStateError("État PPU GBA invalide");
        vcount = values[0]; line_cycles_ = values[1]; in_vblank = values[2] != 0;
        in_hblank = values[3] != 0; vcount_match = values[4] != 0;
        bg2_ref_x_ = values[5]; bg2_ref_y_ = values[6]; bg3_ref_x_ = values[7]; bg3_ref_y_ = values[8];
    }

    std::array<std::int32_t, screen_width * screen_height> frame{};
    std::array<std::int32_t, 5> layer_pixels{};
    bool render_enabled{true};
    bool collect_layer_stats{};
    bool collect_frame_stats{};
    int frame_luma_min{};
    int frame_luma_max{};
    int frame_luma_mean{};
    int vcount{};
    bool in_vblank{};
    bool in_hblank{};
    bool vcount_match{};
    InterruptController* interrupts{};
    DmaController* dma{};

private:
    static constexpr int layer_backdrop = 4;
    static constexpr int layer_obj = 4;
    static constexpr int layer_backdrop_id = 5;
    static constexpr int obj_bit = 1 << 4;
    static constexpr int window_effects = 1 << 5;
    static constexpr int window_all = 0x3f;
    static constexpr int object_tile_base = 0x10000;
    static constexpr int object_palette_base = 256;
    static constexpr int hdraw_cycles = 960;
    static constexpr int line_cycles_total = 1232;
    static constexpr int total_lines = 228;
    static constexpr std::array object_width_{8,16,32,64, 16,32,32,64, 8,8,16,32};
    static constexpr std::array object_height_{8,16,32,64, 8,8,16,32, 16,32,32,64};

    [[nodiscard]] bool irq_enabled(int mask) const noexcept { return (bus_.io[4] & mask) != 0; }
    [[nodiscard]] int reg16(int offset) const noexcept {
        return bus_.io[static_cast<std::size_t>(offset)] | bus_.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    [[nodiscard]] std::int32_t reg32(int offset) const noexcept {
        return i32(static_cast<std::uint32_t>(reg16(offset)) |
                   (static_cast<std::uint32_t>(reg16(offset + 2)) << 16U));
    }
    [[nodiscard]] static std::int32_t signed16(int value) noexcept {
        return static_cast<std::int16_t>(static_cast<std::uint16_t>(value));
    }
    [[nodiscard]] static std::int32_t signed28(std::int32_t value) noexcept {
        return sign_extend(u32(value) & 0x0fff'ffffU, 28);
    }
    [[nodiscard]] int vram8(int offset) const noexcept {
        return offset >= 0 && offset < static_cast<int>(bus_.vram.size()) ? bus_.vram[static_cast<std::size_t>(offset)] : 0;
    }
    [[nodiscard]] int vram16(int offset) const noexcept { return vram8(offset) | vram8(offset + 1) << 8; }
    [[nodiscard]] int oam16(int offset) const noexcept {
        return bus_.oam[static_cast<std::size_t>(offset)] | bus_.oam[static_cast<std::size_t>(offset + 1)] << 8;
    }
    [[nodiscard]] static std::int32_t bgr555(int color) noexcept {
        const auto r = color & 31; const auto g = (color >> 5) & 31; const auto b = (color >> 10) & 31;
        return i32(0xff000000U | static_cast<std::uint32_t>((r << 3) | (r >> 2)) << 16U |
            static_cast<std::uint32_t>((g << 3) | (g >> 2)) << 8U | static_cast<std::uint32_t>((b << 3) | (b >> 2)));
    }
    [[nodiscard]] std::int32_t palette_color(int index) const noexcept {
        const auto offset = static_cast<std::size_t>(index * 2);
        if (offset + 1 >= bus_.palette.size()) return i32(0xff000000U);
        return bgr555(bus_.palette[offset] | bus_.palette[offset + 1] << 8);
    }
    static int positive_mod(int value, int modulus) noexcept {
        const auto result = value % modulus; return result < 0 ? result + modulus : result;
    }
    void reload_affine_references() noexcept {
        bg2_ref_x_ = signed28(reg32(0x28)); bg2_ref_y_ = signed28(reg32(0x2c));
        bg3_ref_x_ = signed28(reg32(0x38)); bg3_ref_y_ = signed28(reg32(0x3c));
    }
    void render_scanline(int y) {
        const auto display = reg16(0);
        const auto row = static_cast<std::size_t>(y * screen_width);
        if ((display & 0x80) != 0) { std::fill_n(frame.begin() + static_cast<std::ptrdiff_t>(row), screen_width, i32(0xffffffffU)); return; }
        const auto blend = reg16(0x50); simple_composition_ = (blend & 0x3fc0) == 0;
        const auto backdrop = palette_color(0);
        for (int x = 0; x < screen_width; ++x) {
            const auto index = static_cast<std::size_t>(x);
            line_color_[index] = backdrop; line_priority_[index] = layer_backdrop; line_layer_[index] = layer_backdrop_id;
            line_color2_[index] = backdrop; line_priority2_[index] = layer_backdrop; line_layer2_[index] = layer_backdrop_id;
            obj_opaque_[index] = false; obj_semitransparent_[index] = false; obj_window_[index] = false;
        }
        render_sprites(y, display); compute_window_mask(y, display);
        switch (display & 7) {
        case 0: case 1: case 2: render_backgrounds(y, display); break;
        case 3: render_mode3(y, display); break; case 4: render_mode4(y, display); break;
        case 5: render_mode5(y, display); break; default: break;
        }
        if (simple_composition_) compose_simple(row); else compose(row, blend);
    }
    void push_pixel(int x, std::int32_t color, int priority, int layer) noexcept {
        const auto index = static_cast<std::size_t>(x);
        if (collect_layer_stats) ++pending_layer_pixels_[static_cast<std::size_t>(layer)];
        line_color2_[index] = line_color_[index]; line_priority2_[index] = line_priority_[index]; line_layer2_[index] = line_layer_[index];
        line_color_[index] = color; line_priority_[index] = priority; line_layer_[index] = layer;
    }
    void render_backgrounds(int y, int display) {
        const auto mode = display & 7; const auto first = mode == 2 ? 2 : 0; const auto last = mode == 1 ? 2 : 3;
        std::array<std::pair<int,int>, 4> order{}; int count{};
        for (int bg = first; bg <= last; ++bg) if ((display & (1 << (8 + bg))) != 0) {
            order[static_cast<std::size_t>(count++)] = {(reg16(0x08 + bg * 2) & 3) * 4 + bg, bg};
        }
        for (int index = 1; index < count; ++index) {
            const auto value = order[static_cast<std::size_t>(index)];
            auto position = index;
            while (position > 0 && order[static_cast<std::size_t>(position - 1)].first < value.first) {
                order[static_cast<std::size_t>(position)] = order[static_cast<std::size_t>(position - 1)];
                --position;
            }
            order[static_cast<std::size_t>(position)] = value;
        }
        for (int index = 0; index < count; ++index) {
            const auto bg = order[static_cast<std::size_t>(index)].second;
            if ((mode == 1 && bg == 2) || (mode == 2 && bg >= 2)) draw_affine(bg);
            else draw_text(bg, y);
        }
    }
    void draw_affine(int bg) {
        const auto control = reg16(0x08 + bg * 2); const auto priority = control & 3;
        const auto char_base = ((control >> 2) & 3) * 0x4000; const auto screen_base = ((control >> 8) & 31) * 0x800;
        const auto wraps = (control & 0x2000) != 0; const auto tiles = 16 << ((control >> 14) & 3);
        const auto pixels = tiles * 8; const auto param = bg == 2 ? 0x20 : 0x30;
        const auto pa = signed16(reg16(param)); const auto pc = signed16(reg16(param + 4));
        auto current_x = bg == 2 ? bg2_ref_x_ : bg3_ref_x_; auto current_y = bg == 2 ? bg2_ref_y_ : bg3_ref_y_;
        for (int x = 0; x < screen_width; ++x) {
            auto texture_x = current_x >> 8; auto texture_y = current_y >> 8;
            current_x = add32(current_x, pa); current_y = add32(current_y, pc);
            if ((window_mask_[static_cast<std::size_t>(x)] & (1 << bg)) == 0) continue;
            if (wraps) { texture_x = positive_mod(texture_x, pixels); texture_y = positive_mod(texture_y, pixels); }
            else if (texture_x < 0 || texture_x >= pixels || texture_y < 0 || texture_y >= pixels) continue;
            const auto tile = vram8(screen_base + texture_y / 8 * tiles + texture_x / 8);
            const auto color = vram8(char_base + tile * 64 + (texture_y & 7) * 8 + (texture_x & 7));
            if (color != 0) push_pixel(x, palette_color(color), priority, bg);
        }
    }
    void draw_text(int bg, int y) {
        const auto control = reg16(0x08 + bg * 2); const auto priority = control & 3;
        const auto char_base = ((control >> 2) & 3) * 0x4000; const auto screen_base = ((control >> 8) & 31) * 0x800;
        const auto eight_bpp = (control & 0x80) != 0; const auto size = (control >> 14) & 3;
        const auto width = size == 1 || size == 3 ? 512 : 256; const auto height = size >= 2 ? 512 : 256;
        const auto effective_y = (y + (reg16(0x12 + bg * 4) & 0x1ff)) & (height - 1);
        const auto tile_y = effective_y >> 3; const auto initial_py = effective_y & 7;
        const auto horizontal = reg16(0x10 + bg * 4) & 0x1ff;
        for (int x = 0; x < screen_width; ++x) {
            if ((window_mask_[static_cast<std::size_t>(x)] & (1 << bg)) == 0) continue;
            const auto effective_x = (x + horizontal) & (width - 1); const auto tile_x = effective_x >> 3;
            const auto block_x = tile_x >= 32 ? 1 : 0; const auto block_y = tile_y >= 32 ? 1 : 0;
            const auto block = size == 1 ? block_x : size == 2 ? block_y : size == 3 ? block_y * 2 + block_x : 0;
            const auto entry = vram16(screen_base + block * 0x800 + ((tile_y & 31) * 32 + (tile_x & 31)) * 2);
            const auto tile = entry & 0x3ff; const auto px = (entry & 0x400) != 0 ? 7 - (effective_x & 7) : effective_x & 7;
            const auto py = (entry & 0x800) != 0 ? 7 - initial_py : initial_py;
            int color{};
            if (eight_bpp) color = vram8(char_base + tile * 64 + py * 8 + px);
            else { const auto byte = vram8(char_base + tile * 32 + py * 4 + px / 2); const auto nibble = (px & 1) == 0 ? byte & 15 : byte >> 4; if (nibble != 0) color = ((entry >> 12) & 15) * 16 + nibble; }
            if (color != 0) push_pixel(x, palette_color(color), priority, bg);
        }
    }
    void render_sprites(int y, int display) {
        if ((display & 0x1000) == 0) return;
        const auto one_dimensional = (display & 0x40) != 0;
        for (int sprite = 0; sprite < 128; ++sprite) {
            const auto base = sprite * 8; const auto attr0 = oam16(base); const auto transform = (attr0 >> 8) & 3;
            if (transform == 2 || ((attr0 >> 14) & 3) == 3 || ((attr0 >> 10) & 3) == 3) continue;
            const auto affine = transform == 1 || transform == 3; const auto doubled = transform == 3;
            const auto attr1 = oam16(base + 2); const auto attr2 = oam16(base + 4);
            const auto shape = (attr0 >> 14) & 3; const auto size_key = shape * 4 + ((attr1 >> 14) & 3);
            const auto width = object_width_[static_cast<std::size_t>(size_key)]; const auto height = object_height_[static_cast<std::size_t>(size_key)];
            const auto box_width = doubled ? width * 2 : width; const auto box_height = doubled ? height * 2 : height;
            const auto sprite_y = (y - (attr0 & 0xff)) & 0xff; if (sprite_y >= box_height) continue;
            auto sprite_x = attr1 & 0x1ff; if (sprite_x >= 0x100) sprite_x -= 0x200;
            const auto eight_bpp = (attr0 & 0x2000) != 0; const auto palette_bank = (attr2 >> 12) & 15;
            const auto priority = (attr2 >> 10) & 3; const auto tile_base = attr2 & 0x3ff;
            const auto slots = eight_bpp ? 2 : 1; const auto row_stride = one_dimensional ? width / 8 * slots : 32;
            auto pa = 0x100; auto pb = 0; auto pc = 0; auto pd = 0x100;
            if (affine) { const auto group = ((attr1 >> 9) & 31) * 32; pa = signed16(oam16(group + 6)); pb = signed16(oam16(group + 14)); pc = signed16(oam16(group + 22)); pd = signed16(oam16(group + 30)); }
            const auto horizontal_flip = !affine && (attr1 & 0x1000) != 0; const auto vertical_flip = !affine && (attr1 & 0x2000) != 0;
            for (int column = 0; column < box_width; ++column) {
                const auto screen_x = sprite_x + column; if (screen_x < 0 || screen_x >= screen_width) continue;
                int texture_x{}; int texture_y{};
                if (affine) {
                    const auto dx = column - box_width / 2; const auto dy = sprite_y - box_height / 2;
                    texture_x = (pa * dx + pb * dy) >> 8; texture_x += width / 2;
                    texture_y = (pc * dx + pd * dy) >> 8; texture_y += height / 2;
                    if (texture_x < 0 || texture_x >= width || texture_y < 0 || texture_y >= height) continue;
                } else { texture_x = horizontal_flip ? width - 1 - column : column; texture_y = vertical_flip ? height - 1 - sprite_y : sprite_y; }
                const auto tile = tile_base + texture_y / 8 * row_stride + texture_x / 8 * slots;
                int color{}; const auto in_x = texture_x & 7; const auto in_y = texture_y & 7;
                if (eight_bpp) color = vram8(object_tile_base + tile * 32 + in_y * 8 + in_x);
                else { const auto byte = vram8(object_tile_base + tile * 32 + in_y * 4 + in_x / 2); const auto nibble = (in_x & 1) == 0 ? byte & 15 : byte >> 4; if (nibble != 0) color = palette_bank * 16 + nibble; }
                if (color == 0) continue;
                const auto index = static_cast<std::size_t>(screen_x);
                if (((attr0 >> 10) & 3) == 2) { obj_window_[index] = true; continue; }
                if (obj_opaque_[index]) continue;
                if (collect_layer_stats) ++pending_layer_pixels_[layer_obj];
                obj_color_[index] = palette_color(object_palette_base + color); obj_priority_[index] = priority;
                obj_opaque_[index] = true; obj_semitransparent_[index] = ((attr0 >> 10) & 3) == 1;
            }
        }
    }
    void compute_window_mask(int y, int display) {
        const auto win0 = (display & 0x2000) != 0; const auto win1 = (display & 0x4000) != 0; const auto obj = (display & 0x8000) != 0;
        if (!win0 && !win1 && !obj) { window_mask_.fill(window_all); return; }
        const auto inside = reg16(0x48); const auto outside = reg16(0x4a);
        for (int x = 0; x < screen_width; ++x) {
            const auto index = static_cast<std::size_t>(x);
            if (win0 && inside_window(0, x, y)) window_mask_[index] = inside & 0x3f;
            else if (win1 && inside_window(1, x, y)) window_mask_[index] = (inside >> 8) & 0x3f;
            else if (obj && obj_window_[index]) window_mask_[index] = (outside >> 8) & 0x3f;
            else window_mask_[index] = outside & 0x3f;
        }
    }
    bool inside_window(int window, int x, int y) const noexcept {
        const auto horizontal = reg16(0x40 + window * 2); const auto vertical = reg16(0x44 + window * 2);
        const auto left = (horizontal >> 8) & 0xff; const auto right = horizontal & 0xff;
        const auto top = (vertical >> 8) & 0xff; const auto bottom = vertical & 0xff;
        const auto in_x = left <= right ? x >= left && x < right : x >= left || x < right;
        const auto in_y = top <= bottom ? y >= top && y < bottom : y >= top || y < bottom;
        return in_x && in_y;
    }
    void compose_simple(std::size_t row) noexcept {
        for (int x = 0; x < screen_width; ++x) { const auto index = static_cast<std::size_t>(x);
            const auto show = obj_opaque_[index] && (window_mask_[index] & obj_bit) != 0 && obj_priority_[index] <= line_priority_[index];
            frame[row + index] = show ? obj_color_[index] : line_color_[index]; }
    }
    void compose(std::size_t row, int blend) {
        const auto mode = (blend >> 6) & 3;
        for (int x = 0; x < screen_width; ++x) { const auto index = static_cast<std::size_t>(x);
            auto top = line_color_[index]; auto top_layer = line_layer_[index]; auto bottom = line_color2_[index]; auto bottom_layer = line_layer2_[index]; auto semi = false;
            if (obj_opaque_[index] && (window_mask_[index] & obj_bit) != 0) {
                if (obj_priority_[index] <= line_priority_[index]) { bottom = top; bottom_layer = top_layer; top = obj_color_[index]; top_layer = layer_obj; semi = obj_semitransparent_[index]; }
                else if (obj_priority_[index] <= line_priority2_[index]) { bottom = obj_color_[index]; bottom_layer = layer_obj; }
            }
            frame[row + index] = color_effect(index, mode, blend, top, top_layer, bottom, bottom_layer, semi);
        }
    }
    std::int32_t color_effect(std::size_t x, int mode, int blend, std::int32_t top, int top_layer,
                              std::int32_t bottom, int bottom_layer, bool semi) const noexcept {
        if ((window_mask_[x] & window_effects) == 0) return top;
        const auto target1 = (blend & (1 << top_layer)) != 0; const auto target2 = (blend & (1 << (8 + bottom_layer))) != 0;
        if (semi && target2) return alpha(top, bottom);
        if (!target1) return top;
        if (mode == 1 && target2) return alpha(top, bottom);
        if (mode == 2) return brightness(top, true);
        if (mode == 3) return brightness(top, false);
        return top;
    }
    std::int32_t alpha(std::int32_t top, std::int32_t bottom) const noexcept {
        const auto coefficients = reg16(0x52); const auto a = std::min(coefficients & 31, 16); const auto b = std::min((coefficients >> 8) & 31, 16);
        const auto channel = [&](int shift) {
            const auto mixed = ((((u32(top) >> shift) & 0xffU) * static_cast<unsigned>(a)) +
                (((u32(bottom) >> shift) & 0xffU) * static_cast<unsigned>(b))) >> 4U;
            return std::min(255U, mixed);
        };
        return i32(0xff000000U | channel(16) << 16U | channel(8) << 8U | channel(0));
    }
    std::int32_t brightness(std::int32_t color, bool white) const noexcept {
        const auto amount = std::min(reg16(0x54) & 31, 16); std::uint32_t result = 0xff000000U;
        for (const auto shift : {16U, 8U, 0U}) { const auto value = static_cast<int>((u32(color) >> shift) & 0xffU); const auto adjusted = white ? value + ((255 - value) * amount >> 4) : value - (value * amount >> 4); result |= static_cast<std::uint32_t>(adjusted) << shift; }
        return i32(result);
    }
    void render_mode3(int y, int display) {
        if ((display & 0x0400) == 0) return;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < screen_width; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, bgr555(vram16((y * screen_width + x) * 2)), priority, 2);
    }
    void render_mode4(int y, int display) {
        if ((display & 0x0400) == 0) return;
        const auto page = (display & 0x10) != 0 ? 0xa000 : 0;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < screen_width; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, palette_color(vram8(page + y * screen_width + x)), priority, 2);
    }
    void render_mode5(int y, int display) {
        if ((display & 0x0400) == 0 || y >= 128) return;
        const auto page = (display & 0x10) != 0 ? 0xa000 : 0;
        const auto priority = reg16(0x0c) & 3;
        for (int x = 0; x < 160; ++x) if ((window_mask_[static_cast<std::size_t>(x)] & 4) != 0) push_pixel(x, bgr555(vram16(page + (y * 160 + x) * 2)), priority, 2);
    }
    void measure_luma() noexcept {
        auto minimum = 255; auto maximum = 0; std::int64_t sum{};
        for (const auto pixel : frame) { const auto raw = u32(pixel); const auto luma = static_cast<int>((54U * ((raw >> 16U) & 255U) + 183U * ((raw >> 8U) & 255U) + 19U * (raw & 255U)) >> 8U); minimum = std::min(minimum, luma); maximum = std::max(maximum, luma); sum += luma; }
        frame_luma_min = minimum;
        frame_luma_max = maximum;
        frame_luma_mean = static_cast<int>(sum / static_cast<std::int64_t>(frame.size()));
    }

    Bus& bus_;
    int line_cycles_{};
    std::int32_t bg2_ref_x_{}; std::int32_t bg2_ref_y_{}; std::int32_t bg3_ref_x_{}; std::int32_t bg3_ref_y_{};
    bool simple_composition_{};
    std::array<std::int32_t, screen_width> line_color_{}; std::array<int, screen_width> line_priority_{}; std::array<int, screen_width> line_layer_{};
    std::array<std::int32_t, screen_width> line_color2_{}; std::array<int, screen_width> line_priority2_{}; std::array<int, screen_width> line_layer2_{};
    std::array<std::int32_t, screen_width> obj_color_{}; std::array<int, screen_width> obj_priority_{};
    std::array<bool, screen_width> obj_opaque_{}; std::array<bool, screen_width> obj_semitransparent_{}; std::array<bool, screen_width> obj_window_{};
    std::array<int, screen_width> window_mask_{}; std::array<std::int32_t, 5> pending_layer_pixels_{};
};

int ppu_dispstat_low(const Ppu* ppu) noexcept { return ppu ? ppu->dispstat_low() : 0; }
int ppu_vcount(const Ppu* ppu) noexcept { return ppu ? ppu->vcount : 0; }
void ppu_affine_reference_write(Ppu* ppu, int offset) noexcept { if (ppu) ppu->on_affine_reference_write(offset); }

class Bios {
public:
    struct WaitState { int interrupt_mask{}; bool discard_old_flags{}; };
    Bios(Cpu& cpu, Bus& bus) : cpu_(cpu), bus_(bus) { install_irq_handler(); }

    void handle_swi(int number) {
        auto& registers = cpu_.state.regs; bus_.diagnostics.swi(number);
        switch (number) {
        case 0x00: soft_reset(); break; case 0x01: register_ram_reset(registers[0]); break;
        case 0x02: case 0x03: wait_state_.reset(); cpu_.state.halted = true; break;
        case 0x04: intr_wait(registers[0] != 0, registers[1] & 0x3fff); break;
        case 0x05: intr_wait(true, 1 << InterruptController::vblank); break;
        case 0x06: divide(registers[0], registers[1]); break; case 0x07: divide(registers[1], registers[0]); break;
        case 0x08: registers[0] = integer_sqrt(u32(registers[0])); break;
        case 0x09: registers[0] = arc_tan(registers[0]); break; case 0x0a: registers[0] = arc_tan2(registers[0], registers[1]); break;
        case 0x0b: cpu_set(registers[0], registers[1], registers[2]); break;
        case 0x0c: cpu_fast_set(registers[0], registers[1], registers[2]); break;
        case 0x0e: bg_affine_set(registers[0], registers[1], registers[2]); break;
        case 0x0f: obj_affine_set(registers[0], registers[1], registers[2], registers[3]); break;
        case 0x10: bit_unpack(registers[0], registers[1], registers[2]); break;
        case 0x11: lz77(registers[0], registers[1], false); break; case 0x12: lz77(registers[0], registers[1], true); break;
        case 0x13: huffman(registers[0], registers[1]); break;
        case 0x14: run_length(registers[0], registers[1], false); break; case 0x15: run_length(registers[0], registers[1], true); break;
        case 0x16: differential(registers[0], registers[1], false, false); break;
        case 0x17: differential(registers[0], registers[1], false, true); break;
        case 0x18: differential(registers[0], registers[1], true, true); break;
        default:
            bus_.diagnostics.report(DiagnosticEvent::unsupported_swi,
                "appel logiciel GBA non pris en charge " + std::to_string(number));
            break;
        }
    }
    void interrupt_raised(int mask) { write_flags(flags() | mask); }
    [[nodiscard]] bool should_resume() {
        if (!bus_.interrupts) return true;
        if (!wait_state_) return (bus_.interrupts->enable & bus_.interrupts->flags & 0x3fff) != 0;
        if ((flags() & wait_state_->interrupt_mask) == 0) return false;
        clear_flags(wait_state_->interrupt_mask); wait_state_.reset(); return true;
    }
    [[nodiscard]] const std::optional<WaitState>& wait_state() const noexcept { return wait_state_; }
    void restore_wait_state(std::optional<WaitState> value) noexcept { wait_state_ = value; }

private:
    static constexpr std::int32_t interrupt_flags_address = i32(0x03007ff8U);
    static constexpr std::size_t max_output = 1U << 20U;
    void install_irq_handler() noexcept {
        constexpr std::array<std::uint32_t, 6> handler{
            0xe92d500fU, 0xe3a00301U, 0xe28fe000U, 0xe510f004U, 0xe8bd500fU, 0xe25ef004U,
        };
        auto offset = 0x18U;
        for (const auto word : handler) for (unsigned byte = 0; byte < 4; ++byte) {
            bus_.bios[offset++] = static_cast<std::uint8_t>(word >> (byte * 8U));
        }
    }
    int flags() { return bus_.read32(interrupt_flags_address); }
    void write_flags(int value) { bus_.write32(interrupt_flags_address, value); }
    void clear_flags(int mask) { write_flags(flags() & ~mask); }
    void intr_wait(bool discard, int mask) {
        if (bus_.interrupts) bus_.interrupts->master_enable = true;
        if (mask == 0) return;
        if (discard) clear_flags(mask);
        else if ((flags() & mask) != 0) { clear_flags(mask); return; }
        wait_state_ = WaitState{mask, discard}; cpu_.state.halted = true;
    }
    void soft_reset() { cpu_.reset(i32(0x08000000U)); cpu_.branch_to(i32(0x08000000U)); }
    void register_ram_reset(int flags) {
        if ((flags & 1) != 0) bus_.ewram.fill(0);
        if ((flags & 2) != 0) std::fill(bus_.iwram.begin(), bus_.iwram.end() - 0x200, 0);
        if ((flags & 4) != 0) bus_.palette.fill(0);
        if ((flags & 8) != 0) bus_.vram.fill(0);
        if ((flags & 0x10) != 0) bus_.oam.fill(0);
        if ((flags & 0x20) != 0) std::fill(bus_.io.begin() + 0x120, bus_.io.begin() + 0x160, 0);
        if ((flags & 0x40) != 0) { std::fill(bus_.io.begin() + 0x60, bus_.io.begin() + 0x0a8, 0); if (bus_.apu) bus_.apu->reset(); }
        if ((flags & 0x80) != 0) {
            std::fill(bus_.io.begin(), bus_.io.begin() + 0x60, 0);
            std::fill(bus_.io.begin() + 0x0a8, bus_.io.begin() + 0x120, 0);
            std::fill(bus_.io.begin() + 0x160, bus_.io.end(), 0);
            if (bus_.timers) bus_.timers->reset();
            if (bus_.interrupts) bus_.interrupts->reset();
            if (bus_.dma) bus_.dma->reset();
            bus_.reset_affine_matrices();
        }
    }
    void divide(std::int32_t numerator, std::int32_t denominator) noexcept {
        if (denominator == 0) return;
        const auto quotient64 = static_cast<std::int64_t>(numerator) / denominator;
        const auto remainder64 = static_cast<std::int64_t>(numerator) % denominator;
        const auto quotient = i32(static_cast<std::uint32_t>(quotient64));
        cpu_.state.regs[0] = quotient; cpu_.state.regs[1] = i32(static_cast<std::uint32_t>(remainder64));
        cpu_.state.regs[3] = quotient < 0 ? i32(0U - u32(quotient)) : quotient;
    }
    static int integer_sqrt(std::uint32_t value) noexcept {
        auto root = static_cast<std::uint32_t>(std::sqrt(static_cast<double>(value)));
        while (static_cast<std::uint64_t>(root) * root > value) --root;
        while (static_cast<std::uint64_t>(root + 1U) * (root + 1U) <= value) ++root;
        return static_cast<int>(root);
    }
    static int arc_tan(int value) noexcept {
        const auto fixed = static_cast<double>(static_cast<std::int16_t>(value)) / 16384.0;
        return static_cast<int>(std::atan(fixed) * 32768.0 / std::numbers::pi) & 0xffff;
    }
    static int arc_tan2(int x, int y) noexcept {
        const auto fx = static_cast<double>(static_cast<std::int16_t>(x)); const auto fy = static_cast<double>(static_cast<std::int16_t>(y));
        if (fx == 0.0 && fy == 0.0) return 0;
        auto angle = std::atan2(fy, fx);
        if (angle < 0.0) angle += 2.0 * std::numbers::pi;
        return static_cast<int>(angle * 32768.0 / std::numbers::pi) & 0xffff;
    }
    void cpu_set(std::int32_t source, std::int32_t destination, std::int32_t control) {
        const auto count = static_cast<int>(u32(control) & 0x1fffffU); const auto fixed = (u32(control) & (1U << 24U)) != 0;
        const auto words = (u32(control) & (1U << 26U)) != 0; const auto unit = words ? 4 : 2;
        for (int index = 0; index < count; ++index) {
            if (words) bus_.write32(destination, bus_.read32(source)); else bus_.write16(destination, bus_.read16(source));
            if (!fixed) source = add32(source, unit);
            destination = add32(destination, unit);
        }
    }
    void cpu_fast_set(std::int32_t source, std::int32_t destination, std::int32_t control) {
        const auto count = static_cast<int>(u32(control) & 0x1fffffU); const auto fixed = (u32(control) & (1U << 24U)) != 0;
        for (int index = 0; index < count; ++index) { bus_.write32(destination, bus_.read32(source)); if (!fixed) source = add32(source, 4); destination = add32(destination, 4); }
    }
    void bg_affine_set(std::int32_t source, std::int32_t destination, int count) {
        count = std::clamp(count, 0, 512);
        for (int entry = 0; entry < count; ++entry) {
            const auto origin_x = bus_.read32(source); const auto origin_y = bus_.read32(add32(source, 4));
            const auto screen_x = static_cast<std::int16_t>(bus_.read16(add32(source, 8)));
            const auto screen_y = static_cast<std::int16_t>(bus_.read16(add32(source, 10)));
            const auto scale_x = static_cast<std::int16_t>(bus_.read16(add32(source, 12)));
            const auto scale_y = static_cast<std::int16_t>(bus_.read16(add32(source, 14)));
            const auto angle = static_cast<double>((bus_.read16(add32(source, 16)) >> 8) & 0xff) * 2.0 * std::numbers::pi / 256.0;
            source = add32(source, 20); const auto cosine = std::cos(angle); const auto sine = std::sin(angle);
            const auto pa = static_cast<std::int32_t>(cosine * scale_x); const auto pb = static_cast<std::int32_t>(-sine * scale_x);
            const auto pc = static_cast<std::int32_t>(sine * scale_y); const auto pd = static_cast<std::int32_t>(cosine * scale_y);
            bus_.write16(destination, pa); bus_.write16(add32(destination, 2), pb); bus_.write16(add32(destination, 4), pc); bus_.write16(add32(destination, 6), pd);
            bus_.write32(add32(destination, 8), add32(origin_x, i32(0U - u32(i32(u32(pa) * static_cast<std::uint32_t>(screen_x) + u32(pb) * static_cast<std::uint32_t>(screen_y))))));
            bus_.write32(add32(destination, 12), add32(origin_y, i32(0U - u32(i32(u32(pc) * static_cast<std::uint32_t>(screen_x) + u32(pd) * static_cast<std::uint32_t>(screen_y))))));
            destination = add32(destination, 16);
        }
    }
    void obj_affine_set(std::int32_t source, std::int32_t destination, int count, int stride) {
        count = std::clamp(count, 0, 512);
        for (int entry = 0; entry < count; ++entry) {
            const auto scale_x = static_cast<std::int16_t>(bus_.read16(source)); const auto scale_y = static_cast<std::int16_t>(bus_.read16(add32(source, 2)));
            const auto angle = static_cast<double>((bus_.read16(add32(source, 4)) >> 8) & 0xff) * 2.0 * std::numbers::pi / 256.0;
            source = add32(source, 8); const auto cosine = std::cos(angle); const auto sine = std::sin(angle);
            bus_.write16(destination, static_cast<int>(cosine * scale_x)); bus_.write16(add32(destination, stride), static_cast<int>(-sine * scale_x));
            bus_.write16(add32(destination, stride * 2), static_cast<int>(sine * scale_y)); bus_.write16(add32(destination, stride * 3), static_cast<int>(cosine * scale_y));
            destination = add32(destination, stride * 4);
        }
    }
    [[nodiscard]] int output_size(std::int32_t source) { return static_cast<int>((u32(bus_.read32(source)) >> 8U) & 0x00ff'ffffU); }
    bool validate_size(int size) {
        if (size > 0 && static_cast<std::size_t>(size) <= max_output) return true;
        bus_.diagnostics.report(DiagnosticEvent::decompression_error, "taille de décompression GBA invalide"); return false;
    }
    void decompression_error(std::string message) { bus_.diagnostics.report(DiagnosticEvent::decompression_error, std::move(message)); }
    void write_output(std::int32_t destination, std::span<const std::uint8_t> output, bool halfwords) {
        if (!halfwords) { for (std::size_t index = 0; index < output.size(); ++index) bus_.write8(add32(destination, static_cast<int>(index)), output[index]); return; }
        std::size_t index{}; for (; index + 1 < output.size(); index += 2) bus_.write16(add32(destination, static_cast<int>(index)), output[index] | output[index + 1] << 8);
        if (index < output.size()) bus_.write16(add32(destination, static_cast<int>(index)), output[index]);
    }
    void lz77(std::int32_t source, std::int32_t destination, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
        auto input = add32(source, 4); int written{};
        while (written < size) { const auto flags = bus_.read8(input); input = add32(input, 1);
            for (int bit = 7; bit >= 0 && written < size; --bit) {
                if (((flags >> bit) & 1) == 0) { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); continue; }
                const auto first = bus_.read8(input); const auto second = bus_.read8(add32(input, 1)); input = add32(input, 2);
                const auto length = (first >> 4) + 3; const auto distance = ((first & 15) << 8 | second) + 1; auto from = written - distance;
                if (from < 0) { decompression_error("référence LZ77 GBA invalide"); return; }
                for (int count = 0; count < length && written < size; ++count) output[static_cast<std::size_t>(written++)] = output[static_cast<std::size_t>(from++)];
            }
        }
        write_output(destination, output, vram);
    }
    void run_length(std::int32_t source, std::int32_t destination, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size));
        auto input = add32(source, 4); int written{};
        while (written < size) { const auto flag = bus_.read8(input); input = add32(input, 1); const auto length = (flag & 0x7f) + ((flag & 0x80) != 0 ? 3 : 1);
            if ((flag & 0x80) != 0) { const auto value = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); for (int i = 0; i < length && written < size; ++i) output[static_cast<std::size_t>(written++)] = value; }
            else for (int i = 0; i < length && written < size; ++i) { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(bus_.read8(input)); input = add32(input, 1); }
        }
        write_output(destination, output, vram);
    }
    void huffman(std::int32_t source, std::int32_t destination) {
        const auto header = bus_.read32(source); const auto size = static_cast<int>((u32(header) >> 8U) & 0xffffffU); const auto symbol_bits = static_cast<int>(u32(header) & 15U);
        if (!validate_size(size) || (symbol_bits != 4 && symbol_bits != 8)) { decompression_error("flux Huffman GBA invalide"); return; }
        const auto tree_start = add32(source, 4); const auto tree_bytes = (bus_.read8(tree_start) + 1) * 2;
        const auto root = add32(tree_start, 1); const auto tree_end = add32(tree_start, tree_bytes); auto stream = tree_end;
        std::vector<std::uint8_t> output(static_cast<std::size_t>(size)); int written{}; bool high{}; int pending{};
        auto node = root; std::uint32_t word{}; int bits{}; std::int64_t budget = static_cast<std::int64_t>(size) * 8 * 32 + 64;
        while (written < size) {
            if (--budget <= 0) { decompression_error("flux Huffman GBA sans fin"); return; }
            if (bits == 0) { word = u32(bus_.read32(stream)); stream = add32(stream, 4); bits = 32; }
            const auto bit = static_cast<int>(word >> 31U); word <<= 1U; --bits; const auto node_value = bus_.read8(node);
            const auto next = add32(i32(u32(node) & ~1U), ((node_value & 0x3f) + 1) * 2 + bit);
            const auto leaf = bit == 0 ? (node_value & 0x80) != 0 : (node_value & 0x40) != 0;
            if (u32(next) < u32(root) || u32(next) >= u32(tree_end)) { decompression_error("arbre Huffman GBA invalide"); return; }
            if (!leaf) { node = next; continue; } const auto symbol = bus_.read8(next); node = root;
            if (symbol_bits == 8) output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(symbol);
            else if (!high) { pending = symbol & 15; high = true; }
            else { output[static_cast<std::size_t>(written++)] = static_cast<std::uint8_t>(pending | (symbol & 15) << 4); high = false; }
        }
        write_output(destination, output, true);
    }
    void differential(std::int32_t source, std::int32_t destination, bool wide, bool vram) {
        const auto size = output_size(source); if (!validate_size(size)) return; std::vector<std::uint8_t> output(static_cast<std::size_t>(size)); auto input = add32(source, 4);
        if (wide) { int previous{}; for (int index = 0; index + 1 < size; index += 2) { const auto delta = bus_.read8(input) | bus_.read8(add32(input, 1)) << 8; input = add32(input, 2); previous = (previous + delta) & 0xffff; output[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(previous); output[static_cast<std::size_t>(index + 1)] = static_cast<std::uint8_t>(previous >> 8); } }
        else { int previous{}; for (int index = 0; index < size; ++index) { previous = (previous + bus_.read8(input)) & 0xff; input = add32(input, 1); output[static_cast<std::size_t>(index)] = static_cast<std::uint8_t>(previous); } }
        write_output(destination, output, vram);
    }
    void bit_unpack(std::int32_t source, std::int32_t destination, std::int32_t parameters) {
        const auto length = bus_.read16(parameters); const auto source_width = bus_.read8(add32(parameters, 2)); const auto destination_width = bus_.read8(add32(parameters, 3));
        const auto offset_word = u32(bus_.read32(add32(parameters, 4))); const auto data_offset = offset_word & 0x7fffffffU; const auto offset_zero = (offset_word & 0x80000000U) != 0;
        if (length <= 0 || source_width <= 0 || source_width > 8 || destination_width <= 0 || destination_width > 32 || 8 % source_width != 0) { decompression_error("paramètres BitUnPack GBA invalides"); return; }
        std::uint64_t buffer{}; int bits{}; const auto mask = (1U << static_cast<unsigned>(source_width)) - 1U;
        for (int index = 0; index < length; ++index) { const auto byte = static_cast<unsigned>(bus_.read8(add32(source, index)));
            for (int consumed = 0; consumed < 8; consumed += source_width) { const auto value = (byte >> static_cast<unsigned>(consumed)) & mask; const auto expanded = value == 0 && !offset_zero ? 0U : value + data_offset; buffer |= static_cast<std::uint64_t>(expanded) << static_cast<unsigned>(bits); bits += destination_width;
                if (bits >= 32) { bus_.write32(destination, i32(static_cast<std::uint32_t>(buffer))); destination = add32(destination, 4); buffer >>= 32U; bits -= 32; }
            }
        }
        if (bits > 0) bus_.write32(destination, i32(static_cast<std::uint32_t>(buffer)));
    }

    Cpu& cpu_;
    Bus& bus_;
    std::optional<WaitState> wait_state_;
};

class Machine {
public:
    Machine(RomImage rom, std::optional<GbaSaveType> forced, Rtc::Clock clock)
        : cartridge(rom, forced, std::move(clock)), bus(cartridge), ppu(bus), timers(interrupts),
          dma(bus, interrupts), cpu(bus), bios(cpu, bus) {
        bus.ppu = &ppu; bus.interrupts = &interrupts; bus.timers = &timers; bus.dma = &dma; bus.apu = &apu;
        ppu.interrupts = &interrupts; ppu.dma = &dma;
        timers.on_overflow = [this](int timer) { apu.timer_overflow(timer); };
        apu.on_fifo_request = [this](int channel) { dma.trigger_sound_fifo(channel); };
        cpu.swi_handler = [this](int number) { bios.handle_swi(number); };
        interrupts.on_request = [this](int mask) { bios.interrupt_raised(mask); bus.diagnostics.interrupt(mask); };
        cpu.reset(i32(0x08000000U));
    }
    void run_frame(int cycles) {
        auto elapsed = 0; bus.diagnostics.begin_frame();
        while (elapsed < cycles) {
            const auto dma_cycles = dma.take_pending_cycles();
            if (dma_cycles > 0) { advance_peripherals(dma_cycles); elapsed += dma_cycles; continue; }
            if (cpu.state.halted) {
                advance_peripherals(64); elapsed += 64;
                bus.diagnostics.wait_step(64, bios.wait_state() ? bios.wait_state()->interrupt_mask : 0);
                if (bios.should_resume()) { cpu.state.halted = false; bus.diagnostics.wait_resolved(); bus.break_access_sequence(); }
                continue;
            }
            if (interrupts.pending() && !cpu.state.irq_disabled) {
                cpu.raise_exception(CpuState::mode_irq, 0x18, add32(cpu.state.regs[15], 4));
            }
            const auto consumed = cpu.step(); bus.diagnostics.instruction(); advance_peripherals(consumed); elapsed += consumed;
        }
    }
    void advance_peripherals(int cycles) {
        auto remaining = cycles;
        while (remaining > 0) {
            const auto step = std::max(1, std::min({remaining, timers.cycles_until_next_overflow(), apu.cycles_until_next_sample()}));
            ppu.tick(step); timers.tick(step); apu.tick(step); remaining -= step;
        }
    }

    Cartridge cartridge;
    Bus bus;
    Ppu ppu;
    InterruptController interrupts;
    Timers timers;
    DmaController dma;
    Apu apu;
    Cpu cpu;
    Bios bios;
};

class GbaCore final : public Core {
public:
    explicit GbaCore(std::optional<GbaSaveType> forced) : forced_(forced) {}
    [[nodiscard]] Console console() const noexcept override { return Console::game_boy_advance; }
    [[nodiscard]] VideoSpec video_spec() const noexcept override { return {240, 160, 16'777'216.0 / 280'896.0}; }
    [[nodiscard]] AudioSpec audio_spec() const noexcept override { return {Apu::sample_rate, 2}; }
    [[nodiscard]] FramebufferFormat framebuffer_format() const noexcept override { return FramebufferFormat::argb_8888; }
    [[nodiscard]] bool supports_video_frame_skipping() const noexcept override { return true; }

    void load_rom(std::span<const std::uint8_t> rom, std::span<const std::uint8_t> battery) override {
        if (rom.size() > Cartridge::max_rom_size) throw RomLoadError("ROM GBA trop volumineuse");
        if (rom.size() < 0xc0U) throw RomLoadError("ROM GBA trop courte");
        if (rom[0xb2] != 0x96U) throw RomLoadError("Marqueur GBA 0x96 absent");
        auto image = std::make_shared<const std::vector<std::uint8_t>>(rom.begin(), rom.end());
        auto replacement = new_machine(image);
        if (!battery.empty() && replacement->cartridge.save()) replacement->cartridge.save()->import(battery);
        loaded_rom_ = std::move(image); rom_hash_ = detail::sha256(rom); machine_ = std::move(replacement);
    }
    void reset() override {
        require_loaded(); std::vector<std::uint8_t> battery;
        if (const auto* save = machine_->cartridge.save()) battery = save->data();
        auto replacement = new_machine(loaded_rom_);
        if (!battery.empty() && replacement->cartridge.save()) replacement->cartridge.save()->import(battery);
        configure_measurement(*replacement, measuring_time_); machine_ = std::move(replacement);
    }
    void run_frame(std::span<std::int32_t> framebuffer, bool render_video) override {
        require_loaded();
        if (framebuffer.size() < Ppu::screen_width * Ppu::screen_height) throw std::invalid_argument("Framebuffer trop petit");
        machine_->ppu.render_enabled = render_video;
        try { machine_->run_frame(280'896); } catch (...) { machine_->ppu.render_enabled = true; throw; }
        machine_->ppu.render_enabled = true;
        if (render_video) std::copy(machine_->ppu.frame.begin(), machine_->ppu.frame.end(), framebuffer.begin());
    }
    void set_button(Button button, bool pressed) override { if (machine_) machine_->bus.keypad.set_button(button, pressed); }
    std::size_t read_audio(std::span<std::int16_t> destination) override {
        return machine_ ? machine_->apu.read_samples(destination) : 0;
    }
    [[nodiscard]] bool has_battery_ram() const noexcept override {
        return machine_ && machine_->cartridge.save();
    }
    [[nodiscard]] bool battery_ram_dirty() const noexcept override {
        return machine_ && machine_->cartridge.save() && machine_->cartridge.save()->dirty();
    }
    std::optional<BatterySnapshot> snapshot_battery_ram() override {
        if (!machine_ || !machine_->cartridge.save()) return std::nullopt;
        const auto* save = machine_->cartridge.save(); return BatterySnapshot{save->data(), save->generation()};
    }
    void acknowledge_battery_ram_saved(std::uint64_t generation) noexcept override {
        if (machine_ && machine_->cartridge.save()) machine_->cartridge.save()->acknowledge(generation);
    }
    [[nodiscard]] GbaSaveType gba_save_type() const noexcept override {
        return machine_ ? machine_->cartridge.save_type() : GbaSaveType::none;
    }
    void set_gba_forced_save_type(std::optional<GbaSaveType> value) noexcept override {
        forced_ = value;
    }
    void set_clock_epoch(std::optional<std::int64_t> value) noexcept override { clock_override_ = value; }
    void set_measuring_time(bool value) noexcept override {
        measuring_time_ = value; if (machine_) configure_measurement(*machine_, value);
    }
    [[nodiscard]] bool measuring_time() const noexcept override { return measuring_time_; }
    [[nodiscard]] std::vector<DiagnosticMessage> drain_diagnostics() override {
        return machine_ ? machine_->bus.diagnostics.drain() : std::vector<DiagnosticMessage>{};
    }
    [[nodiscard]] std::optional<GbaDebugSnapshot> debug_snapshot() const override {
        if (!machine_) return std::nullopt;
        const auto& m = *machine_;
        GbaDebugSnapshot result{};
        result.instructions_per_frame = m.bus.diagnostics.instructions_last_frame; result.program_counter = m.cpu.state.regs[15];
        result.thumb = m.cpu.state.thumb; result.halted = m.cpu.state.halted; result.last_swi = m.bus.diagnostics.last_swi;
        result.last_interrupt_mask = m.bus.diagnostics.last_interrupt_mask; result.vcount = m.ppu.vcount;
        result.last_dma_channel = m.dma.last_channel; result.dma_active = m.dma.active();
        result.fifo_a_size = m.apu.fifo_size(0); result.fifo_b_size = m.apu.fifo_size(1);
        result.fifo_a_empty_reads = m.apu.fifo_empty_reads(0); result.fifo_b_empty_reads = m.apu.fifo_empty_reads(1);
        result.audio_underruns = m.apu.underruns; result.unsupported_swi_count = m.bus.diagnostics.count(DiagnosticEvent::unsupported_swi);
        result.undefined_instruction_count = m.bus.diagnostics.count(DiagnosticEvent::undefined_instruction);
        result.unsupported_access_count = m.bus.diagnostics.count(DiagnosticEvent::unsupported_access);
        result.missing_interrupt_count = m.bus.diagnostics.count(DiagnosticEvent::missing_interrupt);
        result.decompression_error_count = m.bus.diagnostics.count(DiagnosticEvent::decompression_error);
        result.first_unsupported_address = m.bus.diagnostics.first_unsupported_address;
        result.dispcnt = io_half(m.bus, 0); result.bg0_control = io_half(m.bus, 8); result.bg1_control = io_half(m.bus, 0x0a);
        result.bg2_control = io_half(m.bus, 0x0c); result.bg3_control = io_half(m.bus, 0x0e);
        result.blend_control = io_half(m.bus, 0x50); result.blend_alpha = io_half(m.bus, 0x52);
        result.blend_brightness = io_half(m.bus, 0x54); result.window_inside = io_half(m.bus, 0x48); result.window_outside = io_half(m.bus, 0x4a);
        result.luma_min = m.ppu.frame_luma_min; result.luma_max = m.ppu.frame_luma_max; result.luma_mean = m.ppu.frame_luma_mean;
        result.layer_pixels.assign(m.ppu.layer_pixels.begin(), m.ppu.layer_pixels.end());
        result.bg2_reference_x = signed28(io_word(m.bus, 0x28)) >> 8; result.bg2_reference_y = signed28(io_word(m.bus, 0x2c)) >> 8;
        result.bg2_scale_x = io_half(m.bus, 0x20); result.bg2_scale_y = io_half(m.bus, 0x26);
        result.bg2_matrix_writes = m.bus.diagnostics.bg2_matrix_writes; result.bg2_reference_writes = m.bus.diagnostics.bg2_reference_writes;
        result.swi_counts.assign(m.bus.diagnostics.swi_counts.begin(), m.bus.diagnostics.swi_counts.end());
        result.ppu_millis = static_cast<double>(m.bus.diagnostics.ppu_nanos_last_frame) / 1'000'000.0;
        result.dma_millis = static_cast<double>(m.bus.diagnostics.dma_nanos_last_frame) / 1'000'000.0;
        result.apu_millis = static_cast<double>(m.bus.diagnostics.apu_nanos_last_frame) / 1'000'000.0;
        return result;
    }

    [[nodiscard]] std::vector<std::uint8_t> save_state() const override {
        require_loaded(); auto& m = *machine_; BinaryWriter out(768U * 1024U);
        out.u32(0x52564e53U); out.u16(8); out.u8(2); out.raw(rom_hash_);
        const auto banks = m.cpu.state.export_banks(); for (const auto value : m.cpu.state.regs) out.i32(value);
        out.i32(m.cpu.state.cpsr()); out.boolean(m.cpu.state.halted); out.i32(static_cast<int>(banks.size()));
        for (const auto value : banks) out.i32(value);
        out.raw(m.bus.ewram); out.raw(m.bus.iwram); out.raw(m.bus.io); out.raw(m.bus.palette);
        out.raw(m.bus.vram); out.raw(m.bus.oam); out.raw(m.bus.fallback_sram); out.i32(m.bus.keypad.pressed_bits);
        const auto ppu_state = m.ppu.state_fields(); out.i32(static_cast<int>(ppu_state.size()));
        for (const auto value : ppu_state) out.i32(value);
        for (const auto pixel : m.ppu.frame) out.i32(pixel);
        out.i32(m.interrupts.enable); out.i32(m.interrupts.flags); out.boolean(m.interrupts.master_enable);
        for (const auto value : m.timers.export_state()) out.i32(value);
        for (const auto value : m.dma.export_state()) out.i32(value);
        const auto& wait = m.bios.wait_state(); out.boolean(wait.has_value()); out.i32(wait ? wait->interrupt_mask : 0); out.boolean(wait && wait->discard_old_flags);
        const auto* save = m.cartridge.save(); out.i32(save ? static_cast<int>(save->data().size()) : 0); if (save) out.raw(save->data());
        const auto* gpio = m.cartridge.gpio(); out.boolean(gpio != nullptr); if (gpio) for (const auto value : gpio->export_state()) out.i32(value);
        return std::move(out).take();
    }
    void load_state(std::span<const std::uint8_t> bytes) override {
        require_loaded(); if (bytes.size() > (1U << 20U)) throw SaveStateError("État GBA trop volumineux");
        BinaryReader in(bytes); if (in.u32() != 0x52564e53U) throw SaveStateError("Ce fichier n'est pas un état RavenEmu");
        if (in.u16() != 8) throw SaveStateError("Version d'état GBA non prise en charge");
        if (in.u8() != 2) throw SaveStateError("État issu d'une autre console");
        std::array<std::uint8_t, 32> hash{}; in.raw(hash); if (hash != rom_hash_) throw SaveStateError("État issu d'une autre ROM");
        auto replacement = new_machine(loaded_rom_); auto& m = *replacement;
        std::array<std::int32_t, 16> registers{}; for (auto& value : registers) value = in.i32();
        const auto cpsr = in.i32(); m.cpu.state.halted = in.boolean(); if (in.i32() != 28) throw SaveStateError("État GBA corrompu (banques)");
        std::array<std::int32_t, 28> banks{}; for (auto& value : banks) value = in.i32(); m.cpu.state.import_banks(banks); m.cpu.state.set_control_raw(cpsr); m.cpu.state.regs = registers;
        read_array(in, m.bus.ewram); read_array(in, m.bus.iwram); read_array(in, m.bus.io); m.bus.sync_timing_from_io();
        read_array(in, m.bus.palette); read_array(in, m.bus.vram); read_array(in, m.bus.oam); read_array(in, m.bus.fallback_sram); m.bus.keypad.pressed_bits = in.i32();
        if (in.i32() != Ppu::state_field_count) throw SaveStateError("État GBA corrompu (PPU)");
        std::array<std::int32_t, Ppu::state_field_count> ppu_state{}; for (auto& value : ppu_state) value = in.i32(); m.ppu.restore_state(ppu_state);
        for (auto& pixel : m.ppu.frame) pixel = in.i32();
        m.interrupts.enable = in.i32();
        m.interrupts.flags = in.i32();
        m.interrupts.master_enable = in.boolean();
        std::array<std::int32_t, 16> timer_state{}; for (auto& value : timer_state) value = in.i32(); m.timers.import_state(timer_state);
        std::array<std::int32_t, 9> dma_state{}; for (auto& value : dma_state) value = in.i32(); m.dma.import_state(dma_state);
        const auto waiting = in.boolean(); const auto wait_mask = in.i32(); const auto wait_discard = in.boolean();
        m.bios.restore_wait_state(waiting ? std::optional{Bios::WaitState{wait_mask, wait_discard}} : std::nullopt);
        const auto save_size = in.i32(); const auto expected_size = m.cartridge.save() ? static_cast<int>(m.cartridge.save()->data().size()) : 0;
        if (save_size != expected_size) throw SaveStateError("État GBA corrompu (sauvegarde)");
        if (save_size > 0) m.cartridge.save()->import(in.raw(static_cast<std::size_t>(save_size)));
        auto* gpio = m.cartridge.gpio(); if (in.boolean() != (gpio != nullptr)) throw SaveStateError("État GBA corrompu (GPIO)");
        if (gpio) { std::array<std::int32_t, Gpio::state_words> gpio_state{}; for (auto& value : gpio_state) value = in.i32(); gpio->import_state(gpio_state); }
        if (!in.exhausted()) throw SaveStateError("État GBA corrompu (données excédentaires)");
        configure_measurement(m, measuring_time_); machine_ = std::move(replacement);
    }

private:
    template <std::size_t N>
    static void read_array(BinaryReader& in, std::array<std::uint8_t, N>& output) { in.raw(std::span<std::uint8_t>{output}); }
    static int io_half(const Bus& bus, int offset) noexcept {
        return bus.io[static_cast<std::size_t>(offset)] | bus.io[static_cast<std::size_t>(offset + 1)] << 8;
    }
    static std::int32_t io_word(const Bus& bus, int offset) noexcept {
        return i32(static_cast<std::uint32_t>(io_half(bus, offset)) | static_cast<std::uint32_t>(io_half(bus, offset + 2)) << 16U);
    }
    static std::int32_t signed28(std::int32_t value) noexcept { return sign_extend(u32(value) & 0x0fff'ffffU, 28); }
    void configure_measurement(Machine& machine, bool value) const noexcept {
        machine.bus.diagnostics.measuring_time = value; machine.apu.set_measuring(value);
        machine.apu.on_batch_nanos = [&machine](std::int64_t nanos) { machine.bus.diagnostics.apu_nanos += nanos; };
        machine.ppu.collect_layer_stats = value; machine.ppu.collect_frame_stats = value;
    }
    [[nodiscard]] std::int64_t current_epoch() const noexcept {
        if (clock_override_) return *clock_override_;
        return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    }
    [[nodiscard]] std::unique_ptr<Machine> new_machine(RomImage rom) const {
        return std::make_unique<Machine>(rom, forced_, [this] { return current_epoch(); });
    }
    void require_loaded() const { if (!machine_) throw std::logic_error("Aucune ROM chargée"); }

    std::optional<GbaSaveType> forced_;
    std::unique_ptr<Machine> machine_;
    RomImage loaded_rom_;
    std::array<std::uint8_t, 32> rom_hash_{};
    std::optional<std::int64_t> clock_override_;
    bool measuring_time_{};
};

} // namespace

std::unique_ptr<Core> make_gba_core(std::optional<GbaSaveType> forced_save_type) {
    return std::make_unique<GbaCore>(forced_save_type);
}

} // namespace ravenemu
