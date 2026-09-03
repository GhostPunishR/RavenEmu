#include "system/machine.hpp"

namespace ravenemu::nds {

Machine::Machine()
    : link_(main_interrupts_, secondary_interrupts_),
      video_(main_interrupts_, secondary_interrupts_),
      cartridge_(main_interrupts_, secondary_interrupts_),
      serial_(secondary_interrupts_, input_),
      main_map_(system_, video_, link_, main_interrupts_, input_, cartridge_),
      secondary_map_(
          system_, video_, link_, secondary_interrupts_, input_, cartridge_, serial_),
      main_core_(main_map_),
      secondary_core_(secondary_map_),
      main_bios_(Processor::main, main_core_, main_map_, main_interrupts_, &main_core_.cp15()),
      secondary_bios_(
          Processor::secondary, secondary_core_, secondary_map_, secondary_interrupts_, nullptr) {
    // Les appels logiciels sont servis hors du processeur, faute d'un programme
    // d'amorçage à faire tourner. Un cœur monté seul, sans console autour, garde
    // le chemin du matériel : c'est ce que les vérifications du processeur
    // éprouvent.
    main_core_.set_software_interrupt_handler(&main_bios_);
    secondary_core_.set_software_interrupt_handler(&secondary_bios_);
}

void Machine::reset() {
    system_.reset();
    input_.reset();
    main_interrupts_.reset();
    secondary_interrupts_.reset();
    link_.reset();
    video_.reset();
    cartridge_.reset();
    serial_.reset();
    main_map_.reset();
    secondary_map_.reset();
    main_core_.reset();
    secondary_core_.reset();
    // La table des vecteurs se réécrit après la remise à zéro des cœurs : celle
    // du principal dépend de son coprocesseur, qui vient d'y revenir.
    main_bios_.install(main_map_.bios());
    secondary_bios_.install(secondary_map_.bios());
}

Bios& Machine::bios(Processor side) noexcept {
    if (side == Processor::main) return main_bios_;
    return secondary_bios_;
}

ArmCore& Machine::core(Processor side) noexcept {
    if (side == Processor::main) return main_core_;
    return secondary_core_;
}

InterruptController& Machine::interrupts(Processor side) noexcept {
    if (side == Processor::main) return main_interrupts_;
    return secondary_interrupts_;
}

void Machine::load_block(
    Bus& memory,
    std::span<const std::uint8_t> rom,
    std::uint32_t offset,
    std::uint32_t address,
    std::uint32_t size
) {
    for (std::uint32_t written = 0; written < size; written += 4U) {
        std::uint32_t word = 0;
        for (std::uint32_t byte = 0; byte < 4U; ++byte) {
            const auto source = static_cast<std::size_t>(offset) + written + byte;
            if (source >= rom.size()) break;
            word |= static_cast<std::uint32_t>(rom[source]) << (byte * 8U);
        }
        memory.write32(address + written, word);
    }
}

void Machine::boot(const CartridgeHeader& header, std::span<const std::uint8_t> rom) {
    reset();

    // Le bus relit l'image à la demande et n'en garde pas de copie : une
    // cartouche fait jusqu'à cent vingt-huit mégaoctets, et la doubler en
    // mémoire pour un téléphone n'aurait pas de sens. L'appelant la garde donc
    // vivante aussi longtemps que la console tourne.
    cartridge_.insert(rom);

    // La mémoire commune revient au processeur secondaire avant que son bloc y
    // soit écrit, et c'est la seule part de l'état d'amorçage que ce cœur pose.
    //
    // Elle n'est pas devinée : elle se **déduit** de ce que l'en-tête demande.
    // Un jeu du commerce charge son bloc secondaire à `0x037F8000`, juste sous
    // la mémoire propre de ce processeur, précisément pour que les deux se
    // suivent sans trou. Ce n'est vrai que s'il tient la mémoire commune : sans
    // part, la fenêtre se replie sur sa mémoire propre, le bloc s'y enroule et
    // écrase son propre début. Le processeur exécute alors la fin de son
    // programme prise pour le commencement, et la console reste noire.
    //
    // Le partage par défaut donne tout au processeur principal, ce qui est
    // l'état de mise sous tension. Un programme qui veut un autre découpage
    // l'écrit lui-même, comme sur console.
    system_.set_shared_control(SystemMemory::shared_to_secondary);

    load_block(main_map_, rom, header.arm9_rom_offset, header.arm9_ram_address, header.arm9_size);
    load_block(
        secondary_map_, rom, header.arm7_rom_offset, header.arm7_ram_address, header.arm7_size);

    main_core_.state().registers[15] = header.arm9_entry_address;
    secondary_core_.state().registers[15] = header.arm7_entry_address;
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

    // Un transfert armé a lieu entre deux instructions : le programme qui vient
    // de l'allumer trouve la copie faite dès l'instruction suivante, et non une
    // ligne plus tard.
    // Un mot prêt sur le bus de cartouche arme les canaux qui l'attendent, du
    // seul côté qui tient le port : c'est ainsi qu'un jeu copie ses données sans
    // avoir à scruter l'indicateur entre chaque mot.
    const bool card_ready = side == cartridge_.owner() && cartridge_.transferring();
    if (side == Processor::main) {
        if (card_ready) main_map_.dma().trigger(DmaController::Timing::cartridge);
        if (main_map_.dma().pending()) main_map_.dma().run(main_map_);
    } else {
        if (card_ready) secondary_map_.dma().trigger(DmaController::Timing::cartridge);
        if (secondary_map_.dma().pending()) secondary_map_.dma().run(secondary_map_);
    }

    // Le processeur secondaire s'arrête par un registre de sa carte, qui ne le
    // connaît pas et ne peut donc pas l'arrêter elle-même. Le principal n'a pas
    // besoin de ce transport : son coprocesseur est dans le cœur.
    if (side == Processor::secondary && secondary_map_.take_halt_request()) {
        processor.halt();
    }
}

void Machine::raise_key_interrupts() noexcept {
    if (main_map_.key_interrupt().satisfied(input_.held())) {
        main_interrupts_.request(InterruptController::keys);
    }
    if (secondary_map_.key_interrupt().satisfied(input_.held())) {
        secondary_interrupts_.request(InterruptController::keys);
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

    // Le temps d'une ligne passe aussi pour les minuteries, à la même frontière
    // que le faisceau : une demande née là est prise au premier pas de la ligne
    // suivante, exactement comme celle du retour vertical.
    main_map_.timers().advance(cycles_per_line);
    secondary_map_.timers().advance(cycles_per_line);

    raise_key_interrupts();

    // Le retour horizontal appartient à la ligne qui s'achève, le retour
    // vertical à celle qui commence : les canaux qui les attendent s'arment donc
    // de part et d'autre du changement de ligne, comme les interruptions.
    main_map_.dma().trigger(DmaController::Timing::horizontal_blank);
    secondary_map_.dma().trigger(DmaController::Timing::horizontal_blank);

    display().advance_line();

    if (display().line() == DisplayController::visible_lines) {
        main_map_.dma().trigger(DmaController::Timing::vertical_blank);
        secondary_map_.dma().trigger(DmaController::Timing::vertical_blank);
    }
}

void Machine::run_frame(std::span<std::int32_t> framebuffer) {
    for (std::uint32_t line = 0; line < DisplayController::total_lines; ++line) {
        run_line(framebuffer);
    }
}

} // namespace ravenemu::nds
