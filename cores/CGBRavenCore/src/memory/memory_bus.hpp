#pragma once

#include "memory/bus.hpp"
#include "cartridge/cartridge.hpp"
#include "apu/apu.hpp"
#include "ppu/ppu.hpp"
#include "timer/timer.hpp"
#include "serial/serial_port.hpp"
#include "input/joypad.hpp"
#include "speed/speed_controller.hpp"

namespace ravenemu::cgb {

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

} // namespace ravenemu::cgb
