#include "system/bios.hpp"

#include "crc16.hpp"
#include "cpu/cp15.hpp"

#include <array>

namespace ravenemu::nds {

namespace {

/**
 * Le gestionnaire d'interruption écrit pour RavenEmu, en instructions ARM.
 *
 * Six instructions et un littéral. Le processeur émulé les exécute vraiment :
 * le changement de mode, l'empilement et le retour restent donc ceux du
 * matériel, là où les simuler aurait demandé de refaire à la main ce que le cœur
 * sait déjà. Ce que ce code fait est ce que fait celui de la console : il
 * empile ce qu'un gestionnaire écrit en C est en droit d'écraser, saute au
 * gestionnaire du jeu par l'adresse que celui-ci a rangée, puis rend la main à
 * l'instruction interrompue.
 *
 * Le saut passe par `LDR PC`, qui entrelace sur cette architecture : un
 * gestionnaire écrit en Thumb, dont l'adresse porte le bit bas, est atteint dans
 * le bon jeu d'instructions.
 */
constexpr std::uint32_t handler_offset = 0x20;
constexpr std::array<std::uint32_t, 6> interrupt_handler{
    0xe92d'500fU,   // stmfd sp!, {r0-r3, r12, lr}
    0xe59f'000cU,   // ldr   r0, [pc, #12]   -> le littéral qui suit
    0xe28f'e000U,   // add   lr, pc, #0      -> retour juste après le saut
    0xe590'f000U,   // ldr   pc, [r0]        -> le gestionnaire du jeu
    0xe8bd'500fU,   // ldmfd sp!, {r0-r3, r12, lr}
    0xe25e'f004U,   // subs  pc, lr, #4
};

/** Emplacement du littéral qui porte l'adresse du pointeur du jeu. */
constexpr std::uint32_t handler_pointer_offset =
    handler_offset + static_cast<std::uint32_t>(interrupt_handler.size()) * 4U;

/**
 * Branchement sur soi-même.
 *
 * Il occupe les vecteurs qu'aucun service ne dessert. Une exception inattendue
 * s'y arrête donc visiblement, au lieu de traverser des octets nuls et de partir
 * à la dérive dans le reste de la région.
 */
constexpr std::uint32_t branch_to_self = 0xeaff'fffeU;

/** Étendue de la table des vecteurs : huit entrées de quatre octets. */
constexpr std::uint32_t vector_table_bytes = 0x20;

void write_word(std::span<std::uint8_t> region, std::uint32_t offset, std::uint32_t value) noexcept {
    for (std::uint32_t byte = 0; byte < 4U; ++byte) {
        region[offset + byte] = static_cast<std::uint8_t>((value >> (byte * 8U)) & 0xffU);
    }
}

} // namespace

Bios::Bios(
    Processor side,
    ArmCore& core,
    Bus& memory,
    InterruptController& interrupts,
    const Cp15* cp15
) noexcept
    : side_(side), core_(core), memory_(memory), interrupts_(interrupts), cp15_(cp15) {}

void Bios::reset() noexcept {
    wait_.reset();
    unsupported_ = 0;
    first_unsupported_ = 0;
}

void Bios::install(std::span<std::uint8_t> region) noexcept {
    reset();
    region_ = region;
    write_interrupt_vector(region);
}

void Bios::refresh_handler_pointer() noexcept {
    if (region_.size() < handler_pointer_offset + 4U) return;
    const auto wanted = interrupt_handler_address();
    if (wanted == written_handler_pointer_) return;
    write_word(region_, handler_pointer_offset, wanted);
    written_handler_pointer_ = wanted;
}

void Bios::write_interrupt_vector(std::span<std::uint8_t> region) noexcept {
    if (region.size() < handler_pointer_offset + 4U) return;

    for (std::uint32_t offset = 0; offset < vector_table_bytes; offset += 4U) {
        write_word(region, offset, branch_to_self);
    }
    // Le vecteur d'interruption saute juste après la table, où le gestionnaire
    // commence. Le déplacement d'un branchement se compte depuis deux
    // instructions plus loin, d'où un déplacement nul pour ces huit octets.
    write_word(region, ArmCore::irq_vector, 0xea00'0000U);

    std::uint32_t offset = handler_offset;
    for (const auto word : interrupt_handler) {
        write_word(region, offset, word);
        offset += 4U;
    }
    written_handler_pointer_ = interrupt_handler_address();
    write_word(region, handler_pointer_offset, written_handler_pointer_);
}

std::uint32_t Bios::interrupt_handler_address() const noexcept {
    if (!serves_main()) return secondary_handler_address;
    // Le processeur principal range le sien en bout de sa mémoire locale de
    // données, dont lui seul connaît la place : elle se déplace au gré de son
    // coprocesseur, et un jeu qui la déplace emporte le pointeur avec elle.
    //
    // Sans coprocesseur, ce processeur n'a pas de mémoire locale et cette
    // adresse n'a pas de sens : rendre zéro place le pointeur là où rien ne
    // répond, ce qui est visible, plutôt qu'à une adresse plausible et fausse.
    if (cp15_ == nullptr) return 0;
    const auto window = cp15_->dtcm_window();
    if (window.size < 4U) return 0;
    return static_cast<std::uint32_t>(window.base + window.size) - 4U;
}

std::uint32_t Bios::interrupt_flags_address() const noexcept {
    const auto handler = interrupt_handler_address();
    return handler < flags_below_handler ? 0U : handler - flags_below_handler;
}

std::uint32_t& Bios::reg(std::size_t index) noexcept {
    return core_.state().registers[index];
}

std::uint32_t Bios::flags() noexcept {
    return memory_.read32(interrupt_flags_address());
}

void Bios::clear_flags(std::uint32_t mask) noexcept {
    memory_.write32(interrupt_flags_address(), flags() & ~mask);
}

void Bios::repeat_call() noexcept {
    // Le compteur de programme a déjà avancé de deux instructions : c'est de là
    // qu'on revient sur l'appel, et la largeur dépend du jeu d'instructions.
    const auto ahead = core_.state().thumb() ? 4U : 8U;
    core_.branch_to(core_.state().registers[15] - ahead);
}

void Bios::note_unsupported(std::uint32_t number) noexcept {
    if (unsupported_ == 0U) first_unsupported_ = number;
    ++unsupported_;
}

bool Bios::handle_software_interrupt(std::uint32_t number) {
    switch (number) {
    case call_soft_reset:
        soft_reset();
        return true;
    case call_wait_by_loop:
        // Une boucle d'attente n'a pas de durée dans ce cœur, où rien ne compte
        // les cycles. Elle rend donc la main aussitôt, compteur épuisé : c'est
        // ce que le programme observerait à la fin de l'attente.
        reg(0) = 0;
        return true;
    case call_interrupt_wait:
        interrupt_wait(reg(0) != 0U, reg(1));
        return true;
    case call_vertical_blank_wait:
        interrupt_wait(true, InterruptController::vertical_blank);
        return true;
    case call_halt:
        core_.halt();
        return true;
    case call_divide:
        divide();
        return true;
    case call_copy:
        copy(false);
        return true;
    case call_fast_copy:
        copy(true);
        return true;
    case call_square_root:
        square_root();
        return true;
    case call_checksum:
        checksum();
        return true;
    case call_is_debugger:
        // Aucun matériel de mise au point : la console de développement a deux
        // fois plus de mémoire, et répondre autrement ferait prendre à un jeu
        // un chemin qui compte sur cette mémoire.
        reg(0) = 0;
        return true;
    case call_bit_unpack:
        bit_unpack();
        return true;
    case call_lz77_to_memory:
    case call_lz77_to_video:
        lz77();
        return true;
    case call_huffman:
        huffman();
        return true;
    case call_run_length_to_memory:
    case call_run_length_to_video:
        run_length();
        return true;
    case call_unfilter_8bit_to_memory:
    case call_unfilter_8bit_to_video:
        unfilter(false);
        return true;
    case call_unfilter_16bit:
        unfilter(true);
        return true;
    default:
        break;
    }
    // Un appel non couvert est **compté et passé**, non renvoyé au vecteur.
    //
    // Le renvoyer avait l’air prudent : le programme y aurait trouvé ce que la
    // mémoire contient, et un jeu qui installe son propre gestionnaire y aurait
    // été servi. Mais la table posée ici ne contient qu’un branchement sur
    // place, si bien qu’un seul service manquant arrêtait le processeur pour de
    // bon. On l’a vu : un processeur secondaire figé à l’adresse huit, et un
    // processeur principal qui tournait dans le vide en l’attendant.
    //
    // Passer est moins juste qu’un service rendu, et le relevé le dit en nommant
    // l’appel. Mais un service qui manque doit coûter ce service, pas la
    // console entière.
    note_unsupported(number);
    return true;
}

void Bios::soft_reset() {
    // Le programme d'amorçage repart à l'adresse que le jeu a rangée pour lui,
    // à une place que la console fixe et qui diffère selon le processeur.
    //
    // Ce qu'il fait de la mémoire n'est **pas** reproduit : il en efface une
    // partie, et l'étendue exacte n'est affirmée nulle part dans ce dépôt.
    // L'effet observable est le branchement, et c'est lui qui est rendu.
    const auto vector = serves_main() ? main_restart_vector : secondary_restart_vector;
    const auto entry = memory_.read32(vector);
    wait_.reset();
    core_.branch_to(entry);
}

void Bios::interrupt_wait(bool discard, std::uint32_t mask) {
    // L'attente rallume l'autorisation générale. Un jeu la coupe couramment
    // pour poser sa demande sans être interrompu au milieu ; la lui laisser
    // coupée l'endormirait définitivement.
    interrupts_.set_master_enable(1);

    // Une attente sans source n'attend rien : s'endormir dessus ne se
    // terminerait jamais, et le matériel ne le fait pas non plus.
    if (mask == 0U) {
        wait_.reset();
        return;
    }

    // Les anciens indicateurs ne sont écartés qu'au premier passage : l'appel se
    // repose la question à chaque réveil, et les écarter de nouveau effacerait
    // celui qu'on vient d'attendre.
    if (!wait_.has_value() && discard) clear_flags(mask);

    if ((flags() & mask) != 0U) {
        clear_flags(mask);
        wait_.reset();
        return;
    }

    wait_ = Wait{mask};
    repeat_call();
    core_.halt();
}

void Bios::divide() {
    const auto numerator = static_cast<std::int32_t>(reg(0));
    const auto denominator = static_cast<std::int32_t>(reg(1));
    // Une division par zéro n'a pas de résultat défini sur le matériel : les
    // registres sont laissés tels quels plutôt que remplis d'une valeur
    // inventée, et le programme n'apprend rien de faux.
    if (denominator == 0) return;

    // Le quotient se calcule sur soixante-quatre bits : la seule division qui
    // déborde est celle du plus petit entier négatif par moins un, et la faire
    // sur trente-deux bits serait un comportement indéfini.
    const auto wide_numerator = static_cast<std::int64_t>(numerator);
    const auto quotient = wide_numerator / denominator;
    const auto remainder = wide_numerator % denominator;

    reg(0) = static_cast<std::uint32_t>(quotient);
    reg(1) = static_cast<std::uint32_t>(remainder);
    reg(3) = quotient < 0 ? 0U - static_cast<std::uint32_t>(quotient)
                          : static_cast<std::uint32_t>(quotient);
}

void Bios::square_root() {
    const auto value = reg(0);
    // Racine entière par recherche binaire : elle ne dépend d'aucune
    // bibliothèque flottante, dont l'arrondi varierait d'une plateforme à
    // l'autre pour les grandes valeurs.
    //
    // Le produit tient sur trente-deux bits sans qu'il faille l'élargir : la
    // racine d'un mot vaut au plus 0xffff, et son carré 0xfffe'0001.
    std::uint32_t root = 0;
    for (std::uint32_t bit = 1U << 15U; bit != 0U; bit >>= 1U) {
        const auto candidate = root | bit;
        if (candidate * candidate <= value) root = candidate;
    }
    reg(0) = root;
}

void Bios::checksum() {
    const auto initial = static_cast<std::uint16_t>(reg(0));
    auto address = reg(1);
    const auto length = reg(2);

    std::uint16_t crc = initial;
    for (std::uint32_t offset = 0; offset < length; ++offset) {
        const std::array<std::uint8_t, 1> byte{memory_.read8(address + offset)};
        crc = detail::crc16(byte, crc);
    }
    reg(0) = crc;
}

void Bios::copy(bool fast) {
    const auto control = reg(2);
    const bool fixed_source = (control & (1U << 24U)) != 0U;
    // Le transfert rapide ne connaît que les mots, et arrondit son compte au
    // groupe de huit supérieur : c'est ce qui le rend rapide sur console.
    const bool words = fast || (control & (1U << 26U)) != 0U;
    auto count = control & 0x001f'ffffU;
    if (fast) count = (count + 7U) & ~7U;

    const auto unit = words ? 4U : 2U;
    auto source = reg(0) & ~(unit - 1U);
    auto destination = reg(1) & ~(unit - 1U);

    for (std::uint32_t index = 0; index < count; ++index) {
        if (words) {
            memory_.write32(destination, memory_.read32(source));
        } else {
            memory_.write16(destination, memory_.read16(source));
        }
        if (!fixed_source) source += unit;
        destination += unit;
    }
}

void Bios::bit_unpack() {
    auto source = reg(0);
    auto destination = reg(1);
    const auto parameters = reg(2);

    const auto source_bytes = memory_.read16(parameters);
    const std::uint32_t source_width = memory_.read8(parameters + 2U);
    const std::uint32_t destination_width = memory_.read8(parameters + 3U);
    const auto offset_word = memory_.read32(parameters + 4U);
    const auto offset = offset_word & 0x7fff'ffffU;
    const bool offset_zero = (offset_word & (1U << 31U)) != 0U;

    // Des largeurs hors de ces valeurs ne décrivent rien : le matériel ne les
    // définit pas, et deviner produirait une image plausible et fausse.
    if (source_width == 0U || source_width > 8U || destination_width == 0U ||
        destination_width > 32U || destination_width < source_width) {
        note_unsupported(call_bit_unpack);
        return;
    }

    const auto source_mask = static_cast<std::uint32_t>((1ULL << source_width) - 1ULL);
    std::uint64_t assembled = 0;
    std::uint32_t assembled_bits = 0;

    for (std::uint32_t index = 0; index < source_bytes; ++index) {
        const std::uint32_t byte = memory_.read8(source + index);
        for (std::uint32_t taken = 0; taken < 8U; taken += source_width) {
            const auto unit = (byte >> taken) & source_mask;
            auto value = unit;
            if (unit != 0U || offset_zero) value += offset;
            assembled |= static_cast<std::uint64_t>(value) << assembled_bits;
            assembled_bits += destination_width;
            if (assembled_bits < 32U) continue;
            memory_.write32(destination, static_cast<std::uint32_t>(assembled));
            destination += 4U;
            assembled >>= 32U;
            assembled_bits -= 32U;
        }
    }
    // Le dernier mot n'est écrit que s'il porte quelque chose : un mot de plus
    // écraserait un octet que le programme n'a pas demandé.
    if (assembled_bits != 0U) memory_.write32(destination, static_cast<std::uint32_t>(assembled));
}

void Bios::lz77() {
    auto source = reg(0);
    auto destination = reg(1);
    const auto header = memory_.read32(source);
    source += 4U;
    auto remaining = header >> 8U;
    if (remaining > max_output_bytes) remaining = max_output_bytes;
    const auto start = destination;

    while (remaining != 0U) {
        const std::uint32_t flags = memory_.read8(source++);
        for (std::uint32_t bit = 0; bit < 8U && remaining != 0U; ++bit) {
            if ((flags & (0x80U >> bit)) == 0U) {
                memory_.write8(destination++, memory_.read8(source++));
                --remaining;
                continue;
            }
            const std::uint32_t high = memory_.read8(source++);
            const std::uint32_t low = memory_.read8(source++);
            const auto length = ((high >> 4U) & 0xfU) + 3U;
            const auto distance = (((high & 0xfU) << 8U) | low) + 1U;
            // Une distance qui remonte avant le début de la sortie ne désigne
            // rien : le flux est corrompu, et poursuivre lirait de la mémoire au
            // hasard.
            if (destination - start < distance) return;
            for (std::uint32_t copied = 0; copied < length && remaining != 0U; ++copied) {
                memory_.write8(destination, memory_.read8(destination - distance));
                ++destination;
                --remaining;
            }
        }
    }
}

void Bios::huffman() {
    auto source = reg(0);
    auto destination = reg(1);
    const auto header = memory_.read32(source);
    const auto symbol_bits = header & 0xfU;
    auto remaining = header >> 8U;
    if (remaining > max_output_bytes) remaining = max_output_bytes;
    // Le format ne définit que des symboles de quatre ou huit bits.
    if (symbol_bits != 4U && symbol_bits != 8U) {
        note_unsupported(call_huffman);
        return;
    }

    const auto tree = source + 4U;
    const auto tree_bytes = (static_cast<std::uint32_t>(memory_.read8(tree)) + 1U) * 2U;
    auto stream = tree + tree_bytes;

    std::uint32_t assembled = 0;
    std::uint32_t assembled_bits = 0;
    std::uint32_t word = 0;
    std::uint32_t word_bits = 0;
    auto node = tree + 1U;

    while (remaining != 0U) {
        if (word_bits == 0U) {
            word = memory_.read32(stream);
            stream += 4U;
            word_bits = 32U;
        }
        const bool right = (word & 0x8000'0000U) != 0U;
        word <<= 1U;
        --word_bits;

        const std::uint32_t entry = memory_.read8(node);
        const bool leaf = ((entry >> (right ? 6U : 7U)) & 1U) != 0U;
        // L'enfant se trouve après l'octet courant, aligné sur deux, et le champ
        // de l'entrée compte ces paires.
        const auto child = ((node & ~1U) + ((entry & 0x3fU) + 1U) * 2U) + (right ? 1U : 0U);

        if (!leaf) {
            node = child;
            continue;
        }

        const auto symbol = static_cast<std::uint32_t>(memory_.read8(child));
        assembled |= (symbol & ((1U << symbol_bits) - 1U)) << assembled_bits;
        assembled_bits += symbol_bits;
        if (assembled_bits == 8U) {
            memory_.write8(destination++, static_cast<std::uint8_t>(assembled));
            assembled = 0;
            assembled_bits = 0;
            --remaining;
        }
        node = tree + 1U;
    }
}

void Bios::run_length() {
    auto source = reg(0);
    auto destination = reg(1);
    const auto header = memory_.read32(source);
    source += 4U;
    auto remaining = header >> 8U;
    if (remaining > max_output_bytes) remaining = max_output_bytes;

    while (remaining != 0U) {
        const std::uint32_t control = memory_.read8(source++);
        if ((control & 0x80U) != 0U) {
            const auto length = (control & 0x7fU) + 3U;
            const auto value = memory_.read8(source++);
            for (std::uint32_t index = 0; index < length && remaining != 0U; ++index) {
                memory_.write8(destination++, value);
                --remaining;
            }
            continue;
        }
        const auto length = (control & 0x7fU) + 1U;
        for (std::uint32_t index = 0; index < length && remaining != 0U; ++index) {
            memory_.write8(destination++, memory_.read8(source++));
            --remaining;
        }
    }
}

void Bios::unfilter(bool halfwords) {
    auto source = reg(0);
    auto destination = reg(1);
    const auto header = memory_.read32(source);
    source += 4U;
    auto remaining = header >> 8U;
    if (remaining > max_output_bytes) remaining = max_output_bytes;

    // Le flux ne porte que des différences : chaque valeur s'obtient en ajoutant
    // celle qu'on vient d'écrire. La première part de zéro.
    if (halfwords) {
        std::uint16_t previous = 0;
        for (; remaining >= 2U; remaining -= 2U) {
            previous = static_cast<std::uint16_t>(previous + memory_.read16(source));
            source += 2U;
            memory_.write16(destination, previous);
            destination += 2U;
        }
        return;
    }
    std::uint8_t previous = 0;
    for (; remaining != 0U; --remaining) {
        previous = static_cast<std::uint8_t>(previous + memory_.read8(source++));
        memory_.write8(destination++, previous);
    }
}

} // namespace ravenemu::nds
