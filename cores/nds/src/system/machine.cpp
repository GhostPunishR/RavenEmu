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

    // La console recopie le début de l’en-tête tout en haut de la mémoire
    // principale avant de rendre la main au jeu, et des jeux du commerce y
    // relisent leur propre identité plutôt que de la redemander à la cartouche.
    //
    // Ce cœur ne fait pas tourner le programme de la console : il pose donc
    // cette copie lui-même, faute de quoi le jeu lit des zéros. Elle précède le
    // chargement des deux blocs, comme sur console : un bloc assez gros pour
    // atteindre cette zone la recouvre, et c’est bien ce qui arriverait.
    load_block(main_map_, rom, 0, header_copy_address, header_copy_bytes);

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
    // Un processeur arrêté ne compte pas : sans cette distinction, deux mille
    // pas d'attente ressembleraient à deux mille instructions exécutées, et un
    // programme figé passerait pour un programme qui travaille.
    if (!processor.halted()) {
        if (side == Processor::main) ++main_steps_; else ++secondary_steps_;
    }

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

std::int32_t Machine::count_non_black(std::span<const std::int32_t> framebuffer) noexcept {
    std::int32_t lit = 0;
    for (const auto pixel : framebuffer) {
        // Seules les trois composantes comptent : une image opaque et noire
        // reste noire, et la transparence n'est pas une couleur allumée.
        if ((static_cast<std::uint32_t>(pixel) & 0x00ff'ffffU) != 0U) ++lit;
    }
    return lit;
}

void Machine::run_frame(std::span<std::int32_t> framebuffer) {
    main_steps_ = 0;
    secondary_steps_ = 0;
    for (std::uint32_t line = 0; line < DisplayController::total_lines; ++line) {
        run_line(framebuffer);
    }
    main_steps_last_frame_ = main_steps_;
    secondary_steps_last_frame_ = secondary_steps_;
    non_black_pixels_ = count_non_black(framebuffer);
}

NdsDebugSnapshot Machine::report() const noexcept {
    const auto number = [](std::uint32_t value) {
        return static_cast<std::int32_t>(value);
    };
    // Les deux moteurs graphiques comptent chacun de son côté ; un écran noir ne
    // dit pas lequel des deux a buté, et la somme suffit à dire qu'il y a buté.
    const auto& main_engine = video_.engine(Engine::main);
    const auto& secondary_engine = video_.engine(Engine::secondary);
    return NdsDebugSnapshot{
        .main_instructions = main_steps_last_frame_,
        .secondary_instructions = secondary_steps_last_frame_,
        .main_program_counter = number(main_core_.state().registers[15]),
        .secondary_program_counter = number(secondary_core_.state().registers[15]),
        .main_halted = main_core_.halted(),
        .secondary_halted = secondary_core_.halted(),
        .main_undefined_count = number(main_core_.unimplemented_count()),
        .main_first_undefined = number(main_core_.first_unimplemented()),
        .secondary_undefined_count = number(secondary_core_.unimplemented_count()),
        .secondary_first_undefined = number(secondary_core_.first_unimplemented()),
        .main_unimplemented_io = number(main_map_.unimplemented_io_count()),
        .main_first_unimplemented_io = number(main_map_.first_unimplemented_io()),
        .secondary_unimplemented_io = number(secondary_map_.unimplemented_io_count()),
        .secondary_first_unimplemented_io = number(secondary_map_.first_unimplemented_io()),
        .main_unsupported_swi = number(main_bios_.unsupported_count()),
        .secondary_unsupported_swi = number(secondary_bios_.unsupported_count()),
        .main_display_control = number(main_engine.display_control()),
        .secondary_display_control = number(secondary_engine.display_control()),
        .unimplemented_layers = number(
            main_engine.unimplemented_layer_count() + secondary_engine.unimplemented_layer_count()),
        .unimplemented_display = number(
            main_engine.unimplemented_display_count()
                + secondary_engine.unimplemented_display_count()),
        .unimplemented_objects = number(
            main_engine.unimplemented_object_count()
                + secondary_engine.unimplemented_object_count()),
        .non_black_pixels = non_black_pixels_,
        .screens_swapped = video_.display().swapped(),
        .cartridge_unsupported = number(cartridge_.unsupported_count()),
    };
}

} // namespace ravenemu::nds
