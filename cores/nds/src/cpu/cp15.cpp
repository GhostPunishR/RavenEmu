#include "cpu/cp15.hpp"

#include <algorithm>

namespace ravenemu::nds {

namespace {

/**
 * Étendue codée par un champ de taille, en octets.
 *
 * Le calcul se fait sur soixante-quatre bits : le champ va jusqu'à trente et un,
 * et `512 << 31` ne tient pas dans un mot. Une fenêtre plus grande que l'espace
 * d'adressage n'a pas de sens matériel, mais la déborder silencieusement en
 * aurait encore moins.
 */
constexpr std::uint64_t window_size(std::uint32_t code) noexcept {
    return std::uint64_t{512} << (code & 0x1fU);
}

/** Lit [width] octets en petit-boutiste dans un tableau. */
[[nodiscard]] std::uint32_t read_bytes(
    const std::vector<std::uint8_t>& memory,
    std::uint32_t offset,
    std::uint32_t width
) noexcept {
    std::uint32_t value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto position = (offset + index) % static_cast<std::uint32_t>(memory.size());
        value |= static_cast<std::uint32_t>(memory[position]) << (index * 8U);
    }
    return value;
}

void write_bytes(
    std::vector<std::uint8_t>& memory,
    std::uint32_t offset,
    std::uint32_t width,
    std::uint32_t value
) noexcept {
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto position = (offset + index) % static_cast<std::uint32_t>(memory.size());
        memory[position] = static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU);
    }
}

/**
 * Convertit les permissions étendues, quatre bits par région, vers la forme
 * courte à deux bits que le même registre expose sous un autre numéro.
 */
constexpr std::uint32_t to_short_permissions(std::uint32_t extended) noexcept {
    std::uint32_t compact = 0;
    for (std::uint32_t region = 0; region < 8U; ++region) {
        const auto field = (extended >> (region * 4U)) & 0x3U;
        compact |= field << (region * 2U);
    }
    return compact;
}

constexpr std::uint32_t to_extended_permissions(std::uint32_t compact) noexcept {
    std::uint32_t extended = 0;
    for (std::uint32_t region = 0; region < 8U; ++region) {
        const auto field = (compact >> (region * 2U)) & 0x3U;
        extended |= field << (region * 4U);
    }
    return extended;
}

/** Masque d'un registre de région : base alignée sur quatre kilooctets, taille, activation. */
constexpr std::uint32_t region_mask = 0xffff'f03f;

} // namespace

Cp15::Cp15() : itcm_(itcm_bytes, 0), dtcm_(dtcm_bytes, 0) {}

void Cp15::reset() noexcept {
    control_ = control_read_as_one;
    data_cachable_ = 0;
    instruction_cachable_ = 0;
    bufferable_ = 0;
    data_permissions_ = 0;
    instruction_permissions_ = 0;
    std::fill(std::begin(regions_), std::end(regions_), 0U);
    data_lockdown_ = 0;
    instruction_lockdown_ = 0;
    dtcm_region_ = 0;
    itcm_region_ = 0;
    halt_requested_ = false;
    unknown_accesses_ = 0;
    first_unknown_ = 0;
    // Le contenu des mémoires locales est indéfini à la mise sous tension. Le
    // mettre à zéro rend le cœur reproductible : deux exécutions du même
    // programme ne peuvent pas diverger sur de la mémoire jamais écrite.
    std::fill(itcm_.begin(), itcm_.end(), std::uint8_t{0});
    std::fill(dtcm_.begin(), dtcm_.end(), std::uint8_t{0});
}

void Cp15::note_unknown(
    std::uint32_t operation,
    std::uint32_t primary,
    std::uint32_t secondary,
    std::uint32_t sub_operation
) noexcept {
    const auto encoded = (operation << 12U) | (primary << 8U) | (secondary << 4U) | sub_operation;
    if (unknown_accesses_ == 0U) first_unknown_ = encoded;
    ++unknown_accesses_;
}

std::uint32_t Cp15::read(
    std::uint32_t operation,
    std::uint32_t primary,
    std::uint32_t secondary,
    std::uint32_t sub_operation
) noexcept {
    // Toutes les opérations du CP15 de ce cœur passent par l'opération primaire
    // zéro ; les autres n'existent pas.
    if (operation != 0U) {
        note_unknown(operation, primary, secondary, sub_operation);
        return 0;
    }

    switch (primary) {
    case 0:
        // Les numéros non attribués de ce registre renvoient l'identifiant
        // principal plutôt que zéro, ce qui évite qu'un logiciel conclue à
        // l'absence de processeur.
        switch (sub_operation) {
        case 1: return cache_type;
        case 2: return tcm_size;
        default: return main_id;
        }
    case 1:
        if (secondary == 0U && sub_operation == 0U) return control_;
        break;
    case 2:
        if (secondary == 0U && sub_operation == 0U) return data_cachable_;
        if (secondary == 0U && sub_operation == 1U) return instruction_cachable_;
        break;
    case 3:
        if (secondary == 0U && sub_operation == 0U) return bufferable_;
        break;
    case 5:
        if (secondary != 0U) break;
        switch (sub_operation) {
        case 0: return to_short_permissions(data_permissions_);
        case 1: return to_short_permissions(instruction_permissions_);
        case 2: return data_permissions_;
        case 3: return instruction_permissions_;
        default: break;
        }
        break;
    case 6:
        // Les régions sont unifiées : les deux numéros d'opération secondaire
        // désignent le même registre.
        if (secondary < 8U && sub_operation < 2U) return regions_[secondary];
        break;
    case 9:
        if (secondary == 0U && sub_operation == 0U) return data_lockdown_;
        if (secondary == 0U && sub_operation == 1U) return instruction_lockdown_;
        if (secondary == 1U && sub_operation == 0U) return dtcm_region_;
        if (secondary == 1U && sub_operation == 1U) return itcm_region_;
        break;
    default:
        break;
    }

    note_unknown(operation, primary, secondary, sub_operation);
    return 0;
}

