#pragma once

#include "bios/bios.hpp"
#include "ppu/ppu.hpp"
#include "dma/dma_controller.hpp"
#include "apu/apu.hpp"
#include "timer/timers.hpp"

namespace ravenemu::gba {

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

} // namespace ravenemu::gba
