#pragma once

#include "cpu/cpu.hpp"
#include "memory/memory_bus.hpp"
#include "cartridge/cartridge_factory.hpp"

namespace ravenemu::cgb {

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

} // namespace ravenemu::cgb