void Cp15::write(
    std::uint32_t operation,
    std::uint32_t primary,
    std::uint32_t secondary,
    std::uint32_t sub_operation,
    std::uint32_t value
) noexcept {
    if (operation != 0U) {
        note_unknown(operation, primary, secondary, sub_operation);
        return;
    }

    switch (primary) {
    case 1:
        if (secondary == 0U && sub_operation == 0U) {
            control_ = (value & control_writable) | control_read_as_one;
            return;
        }
        break;
    case 2:
        if (secondary == 0U && sub_operation == 0U) { data_cachable_ = value & 0xffU; return; }
        if (secondary == 0U && sub_operation == 1U) { instruction_cachable_ = value & 0xffU; return; }
        break;
    case 3:
        if (secondary == 0U && sub_operation == 0U) { bufferable_ = value & 0xffU; return; }
        break;
    case 5:
        if (secondary != 0U) break;
        switch (sub_operation) {
        // Écrire la forme courte étend chaque champ de deux bits sur quatre :
        // c'est le même registre vu autrement, pas un second registre.
        case 0: data_permissions_ = to_extended_permissions(value); return;
        case 1: instruction_permissions_ = to_extended_permissions(value); return;
        case 2: data_permissions_ = value; return;
        case 3: instruction_permissions_ = value; return;
        default: break;
        }
        break;
    case 6:
        if (secondary < 8U && sub_operation < 2U) {
            regions_[secondary] = value & region_mask;
            return;
        }
        break;
    case 7:
        // Opérations de cache. Aucun cache n'est modélisé, donc les vider ou
        // les nettoyer ne fait rien ; seule l'attente d'interruption a un effet
        // observable.
        if (secondary == 0U && sub_operation == 4U) { halt_requested_ = true; return; }
        return;
    case 9:
        if (secondary == 0U && sub_operation == 0U) { data_lockdown_ = value; return; }
        if (secondary == 0U && sub_operation == 1U) { instruction_lockdown_ = value; return; }
        if (secondary == 1U && sub_operation == 0U) { dtcm_region_ = value & region_mask; return; }
        if (secondary == 1U && sub_operation == 1U) { itcm_region_ = value & region_mask; return; }
        break;
    default:
        break;
    }

    note_unknown(operation, primary, secondary, sub_operation);
}

Cp15::Window Cp15::itcm_window() const noexcept {
    // La base de l'ITCM est câblée à zéro sur ce cœur : le champ d'adresse du
    // registre existe mais n'est pas consulté.
    return {0U, window_size((itcm_region_ >> 1U) & 0x1fU)};
}

Cp15::Window Cp15::dtcm_window() const noexcept {
    return {dtcm_region_ & 0xffff'f000U, window_size((dtcm_region_ >> 1U) & 0x1fU)};
}

bool Cp15::itcm_covers(std::uint32_t address) const noexcept {
    return (control_ & itcm_enable) != 0U && itcm_window().contains(address);
}

bool Cp15::dtcm_covers(std::uint32_t address) const noexcept {
    return (control_ & dtcm_enable) != 0U && dtcm_window().contains(address);
}

bool Cp15::fetch(std::uint32_t address, std::uint32_t width, std::uint32_t& value) const noexcept {
    // En mode chargement, la mémoire locale ne répond plus aux lectures : c'est
    // ce qui permet de la remplir depuis la mémoire extérieure, en lisant
    // dehors et en écrivant dedans.
    if (!itcm_covers(address) || (control_ & itcm_load_mode) != 0U) return false;
    // La base de l'ITCM est zéro : l'adresse est déjà son propre décalage.
    value = read_bytes(itcm_, address, width);
    return true;
}

bool Cp15::load(std::uint32_t address, std::uint32_t width, std::uint32_t& value) const noexcept {
    // La DTCM passe devant l'ITCM quand les deux couvrent la même adresse.
    if (dtcm_covers(address) && (control_ & dtcm_load_mode) == 0U) {
        value = read_bytes(dtcm_, address - dtcm_window().base, width);
        return true;
    }
    return fetch(address, width, value);
}

bool Cp15::store(std::uint32_t address, std::uint32_t width, std::uint32_t value) noexcept {
    if (dtcm_covers(address)) {
        write_bytes(dtcm_, address - dtcm_window().base, width, value);
        return true;
    }
    if (itcm_covers(address)) {
        write_bytes(itcm_, address, width, value);
        return true;
    }
    return false;
}

} // namespace ravenemu::nds
