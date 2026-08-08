#pragma once

#include "cpu/cpu.hpp"
#include "memory/memory_bus.hpp"
#include "cartridge/cartridge_factory.hpp"
#include <ravenemu/gbc/infrared_port.hpp>

namespace ravenemu::cgb {

class Machine {
public:
    Machine(RomImage rom, Cartridge::Clock clock)
        : cartridge(Cartridge::create(rom, std::move(clock))), cgb_mode(cartridge->header().uses_color),
          timer(interrupts), joypad(interrupts), speed(cgb_mode), serial(interrupts, cgb_mode),
          infrared(cgb_mode), ppu(interrupts, cgb_mode),
          bus(*cartridge, ppu, interrupts, timer, serial, joypad, apu, cgb_mode, speed, infrared),
          cpu(bus, interrupts) {
        if (cgb_mode) cpu.a = 0x11;
    }

    /**
     * Exécute une tranche et retourne le nombre de dots périphériques écoulés.
     * 0 signifie que la machine est en STOP normal et qu'aucune horloge interne
     * ne doit progresser avant un réveil par le joypad.
     */
    int step() {
        if (speed.switching()) {
            const int dots = std::min(4, speed.switch_dots_remaining());
            tick_speed_switch(dots);
            return dots;
        }

        if (bus.cpu_blocked()) {
            constexpr int peripheral_dots = 4;
            const int cpu_cycles = peripheral_dots << speed.peripheral_shift();
            tick_cpu_clock(cpu_cycles);
            return peripheral_dots;
        }

        const int cpu_cycles = cpu.step();
        if (cpu_cycles <= 0) return 0;
        const int peripheral_cycles = cpu_cycles >> speed.peripheral_shift();
        tick_cpu_clock(cpu_cycles);
        return peripheral_cycles;
    }

    std::unique_ptr<Cartridge> cartridge;
    bool cgb_mode{};
    InterruptController interrupts;
    Timer timer;
    Joypad joypad;
    SpeedController speed;
    SerialPort serial;
    InfraredPort infrared;
    Ppu ppu;
    Apu apu;
    MemoryBus bus;
    Cpu cpu;

private:
    void tick_cpu_clock(int cpu_cycles) {
        timer.tick(cpu_cycles);
        serial.tick(cpu_cycles);
        const int peripheral_cycles = cpu_cycles >> speed.peripheral_shift();
        ppu.tick(peripheral_cycles);
        if (ppu.take_hblank_entry()) bus.notify_hblank();
        apu.tick(peripheral_cycles);
        bus.tick(cpu_cycles, peripheral_cycles);
        cartridge->tick(cpu_cycles);
    }

    void tick_speed_switch(int peripheral_dots) {
        // Pendant la transition KEY1, le CPU/DIV/TIMA/SIO sont arrêtés mais le
        // domaine LCD/APU continue sur l'horloge périphérique de base.
        ppu.tick(peripheral_dots);
        if (ppu.take_hblank_entry()) bus.notify_hblank();
        apu.tick(peripheral_dots);
        bus.tick(0, peripheral_dots);
        speed.tick_peripheral(peripheral_dots);
    }
};

} // namespace ravenemu::cgb
