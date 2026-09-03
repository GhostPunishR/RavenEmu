#include "system/cartridge.hpp"

#include "system/registers.hpp"

namespace ravenemu::nds {

namespace {

/**
 * Ce que lit un programme quand la cartouche n'a rien à dire.
 *
 * Des octets à un, et non des zéros. Un bus au repos n'est tiré par personne, et
 * zéro serait une donnée plausible qu'un programme prendrait pour un contenu.
 */
constexpr std::uint32_t idle_word = 0xffff'ffffU;

/** Taille de bloc annoncée par le champ, hors ses deux valeurs particulières. */
constexpr std::uint32_t base_block_bytes = 0x100;

/** Vrai quand [address] tombe dans les [size] octets à partir de [base]. */
[[nodiscard]] constexpr bool within(
    std::uint32_t address,
    std::uint32_t base,
    std::uint32_t size
) noexcept {
    return address >= base && address - base < size;
}

} // namespace

std::uint32_t Cartridge::block_bytes(std::uint32_t field) noexcept {
    // Le champ n'est pas une puissance de deux qu'on lirait directement : ses
    // deux extrémités sont des cas à part. Zéro ne demande aucune donnée, et la
    // valeur haute en demande un seul mot, non le double de la précédente.
    if ((field & block_size_mask) == 0U) return 0;
    if ((field & block_size_mask) == block_size_mask) return 4;
    return base_block_bytes << ((field & block_size_mask) - 1U);
}

void Cartridge::reset() noexcept {
    command_ = {};
    control_ = 0;
    auxiliary_ = 0;
    cursor_ = 0;
    remaining_ = 0;
    constant_source_ = false;
    constant_value_ = 0;
    owner_ = Processor::main;
    unsupported_ = 0;
    first_unsupported_ = 0;
}

void Cartridge::insert(std::span<const std::uint8_t> image) noexcept {
    image_ = image;
}

std::uint8_t Cartridge::command_byte(std::size_t index) const noexcept {
    return command_[index];
}

void Cartridge::set_command_byte(std::size_t index, std::uint8_t value) noexcept {
    command_[index] = value;
}

void Cartridge::note_unsupported(std::uint8_t command) noexcept {
    if (unsupported_ == 0U) first_unsupported_ = command;
    ++unsupported_;
}

std::uint32_t Cartridge::word_at(std::uint32_t offset) const noexcept {
    std::uint32_t value = 0;
    for (std::uint32_t byte = 0; byte < 4U; ++byte) {
        const auto index = static_cast<std::size_t>(offset) + byte;
        // Au-delà de l'image, la cartouche ne tire rien : le bus reste à un.
        const std::uint32_t read = index < image_.size() ? image_[index] : 0xffU;
        value |= read << (byte * 8U);
    }
    return value;
}

void Cartridge::set_control(std::uint32_t value) noexcept {
    const bool was_started = (control_ & start) != 0U;
    // Les deux bits du matériel ne s'écrivent pas : l'un dit qu'un mot attend,
    // l'autre qu'un transfert court, et laisser le jeu les poser lui donnerait
    // le pouvoir de se mentir sur l'état du bus.
    control_ = (control_ & ~writable_control) | (value & writable_control);

    if (was_started || (value & start) == 0U) return;
    control_ |= start;
    begin_transfer();
}

void Cartridge::begin_transfer() noexcept {
    const auto bytes = block_bytes(control_ >> block_size_shift);
    remaining_ = bytes / 4U;
    constant_source_ = false;
    constant_value_ = idle_word;

    switch (command_[0]) {
    case command_read: {
        // Les quatre octets qui suivent portent l'adresse, poids fort d'abord :
        // c'est l'ordre du bus, et non celui de la mémoire du processeur.
        cursor_ = (static_cast<std::uint32_t>(command_[1]) << 24U) |
            (static_cast<std::uint32_t>(command_[2]) << 16U) |
            (static_cast<std::uint32_t>(command_[3]) << 8U) |
            static_cast<std::uint32_t>(command_[4]);
        break;
    }
    case command_chip_id:
        constant_source_ = true;
        constant_value_ = chip_id;
        break;
    default:
        // Les autres commandes appartiennent aux phases d'amorçage de la
        // console, qui chiffrent leurs échanges. Elles rendent un bus au repos
        // plutôt qu'un contenu inventé, et sont comptées.
        note_unsupported(command_[0]);
        constant_source_ = true;
        constant_value_ = idle_word;
        break;
    }

    // Un bloc vide n'a rien à rendre : le transfert s'achève sans qu'un seul mot
    // soit lu, et le programme qui scrute l'indicateur ne l'attend pas en vain.
    if (remaining_ == 0U) {
        finish_transfer();
        return;
    }
    control_ |= data_ready;
}

void Cartridge::finish_transfer() noexcept {
    control_ &= ~(start | data_ready);
    if ((auxiliary_ & transfer_interrupt) == 0U) return;
    // L'interruption va au processeur qui tient le port, et à lui seul : c'est
    // lui qui a demandé le transfert.
    auto& interrupts = owner_ == Processor::main ? main_interrupts_ : secondary_interrupts_;
    interrupts.request(InterruptController::cartridge);
}

bool Cartridge::read_register(
    Processor side,
    std::uint32_t address,
    std::uint32_t width,
    std::uint32_t& value
) noexcept {
    const bool held = side == owner_;

    // Le port de données est un port de mots : c'est ainsi que le matériel le
    // présente, et le lire par morceaux retirerait plusieurs mots pour un seul.
    if (address == registers::cartridge_data && width == 4U) {
        value = held ? read_data() : idle_word;
        return true;
    }
    if (!covers(address, width)) return false;

    value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        value |= static_cast<std::uint32_t>(register_byte(address + index, held)) << (index * 8U);
    }
    return true;
}

