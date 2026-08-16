#include "cpu/arm9.hpp"

#include "cpu/bits.hpp"

#include <bit>

namespace ravenemu::nds {

using detail::bit;
using detail::bits;
using detail::rotate_right;
using detail::sign_extend;

/**
 * Jeu Thumb.
 *
 * Thumb code les instructions sur seize bits au lieu de trente-deux, en
 * échangeant de l'expressivité contre de la densité : trois bits de registre au
 * lieu de quatre, donc R0 à R7 seulement dans la plupart des formes, pas de
 * champ de condition, et des indicateurs écrits d'office. C'est ce qui fait
 * qu'une grande partie du code d'un jeu de la console est en Thumb, et donc
 * qu'un cœur qui ne le connaît pas ne fait tourner à peu près rien.
 *
 * L'écriture d'office des indicateurs est le piège du jeu : là où ARM demande
 * un bit `S` explicite, presque toutes les opérations Thumb écrivent N et Z, et
 * certaines seulement C et V. Les exceptions — opérations sur registres hauts,
 * calculs d'adresse, ajustement de pile — sont notées à mesure, parce qu'un
 * indicateur écrit de trop est une faute qui ne se voit que plusieurs
 * instructions plus loin.
 */

void Arm9::execute_thumb(std::uint32_t opcode) {
    switch (bits(opcode, 13, 3)) {
    case 0x0:
        // Le décalage immédiat occupe l'espace, sauf une entaille où ARM a rangé
        // l'addition et la soustraction à trois opérandes.
        if (bits(opcode, 11, 2) == 0x3U) thumb_add_subtract(opcode);
        else thumb_shift_immediate(opcode);
        return;
    case 0x1:
        thumb_immediate_operation(opcode);
        return;
    case 0x2:
        if (bits(opcode, 10, 3) == 0x0U) { thumb_alu_operation(opcode); return; }
        if (bits(opcode, 10, 3) == 0x1U) { thumb_high_register(opcode); return; }
        if (bits(opcode, 11, 2) == 0x1U) { thumb_load_pc_relative(opcode); return; }
        thumb_transfer_register_offset(opcode);
        return;
    case 0x3:
        thumb_transfer_immediate_offset(opcode);
        return;
    case 0x4:
        if (bit(opcode, 12)) thumb_transfer_stack(opcode);
        else thumb_transfer_halfword(opcode);
        return;
    case 0x5:
        if (!bit(opcode, 12)) { thumb_load_address(opcode); return; }
        if (bits(opcode, 8, 5) == 0x10U) { thumb_adjust_stack(opcode); return; }
        if (bits(opcode, 9, 2) == 0x2U) { thumb_push_pop(opcode); return; }
        // Le reste de ce bloc contient le point d'arrêt matériel et des trous
        // que d'autres révisions d'architecture ont depuis comblés.
        raise_undefined(opcode);
        return;
    case 0x6:
        if (!bit(opcode, 12)) { thumb_block_transfer(opcode); return; }
        if (bits(opcode, 8, 4) == 0xfU) {
            enter_exception(
                CpuMode::supervisor,
                software_interrupt_vector,
                state_.registers[15] - 2U,
                false
            );
            return;
        }
        if (bits(opcode, 8, 4) == 0xeU) { raise_undefined(opcode); return; }
        thumb_conditional_branch(opcode);
        return;
    default:
        // Quatre motifs se partagent ce bloc et ne se distinguent qu'aux cinq
        // bits hauts : le branchement simple, le préfixe d'appel long, et les
        // deux suffixes — celui qui reste en Thumb et celui qui bascule en ARM.
        if (bits(opcode, 11, 5) == 0x1cU) { thumb_branch(opcode); return; }
        thumb_long_branch(opcode);
        return;
    }
}

void Arm9::thumb_shift_immediate(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto rs = bits(opcode, 3, 3);
    const auto amount = bits(opcode, 6, 5);
    const auto type = bits(opcode, 11, 2);

    // Forme immédiate du décaleur : un décalage nul n'y est neutre que pour LSL,
    // les deux autres types y codant 32.
    const auto shifted = apply_shift(read_register(rs), type, amount, true, state_.flag(psr::carry));
    write_register(rd, shifted.value);
    set_logical_flags(shifted.value, shifted.carry);
}

void Arm9::thumb_add_subtract(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto rs = bits(opcode, 3, 3);
    const auto operand = bit(opcode, 10) ? bits(opcode, 6, 3) : read_register(bits(opcode, 6, 3));
    const auto left = read_register(rs);

    if (bit(opcode, 9)) {
        const auto result = left - operand;
        write_register(rd, result);
        set_arithmetic_flags(result, left, operand, left >= operand, true);
    } else {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + operand;
        const auto result = static_cast<std::uint32_t>(wide);
        write_register(rd, result);
        set_arithmetic_flags(result, left, operand, wide > 0xffff'ffffULL, false);
    }
}

void Arm9::thumb_immediate_operation(std::uint32_t opcode) {
    const auto rd = bits(opcode, 8, 3);
    const auto value = bits(opcode, 0, 8);
    const auto left = read_register(rd);

    switch (bits(opcode, 11, 2)) {
    case 0x0:
        // L'immédiate n'a pas de rotation : il n'y a pas de retenue à en tirer,
        // et C reste donc tel qu'il était.
        write_register(rd, value);
        set_logical_flags(value, state_.flag(psr::carry));
        return;
    case 0x1: {
        const auto result = left - value;
        set_arithmetic_flags(result, left, value, left >= value, true);
        return;
    }
    case 0x2: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + value;
        const auto result = static_cast<std::uint32_t>(wide);
        write_register(rd, result);
        set_arithmetic_flags(result, left, value, wide > 0xffff'ffffULL, false);
        return;
    }
    default: {
        const auto result = left - value;
        write_register(rd, result);
        set_arithmetic_flags(result, left, value, left >= value, true);
        return;
    }
    }
}

void Arm9::thumb_alu_operation(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto rs = bits(opcode, 3, 3);
    const auto left = read_register(rd);
    const auto right = read_register(rs);
    const auto carry_in = state_.flag(psr::carry) ? 1U : 0U;
    // Les opérations logiques et les multiplications ne touchent ni C ni V :
    // réinjecter la retenue courante est la façon la plus sûre de le dire.
    const auto carry_kept = state_.flag(psr::carry);

    switch (bits(opcode, 6, 4)) {
    case 0x0: {
        const auto result = left & right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    case 0x1: {
        const auto result = left ^ right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    case 0x2:
    case 0x3:
    case 0x4:
    case 0x7: {
        // Décalages par registre : un décalage nul y est neutre, retenue
        // comprise, contrairement à la forme immédiate.
        const auto operation = bits(opcode, 6, 4);
        const std::uint32_t type =
            operation == 0x2U ? 0U : operation == 0x3U ? 1U : operation == 0x4U ? 2U : 3U;
        const auto shifted = apply_shift(left, type, right & 0xffU, false, carry_kept);
        write_register(rd, shifted.value);
        set_logical_flags(shifted.value, shifted.carry);
        return;
    }
    case 0x5: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + right + carry_in;
        const auto result = static_cast<std::uint32_t>(wide);
        write_register(rd, result);
        set_arithmetic_flags(result, left, right, wide > 0xffff'ffffULL, false);
        return;
    }
    case 0x6: {
        const std::uint64_t wide =
            static_cast<std::uint64_t>(left) - right - (carry_in ^ 1U);
        const auto result = static_cast<std::uint32_t>(wide);
        const auto carry = static_cast<std::uint64_t>(left) >=
            static_cast<std::uint64_t>(right) + (carry_in ^ 1U);
        write_register(rd, result);
        set_arithmetic_flags(result, left, right, carry, true);
        return;
    }
    case 0x8:
        set_logical_flags(left & right, carry_kept);
        return;
    case 0x9: {
        // NEG est une soustraction inversée : zéro moins l'opérande.
        const auto result = 0U - right;
        write_register(rd, result);
        set_arithmetic_flags(result, 0U, right, right == 0U, true);
        return;
    }
    case 0xa: {
        const auto result = left - right;
        set_arithmetic_flags(result, left, right, left >= right, true);
        return;
    }
    case 0xb: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + right;
        set_arithmetic_flags(
            static_cast<std::uint32_t>(wide), left, right, wide > 0xffff'ffffULL, false
        );
        return;
    }
    case 0xc: {
        const auto result = left | right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    case 0xd: {
        const auto result = left * right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    case 0xe: {
        const auto result = left & ~right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    default: {
        const auto result = ~right;
        write_register(rd, result);
        set_logical_flags(result, carry_kept);
        return;
    }
    }
}

void Arm9::thumb_high_register(std::uint32_t opcode) {
    // Seule forme du jeu qui atteigne R8 à R15 : le quatrième bit de chaque
    // numéro de registre est rangé à part.
    const auto rd = bits(opcode, 0, 3) | (bit(opcode, 7) ? 8U : 0U);
    const auto rs = bits(opcode, 3, 3) | (bit(opcode, 6) ? 8U : 0U);

    switch (bits(opcode, 8, 2)) {
    case 0x0: {
        // Aucun indicateur : c'est ce qui rend cette forme utilisable pour
        // manipuler des adresses sans perdre le résultat d'une comparaison.
        const auto result = read_register(rd) + read_register(rs);
        if (rd == 15U) write_register(15, result & ~1U);
        else write_register(rd, result);
        return;
    }
    case 0x1: {
        const auto left = read_register(rd);
        const auto right = read_register(rs);
        set_arithmetic_flags(left - right, left, right, left >= right, true);
        return;
    }
    case 0x2: {
        const auto value = read_register(rs);
        if (rd == 15U) write_register(15, value & ~1U);
        else write_register(rd, value);
        return;
    }
    default: {
        const auto target = read_register(rs);
        if (bit(opcode, 7)) write_register(14, (state_.registers[15] - 2U) | 1U);
        state_.set_flag(psr::thumb, bit(target, 0));
        write_register(15, target & ~1U);
        return;
    }
    }
}

void Arm9::thumb_load_pc_relative(std::uint32_t opcode) {
    // Le compteur de programme est aligné sur le mot avant l'addition : une
    // instruction Thumb peut se trouver au milieu d'un mot, pas la table de
    // constantes qu'elle vise. C'est ici la seule mise en forme de l'adresse —
    // réaligner une seconde fois à l'accès rendrait celle-ci indifférente, donc
    // impossible à éprouver.
    const auto base = state_.registers[15] & ~3U;
    write_register(bits(opcode, 8, 3), bus_.read32(base + (bits(opcode, 0, 8) << 2U)));
}

void Arm9::thumb_transfer_register_offset(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto address = read_register(bits(opcode, 3, 3)) + read_register(bits(opcode, 6, 3));

    if (bit(opcode, 9)) {
        switch (bits(opcode, 10, 2)) {
        case 0x0: bus_.write16(address & ~1U, static_cast<std::uint16_t>(read_register(rd))); break;
        case 0x1: write_register(rd, sign_extend(bus_.read8(address), 8)); break;
        case 0x2: write_register(rd, bus_.read16(address & ~1U)); break;
        default: write_register(rd, sign_extend(bus_.read16(address & ~1U), 16)); break;
        }
        return;
    }

    switch (bits(opcode, 10, 2)) {
    case 0x0: bus_.write32(address & ~3U, read_register(rd)); break;
    case 0x1: bus_.write8(address, static_cast<std::uint8_t>(read_register(rd))); break;
    case 0x2:
        write_register(rd, rotate_right(bus_.read32(address & ~3U), (address & 3U) * 8U));
        break;
    default: write_register(rd, bus_.read8(address)); break;
    }
}

void Arm9::thumb_transfer_immediate_offset(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto base = read_register(bits(opcode, 3, 3));
    const bool byte_access = bit(opcode, 12);
    // Le décalage compte en unités d'accès, pas en octets : c'est ce qui permet
    // d'adresser plus loin avec cinq bits.
    const auto offset = byte_access ? bits(opcode, 6, 5) : (bits(opcode, 6, 5) << 2U);
    const auto address = base + offset;

    if (bit(opcode, 11)) {
        if (byte_access) write_register(rd, bus_.read8(address));
        else write_register(rd, rotate_right(bus_.read32(address & ~3U), (address & 3U) * 8U));
    } else {
        if (byte_access) bus_.write8(address, static_cast<std::uint8_t>(read_register(rd)));
        else bus_.write32(address & ~3U, read_register(rd));
    }
}

void Arm9::thumb_transfer_halfword(std::uint32_t opcode) {
    const auto rd = bits(opcode, 0, 3);
    const auto address = read_register(bits(opcode, 3, 3)) + (bits(opcode, 6, 5) << 1U);
    if (bit(opcode, 11)) write_register(rd, bus_.read16(address & ~1U));
    else bus_.write16(address & ~1U, static_cast<std::uint16_t>(read_register(rd)));
}

void Arm9::thumb_transfer_stack(std::uint32_t opcode) {
    const auto rd = bits(opcode, 8, 3);
    const auto address = read_register(13) + (bits(opcode, 0, 8) << 2U);
    if (bit(opcode, 11)) {
        write_register(rd, rotate_right(bus_.read32(address & ~3U), (address & 3U) * 8U));
    } else {
        bus_.write32(address & ~3U, read_register(rd));
    }
}

void Arm9::thumb_load_address(std::uint32_t opcode) {
    const auto rd = bits(opcode, 8, 3);
    const auto offset = bits(opcode, 0, 8) << 2U;
    // Calcul d'adresse, pas arithmétique : aucun indicateur n'est écrit.
    const auto base = bit(opcode, 11) ? read_register(13) : (state_.registers[15] & ~2U);
    write_register(rd, base + offset);
}

void Arm9::thumb_adjust_stack(std::uint32_t opcode) {
    const auto offset = bits(opcode, 0, 7) << 2U;
    const auto stack = read_register(13);
    write_register(13, bit(opcode, 7) ? stack - offset : stack + offset);
}

void Arm9::thumb_push_pop(std::uint32_t opcode) {
    const bool load = bit(opcode, 11);
    const bool extra = bit(opcode, 8);
    auto list = bits(opcode, 0, 8);
    if (extra) list |= load ? (1U << 15U) : (1U << 14U);

    if (list == 0U) {
        // Une liste vide n'a pas de comportement défini, et la pile s'en
        // trouverait déplacée sans que rien ne soit transféré.
        raise_undefined(opcode);
        return;
    }

    const auto count = static_cast<std::uint32_t>(std::popcount(list));
    const auto stack = read_register(13);

    if (load) {
        auto address = stack;
        for (std::uint32_t index = 0; index < 16U; ++index) {
            if (!bit(list, index)) continue;
            const auto value = bus_.read32(address & ~3U);
            if (index == 15U) {
                // Le retour d'un appel peut ramener en ARM : le bit bas de
                // l'adresse dépilée décide de l'état.
                state_.set_flag(psr::thumb, bit(value, 0));
                write_register(15, value & ~1U);
            } else {
                state_.registers[index] = value;
            }
            address += 4U;
        }
        write_register(13, stack + count * 4U);
        return;
    }

    // La pile descend : la base est calculée d'abord, puis les registres sont
    // rangés du plus bas au plus haut vers les adresses croissantes.
    const auto base = stack - count * 4U;
    auto address = base;
    for (std::uint32_t index = 0; index < 16U; ++index) {
        if (!bit(list, index)) continue;
        bus_.write32(address & ~3U, read_register(index));
        address += 4U;
    }
    write_register(13, base);
}

void Arm9::thumb_block_transfer(std::uint32_t opcode) {
    const auto rb = bits(opcode, 8, 3);
    const auto list = bits(opcode, 0, 8);
    const bool load = bit(opcode, 11);

    if (list == 0U) {
        raise_undefined(opcode);
        return;
    }

    const auto count = static_cast<std::uint32_t>(std::popcount(list));
    const auto base = read_register(rb);
    auto address = base;

    for (std::uint32_t index = 0; index < 8U; ++index) {
        if (!bit(list, index)) continue;
        if (load) state_.registers[index] = bus_.read32(address & ~3U);
        else bus_.write32(address & ~3U, read_register(index));
        address += 4U;
    }

    // Une base rechargée par la liste garde la valeur lue : la réécriture
    // l'écraserait avec une adresse dont plus personne n'a besoin.
    if (!(load && bit(list, rb))) write_register(rb, base + count * 4U);
}

void Arm9::thumb_conditional_branch(std::uint32_t opcode) {
    // Le champ de condition est celui d'ARM, décalé : la table est la même, et
    // `condition_met` la lit au rang 28.
    if (!condition_met(bits(opcode, 8, 4) << 28U)) return;
    const auto offset = sign_extend(bits(opcode, 0, 8), 8) << 1U;
    write_register(15, state_.registers[15] + offset);
}

void Arm9::thumb_branch(std::uint32_t opcode) {
    const auto offset = sign_extend(bits(opcode, 0, 11), 11) << 1U;
    write_register(15, state_.registers[15] + offset);
}

void Arm9::thumb_long_branch(std::uint32_t opcode) {
    const auto offset = bits(opcode, 0, 11);

    if (!bit(opcode, 11)) {
        // Premier demi-mot : il ne branche pas, il dépose la moitié haute de la
        // cible dans le registre de lien. Deux instructions sont nécessaires
        // parce qu'un branchement de plus ou moins quatre mégaoctets ne tient
        // pas dans seize bits.
        write_register(14, state_.registers[15] + (sign_extend(offset, 11) << 12U));
        return;
    }

    const auto target = read_register(14) + (offset << 1U);
    const auto ret = (state_.registers[15] - 2U) | 1U;
    write_register(14, ret);

    if (bits(opcode, 11, 5) == 0x1dU) {
        // Le suffixe d'échange bascule en ARM, où la cible doit être alignée
        // sur un mot.
        state_.set_flag(psr::thumb, false);
        write_register(15, target & ~3U);
        return;
    }
    write_register(15, target & ~1U);
}

} // namespace ravenemu::nds
