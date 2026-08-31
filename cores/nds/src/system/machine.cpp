#include "system/machine.hpp"

namespace ravenemu::nds {

Machine::Machine()
    : link_(main_interrupts_, secondary_interrupts_),
      video_(main_interrupts_, secondary_interrupts_),
      main_map_(system_, video_, link_, main_interrupts_),
      secondary_map_(system_, video_, link_, secondary_interrupts_),
      main_core_(main_map_),
      secondary_core_(secondary_map_) {}

void Machine::reset() {
    system_.reset();
    main_interrupts_.reset();
    secondary_interrupts_.reset();
    link_.reset();
    video_.reset();
    main_map_.reset();
    secondary_map_.reset();
    main_core_.reset();
    secondary_core_.reset();
}

ArmCore& Machine::core(Processor side) noexcept {
    if (side == Processor::main) return main_core_;
    return secondary_core_;
}

InterruptController& Machine::interrupts(Processor side) noexcept {
    if (side == Processor::main) return main_interrupts_;
    return secondary_interrupts_;
}

void Machine::step(Processor side) {
    auto& processor = core(side);
    auto& controller = interrupts(side);

    // L'attente s'achève sur une source autorisée en attente, sans que
    // l'autorisation générale entre en compte : c'est l'arrêt qui se lève, pas
    // l'interruption qui s'impose.
    if (processor.halted() && controller.pending()) processor.wake();

    // La ligne, elle, est bien celle qui fait prendre l'interruption. Elle est
    // reposée à chaque pas : une demande peut naître entre deux instructions,
    // et le balayage en pose sans que personne n'exécute quoi que ce soit.
    processor.set_irq_line(controller.line());
    processor.step();

    // Le processeur secondaire s'arrête par un registre de sa carte, qui ne le
    // connaît pas et ne peut donc pas l'arrêter elle-même. Le principal n'a pas
    // besoin de ce transport : son coprocesseur est dans le cœur.
    if (side == Processor::secondary && secondary_map_.take_halt_request()) {
        processor.halt();
    }
}

void Machine::run_line(std::span<std::int32_t> framebuffer) {
    for (std::uint32_t tick = 0; tick < secondary_steps_per_line; ++tick) {
        for (std::uint32_t beat = 0; beat < main_clock_multiplier; ++beat) {
            step(Processor::main);
        }
        step(Processor::secondary);
    }

    display().render_current_line(framebuffer);
    display().advance_line();
}

void Machine::run_frame(std::span<std::int32_t> framebuffer) {
    for (std::uint32_t line = 0; line < DisplayController::total_lines; ++line) {
        run_line(framebuffer);
    }
}

} // namespace ravenemu::nds