bool Cartridge::write_register(
    Processor side,
    std::uint32_t address,
    std::uint32_t width,
    std::uint32_t value
) noexcept {
    // Le port de données ne s'écrit pas : c'est le matériel qui l'alimente.
    if (address == registers::cartridge_data) return true;
    if (!covers(address, width)) return false;

    // Un processeur qui ne tient pas le port n'écrit rien, mais l'adresse lui
    // est bien décodée : la compter comme inconnue serait faux.
    if (side != owner_) return true;

    for (std::uint32_t index = 0; index < width; ++index) {
        write_register_byte(address + index, static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU));
    }
    return true;
}

bool Cartridge::covers(std::uint32_t address, std::uint32_t width) noexcept {
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        const bool known = within(byte_address, registers::cartridge_control, 4U) ||
            within(byte_address, registers::cartridge_auxiliary, 2U) ||
            within(byte_address, registers::cartridge_command, command_bytes);
        if (!known) return false;
    }
    return width != 0U;
}

std::uint8_t Cartridge::register_byte(std::uint32_t address, bool held) const noexcept {
    // Sans le port, la cartouche se lit comme si elle était absente.
    if (!held) return 0xffU;
    if (within(address, registers::cartridge_control, 4U)) {
        return registers::byte_of(control_, address - registers::cartridge_control);
    }
    if (within(address, registers::cartridge_auxiliary, 2U)) {
        return registers::byte_of(auxiliary_, address - registers::cartridge_auxiliary);
    }
    return command_[address - registers::cartridge_command];
}

void Cartridge::write_register_byte(std::uint32_t address, std::uint8_t byte) noexcept {
    if (within(address, registers::cartridge_control, 4U)) {
        // L'allumage se décide sur le registre entier : écrit octet par octet,
        // le transfert ne part qu'au dernier, celui qui porte le bit d'allumage.
        set_control(registers::with_byte(control_, address - registers::cartridge_control, byte));
        return;
    }
    if (within(address, registers::cartridge_auxiliary, 2U)) {
        auxiliary_ = static_cast<std::uint16_t>(registers::with_byte(
            auxiliary_, address - registers::cartridge_auxiliary, byte));
        return;
    }
    command_[address - registers::cartridge_command] = byte;
}

std::uint32_t Cartridge::read_data() noexcept {
    if (remaining_ == 0U) return idle_word;

    const auto value = constant_source_ ? constant_value_ : word_at(cursor_);
    if (!constant_source_) cursor_ += 4U;
    --remaining_;
    if (remaining_ == 0U) finish_transfer();
    return value;
}

} // namespace ravenemu::nds
