#include "cpu/arm9.hpp"

#include <bit>

namespace ravenemu::nds {

namespace {

constexpr std::uint32_t bits(std::uint32_t value, unsigned low, unsigned count) noexcept {
    return (value >> low) & ((1U << count) - 1U);
}

constexpr bool bit(std::uint32_t value, unsigned index) noexcept {
    return ((value >> index) & 1U) != 0U;
}

constexpr std::uint32_t rotate_right(std::uint32_t value, std::uint32_t amount) noexcept {
    amount &= 31U;
    if (amount == 0U) return value;
    return (value >> amount) | (value << (32U - amount));
}

/** Étend un champ signé de [width] bits sur 32. */
constexpr std::uint32_t sign_extend(std::uint32_t value, unsigned width) noexcept {
    const auto shift = 32U - width;
    return static_cast<std::uint32_t>(static_cast<std::int32_t>(value << shift) >> shift);
}

constexpr std::uint32_t saturate(std::int64_t value, bool& saturated) noexcept {
    constexpr std::int64_t high = 0x7fff'ffff;
    constexpr std::int64_t low = -0x8000'0000LL;
    if (value > high) { saturated = true; return static_cast<std::uint32_t>(high); }
    if (value < low) { saturated = true; return static_cast<std::uint32_t>(static_cast<std::int32_t>(low)); }
    return static_cast<std::uint32_t>(static_cast<std::int32_t>(value));
}

} // namespace

void Arm9::reset() noexcept {
    state_ = CpuState{};
    branched_ = false;
    irq_line_ = false;
    fiq_line_ = false;
    unimplemented_ = 0;
    first_unimplemented_ = 0;
    state_.registers[15] = reset_vector;
}

bool Arm9::condition_met(std::uint32_t opcode) const noexcept {
    const auto n = state_.flag(psr::negative);
    const auto z = state_.flag(psr::zero);
    const auto c = state_.flag(psr::carry);
    const auto v = state_.flag(psr::overflow);
    switch (bits(opcode, 28, 4)) {
    case 0x0: return z;
    case 0x1: return !z;
    case 0x2: return c;
    case 0x3: return !c;
    case 0x4: return n;
    case 0x5: return !n;
    case 0x6: return v;
    case 0x7: return !v;
    case 0x8: return c && !z;
    case 0x9: return !c || z;
    case 0xa: return n == v;
    case 0xb: return n != v;
    case 0xc: return !z && n == v;
    case 0xd: return z || n != v;
    case 0xe: return true;
    // 0xF n'est pas une condition : c'est l'espace d'extension d'ARMv5, traité
    // avant l'évaluation conditionnelle.
    default: return true;
    }
}

Arm9::ShifterResult Arm9::apply_shift(
    std::uint32_t value,
    std::uint32_t type,
    std::uint32_t amount,
    bool immediate_form,
    bool carry_in
) noexcept {
    if (amount == 0U) {
        if (!immediate_form) return {value, carry_in};
        // Un décalage immédiat nul ne veut pas dire « ne rien faire » : chaque
        // type y code un cas particulier.
        switch (type) {
        case 0: return {value, carry_in};                                  // LSL #0
        case 1: return {0U, bit(value, 31)};                               // LSR #32
        case 2: return {value & 0x8000'0000U ? 0xffff'ffffU : 0U, bit(value, 31)}; // ASR #32
        default: {                                                          // RRX
            const auto result = (value >> 1U) | (carry_in ? 0x8000'0000U : 0U);
            return {result, bit(value, 0)};
        }
        }
    }

    switch (type) {
    case 0:
        if (amount < 32U) return {value << amount, bit(value, 32U - amount)};
        if (amount == 32U) return {0U, bit(value, 0)};
        return {0U, false};
    case 1:
        if (amount < 32U) return {value >> amount, bit(value, amount - 1U)};
        if (amount == 32U) return {0U, bit(value, 31)};
        return {0U, false};
    case 2: {
        if (amount >= 32U) {
            const bool negative = bit(value, 31);
            return {negative ? 0xffff'ffffU : 0U, negative};
        }
        const auto result = static_cast<std::uint32_t>(
            static_cast<std::int32_t>(value) >> static_cast<int>(amount)
        );
        return {result, bit(value, amount - 1U)};
    }
    default: {
        const auto rotation = amount & 31U;
        if (rotation == 0U) return {value, bit(value, 31)};
        const auto result = rotate_right(value, rotation);
        return {result, bit(result, 31)};
    }
    }
}

Arm9::ShifterResult Arm9::shift_operand(std::uint32_t opcode, bool immediate) {
    const auto carry_in = state_.flag(psr::carry);
    if (immediate) {
        const auto rotation = bits(opcode, 8, 4) * 2U;
        const auto value = rotate_right(bits(opcode, 0, 8), rotation);
        return {value, rotation == 0U ? carry_in : bit(value, 31)};
    }

    const auto rm = bits(opcode, 0, 4);
    const auto type = bits(opcode, 5, 2);
    const bool by_register = bit(opcode, 4);

    // Un décalage par registre consomme un cycle de plus, pendant lequel le
    // compteur de programme a encore avancé : R15 y vaut donc douze de plus que
    // l'instruction, et non huit.
    auto value = read_register(rm);
    if (rm == 15U && by_register) value += 4U;

    if (!by_register) {
        return apply_shift(value, type, bits(opcode, 7, 5), true, carry_in);
    }
    auto amount = read_register(bits(opcode, 8, 4)) & 0xffU;
    return apply_shift(value, type, amount, false, carry_in);
}

void Arm9::write_register(std::uint32_t index, std::uint32_t value) noexcept {
    state_.registers[index] = value;
    if (index == 15U) branched_ = true;
}

void Arm9::set_logical_flags(std::uint32_t result, bool carry) noexcept {
    state_.set_flag(psr::negative, bit(result, 31));
    state_.set_flag(psr::zero, result == 0U);
    state_.set_flag(psr::carry, carry);
}

void Arm9::set_arithmetic_flags(
    std::uint32_t result,
    std::uint32_t left,
    std::uint32_t right,
    bool carry,
    bool subtract
) noexcept {
    state_.set_flag(psr::negative, bit(result, 31));
    state_.set_flag(psr::zero, result == 0U);
    state_.set_flag(psr::carry, carry);
    const auto overflow = subtract
        ? ((left ^ right) & (left ^ result)) : ((left ^ result) & (right ^ result));
    state_.set_flag(psr::overflow, bit(overflow, 31));
}

void Arm9::enter_exception(
    CpuMode mode,
    std::uint32_t vector,
    std::uint32_t return_address,
    bool mask_fiq
) noexcept {
    const auto saved = state_.cpsr;
    state_.switch_mode(mode);
    state_.spsr_for(mode) = saved;
    state_.registers[14] = return_address;
    state_.cpsr &= ~psr::thumb;
    state_.cpsr |= psr::irq_disable;
    if (mask_fiq) state_.cpsr |= psr::fiq_disable;
    state_.registers[15] = vector;
    branched_ = true;
}

void Arm9::restore_cpsr_from_spsr() noexcept {
    const auto current = state_.mode();
    if (!CpuState::has_spsr(current)) return;
    const auto restored = state_.spsr_for(current);
    state_.switch_mode(static_cast<CpuMode>(restored & psr::mode_mask));
    state_.cpsr = restored;
}

void Arm9::raise_undefined(std::uint32_t opcode) {
    if (unimplemented_ == 0U) first_unimplemented_ = opcode;
    ++unimplemented_;
    enter_exception(CpuMode::undefined, undefined_vector, state_.registers[15] - 4U, false);
}

void Arm9::step() {
    // Les lignes d'interruption sont échantillonnées entre deux instructions :
    // le matériel ne coupe pas une instruction en cours.
    if (fiq_line_ && !state_.flag(psr::fiq_disable)) {
        enter_exception(CpuMode::fiq, fiq_vector, state_.registers[15] + 4U, true);
        return;
    }
    if (irq_line_ && !state_.flag(psr::irq_disable)) {
        enter_exception(CpuMode::irq, irq_vector, state_.registers[15] + 4U, false);
        return;
    }

    if (state_.thumb()) {
        // Le jeu Thumb n'est pas encore écrit. Le signaler par l'exception
        // prévue à cet effet vaut mieux que d'exécuter des octets au hasard.
        raise_undefined(0U);
        return;
    }

    const auto pc = state_.registers[15];
    const auto opcode = bus_.read32(pc & ~3U);
    branched_ = false;
    state_.registers[15] = pc + 8U;
    execute(opcode);
    if (!branched_) state_.registers[15] = pc + 4U;
}

void Arm9::execute(std::uint32_t opcode) {
    // L'espace de condition 0xF n'est pas conditionnel : ARMv5 y range BLX
    // immédiat et des préchargements sans effet observable ici.
    if (bits(opcode, 28, 4) == 0xfU) {
        if (bits(opcode, 25, 3) == 0x5U) {                     // BLX immédiat
            const auto offset = sign_extend(bits(opcode, 0, 24), 24) << 2U;
            const auto half = bit(opcode, 24) ? 2U : 0U;
            write_register(14, state_.registers[15] - 4U);
            state_.cpsr |= psr::thumb;
            write_register(15, state_.registers[15] + offset + half);
            return;
        }
        raise_undefined(opcode);
        return;
    }

    if (!condition_met(opcode)) return;

    const auto family = bits(opcode, 25, 3);
    switch (family) {
    case 0x0: {
        if (bits(opcode, 4, 4) == 0x9U) {
            if (bits(opcode, 23, 5) == 0x02U) { execute_swap(opcode); return; }
            if (bits(opcode, 23, 5) == 0x01U) { execute_multiply_long(opcode); return; }
            if (bits(opcode, 22, 6) == 0x00U) { execute_multiply(opcode); return; }
            raise_undefined(opcode);
            return;
        }
        if (bit(opcode, 7) && bit(opcode, 4)) { execute_halfword_transfer(opcode); return; }
        // Les formes « comparaison sans écriture d'indicateurs » n'en sont pas :
        // ARM y a rangé les transferts d'état, les branchements par registre,
        // le comptage de zéros et l'arithmétique saturante.
        if (bits(opcode, 23, 2) == 0x2U && !bit(opcode, 20)) {
            if (bits(opcode, 4, 4) == 0x1U && bits(opcode, 8, 12) == 0xfffU) {
                execute_branch_exchange(opcode, false);
                return;
            }
            if (bits(opcode, 4, 4) == 0x3U && bits(opcode, 8, 12) == 0xfffU) {
                execute_branch_exchange(opcode, true);
                return;
            }
            if (bits(opcode, 4, 4) == 0x1U && bits(opcode, 16, 4) == 0xfU && bit(opcode, 22)) {
                execute_clz(opcode);
                return;
            }
            if (bits(opcode, 4, 4) == 0x5U) { execute_saturating(opcode); return; }
            if (bits(opcode, 4, 1) == 0U && bit(opcode, 7)) {
                // Multiplications signées de la variante DSP : décodées, non
                // implémentées, et signalées comme telles.
                raise_undefined(opcode);
                return;
            }
            execute_psr_transfer(opcode);
            return;
        }
        execute_data_processing(opcode);
        return;
    }
    case 0x1:
        if (bits(opcode, 23, 2) == 0x2U && !bit(opcode, 20)) { execute_psr_transfer(opcode); return; }
        execute_data_processing(opcode);
        return;
    case 0x2:
    case 0x3:
        if (family == 0x3U && bit(opcode, 4)) { raise_undefined(opcode); return; }
        execute_single_transfer(opcode);
        return;
    case 0x4:
        execute_block_transfer(opcode);
        return;
    case 0x5:
        execute_branch(opcode);
        return;
    case 0x7:
        if (bit(opcode, 24)) {                                   // SWI
            enter_exception(
                CpuMode::supervisor,
                software_interrupt_vector,
                state_.registers[15] - 4U,
                false
            );
            return;
        }
        [[fallthrough]];
    default:
        // Coprocesseurs : le CP15 du 946E-S gouverne caches, mémoires locales
        // et régions de protection. Il fera l'objet de son propre lot.
        raise_undefined(opcode);
        return;
    }
}

void Arm9::execute_data_processing(std::uint32_t opcode) {
    const auto operation = bits(opcode, 21, 4);
    const bool set_flags = bit(opcode, 20);
    const auto rn = bits(opcode, 16, 4);
    const auto rd = bits(opcode, 12, 4);
    const bool immediate = bit(opcode, 25);

    const auto shifted = shift_operand(opcode, immediate);
    auto left = read_register(rn);
    // Même règle que pour le décaleur : un opérande décalé par registre voit un
    // compteur de programme avancé de douze.
    if (rn == 15U && !immediate && bit(opcode, 4)) left += 4U;

    const auto right = shifted.value;
    const auto carry_in = state_.flag(psr::carry) ? 1U : 0U;
    std::uint32_t result = 0U;
    bool writes = true;
    bool arithmetic = false;
    bool subtract = false;
    bool carry_out = shifted.carry;
    std::uint32_t flag_left = left;
    std::uint32_t flag_right = right;

    switch (operation) {
    case 0x0: result = left & right; break;
    case 0x1: result = left ^ right; break;
    case 0x2: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) - right;
        result = static_cast<std::uint32_t>(wide);
        carry_out = left >= right;
        arithmetic = true; subtract = true;
        break;
    }
    case 0x3: {
        result = right - left;
        carry_out = right >= left;
        arithmetic = true; subtract = true;
        flag_left = right; flag_right = left;
        break;
    }
    case 0x4: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + right;
        result = static_cast<std::uint32_t>(wide);
        carry_out = wide > 0xffff'ffffULL;
        arithmetic = true;
        break;
    }
    case 0x5: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + right + carry_in;
        result = static_cast<std::uint32_t>(wide);
        carry_out = wide > 0xffff'ffffULL;
        arithmetic = true;
        break;
    }
    case 0x6: {
        const std::uint64_t wide =
            static_cast<std::uint64_t>(left) - right - (carry_in ^ 1U);
        result = static_cast<std::uint32_t>(wide);
        carry_out = static_cast<std::uint64_t>(left) >=
            static_cast<std::uint64_t>(right) + (carry_in ^ 1U);
        arithmetic = true; subtract = true;
        break;
    }
    case 0x7: {
        const std::uint64_t wide =
            static_cast<std::uint64_t>(right) - left - (carry_in ^ 1U);
        result = static_cast<std::uint32_t>(wide);
        carry_out = static_cast<std::uint64_t>(right) >=
            static_cast<std::uint64_t>(left) + (carry_in ^ 1U);
        arithmetic = true; subtract = true;
        flag_left = right; flag_right = left;
        break;
    }
    case 0x8: result = left & right; writes = false; break;
    case 0x9: result = left ^ right; writes = false; break;
    case 0xa: {
        result = left - right;
        carry_out = left >= right;
        writes = false; arithmetic = true; subtract = true;
        break;
    }
    case 0xb: {
        const std::uint64_t wide = static_cast<std::uint64_t>(left) + right;
        result = static_cast<std::uint32_t>(wide);
        carry_out = wide > 0xffff'ffffULL;
        writes = false; arithmetic = true;
        break;
    }
    case 0xc: result = left | right; break;
    case 0xd: result = right; break;
    case 0xe: result = left & ~right; break;
    default: result = ~right; break;
    }

    if (writes) write_register(rd, result);

    if (!set_flags) return;
    if (rd == 15U && writes) {
        // Écrire le compteur de programme avec le bit S demande le retour d'une
        // exception : c'est le CPSR sauvegardé qui reprend la main, pas de
        // simples indicateurs.
        restore_cpsr_from_spsr();
        return;
    }
    if (arithmetic) set_arithmetic_flags(result, flag_left, flag_right, carry_out, subtract);
    else set_logical_flags(result, carry_out);
}

void Arm9::execute_multiply(std::uint32_t opcode) {
    const auto rd = bits(opcode, 16, 4);
    const auto rn = bits(opcode, 12, 4);
    const auto rs = bits(opcode, 8, 4);
    const auto rm = bits(opcode, 0, 4);
    auto result = read_register(rm) * read_register(rs);
    if (bit(opcode, 21)) result += read_register(rn);
    write_register(rd, result);
    if (bit(opcode, 20)) {
        state_.set_flag(psr::negative, bit(result, 31));
        state_.set_flag(psr::zero, result == 0U);
    }
}

void Arm9::execute_multiply_long(std::uint32_t opcode) {
    const auto rd_high = bits(opcode, 16, 4);
    const auto rd_low = bits(opcode, 12, 4);
    const auto rs = read_register(bits(opcode, 8, 4));
    const auto rm = read_register(bits(opcode, 0, 4));
    const bool is_signed = bit(opcode, 22);

    std::uint64_t product = is_signed
        ? static_cast<std::uint64_t>(
              static_cast<std::int64_t>(static_cast<std::int32_t>(rm)) *
              static_cast<std::int64_t>(static_cast<std::int32_t>(rs)))
        : static_cast<std::uint64_t>(rm) * rs;

    if (bit(opcode, 21)) {
        const auto accumulator = (static_cast<std::uint64_t>(read_register(rd_high)) << 32U) |
            read_register(rd_low);
        product += accumulator;
    }

    write_register(rd_low, static_cast<std::uint32_t>(product));
    write_register(rd_high, static_cast<std::uint32_t>(product >> 32U));
    if (bit(opcode, 20)) {
        state_.set_flag(psr::negative, (product >> 63U) != 0U);
        state_.set_flag(psr::zero, product == 0U);
    }
}

void Arm9::execute_swap(std::uint32_t opcode) {
    const auto address = read_register(bits(opcode, 16, 4));
    const auto rd = bits(opcode, 12, 4);
    const auto source = read_register(bits(opcode, 0, 4));
    if (bit(opcode, 22)) {
        const auto previous = bus_.read8(address);
        bus_.write8(address, static_cast<std::uint8_t>(source));
        write_register(rd, previous);
    } else {
        const auto previous = rotate_right(bus_.read32(address & ~3U), (address & 3U) * 8U);
        bus_.write32(address & ~3U, source);
        write_register(rd, previous);
    }
}

void Arm9::execute_halfword_transfer(std::uint32_t opcode) {
    const auto rn = bits(opcode, 16, 4);
    const auto rd = bits(opcode, 12, 4);
    const bool pre = bit(opcode, 24);
    const bool up = bit(opcode, 23);
    const bool writeback = bit(opcode, 21);
    const bool load = bit(opcode, 20);
    const auto kind = bits(opcode, 5, 2);

    const auto offset = bit(opcode, 22)
        ? ((bits(opcode, 8, 4) << 4U) | bits(opcode, 0, 4))
        : read_register(bits(opcode, 0, 4));

    const auto base = read_register(rn);
    const auto moved = up ? base + offset : base - offset;
    const auto address = pre ? moved : base;

    if (!load && (kind == 0x2U || kind == 0x3U)) {
        // Doubles mots : la paire de registres est imposée par le matériel, qui
        // n'accepte qu'un registre pair et jamais le compteur de programme. Un
        // encodage hors de ces clous n'a pas de comportement défini ; le laisser
        // passer ferait lire `registers[16]`, donc on le refuse au décodage.
        if ((rd & 1U) != 0U || rd == 14U) {
            raise_undefined(opcode);
            return;
        }
        if (kind == 0x2U) {                                   // LDRD
            write_register(rd, bus_.read32(address & ~3U));
            write_register(rd + 1U, bus_.read32((address + 4U) & ~3U));
        } else {                                              // STRD
            bus_.write32(address & ~3U, read_register(rd));
            bus_.write32((address + 4U) & ~3U, read_register(rd + 1U));
        }
    } else if (load) {
        switch (kind) {
        // ARMv5 laisse un accès demi-mot désaligné indéfini, et le contrôleur
        // d'alignement du 946E-S y lève une faute plutôt que de servir une
        // valeur. Le bit bas est donc ignoré, uniformément en lecture comme en
        // écriture : une règle unique vaut mieux qu'une rotation inventée qui
        // différerait entre `LDRH` et `LDRSH`.
        case 0x1: write_register(rd, bus_.read16(address & ~1U)); break;
        case 0x2: write_register(rd, sign_extend(bus_.read8(address), 8)); break;
        default: write_register(rd, sign_extend(bus_.read16(address & ~1U), 16)); break;
        }
    } else {
        bus_.write16(address & ~1U, static_cast<std::uint16_t>(read_register(rd)));
    }

    if ((!pre || writeback) && rn != rd) write_register(rn, moved);
}

void Arm9::execute_single_transfer(std::uint32_t opcode) {
    const auto rn = bits(opcode, 16, 4);
    const auto rd = bits(opcode, 12, 4);
    const bool pre = bit(opcode, 24);
    const bool up = bit(opcode, 23);
    const bool byte_access = bit(opcode, 22);
    const bool writeback = bit(opcode, 21);
    const bool load = bit(opcode, 20);

    // Le bit 25 a ici le sens inverse de celui du traitement de données :
    // il indique un décalage de registre, pas une valeur immédiate.
    const auto offset = bit(opcode, 25)
        ? apply_shift(
              read_register(bits(opcode, 0, 4)),
              bits(opcode, 5, 2),
              bits(opcode, 7, 5),
              true,
              state_.flag(psr::carry)
          ).value
        : bits(opcode, 0, 12);

    const auto base = read_register(rn);
    const auto moved = up ? base + offset : base - offset;
    const auto address = pre ? moved : base;

    if (load) {
        if (byte_access) {
            write_register(rd, bus_.read8(address));
        } else {
            // Une lecture de mot non alignée ne fait pas d'erreur : la donnée
            // est ramenée alignée puis tournée, comportement dont des jeux
            // dépendent.
            const auto value = bus_.read32(address & ~3U);
            write_register(rd, rotate_right(value, (address & 3U) * 8U));
        }
    } else {
        const auto value = rd == 15U ? read_register(15) + 4U : read_register(rd);
        if (byte_access) bus_.write8(address, static_cast<std::uint8_t>(value));
        else bus_.write32(address & ~3U, value);
    }

    if ((!pre || writeback) && !(load && rn == rd)) write_register(rn, moved);
}

void Arm9::execute_block_transfer(std::uint32_t opcode) {
    const auto rn = bits(opcode, 16, 4);
    const bool pre = bit(opcode, 24);
    const bool up = bit(opcode, 23);
    const bool user_bank = bit(opcode, 22);
    const bool writeback = bit(opcode, 21);
    const bool load = bit(opcode, 20);
    const auto list = bits(opcode, 0, 16);

    const auto count = static_cast<std::uint32_t>(std::popcount(list));
    const auto base = read_register(rn);
    // Les registres vont toujours du plus bas au plus haut vers les adresses
    // croissantes : seul le point de départ change avec le mode.
    const auto start = up ? (base + (pre ? 4U : 0U)) : (base - count * 4U + (pre ? 0U : 4U));
    const auto moved = up ? base + count * 4U : base - count * 4U;

    const bool restores_cpsr = load && user_bank && bit(list, 15);
    const bool uses_user_bank = user_bank && !restores_cpsr;
    const auto previous_mode = state_.mode();
    if (uses_user_bank) state_.switch_mode(CpuMode::system);

    auto address = start;
    if (count == 0U) {
        // Une liste vide transfère quand même R15 et déplace la base de 64
        // octets sur ce cœur ; le cas est rare et documenté comme non couvert.
        raise_undefined(opcode);
        if (uses_user_bank) state_.switch_mode(previous_mode);
        return;
    }

    for (std::uint32_t index = 0; index < 16U; ++index) {
        if (!bit(list, index)) continue;
        if (load) {
            const auto value = bus_.read32(address & ~3U);
            if (index == 15U) {
                write_register(15, value & ~1U);
                if (restores_cpsr) restore_cpsr_from_spsr();
            } else {
                state_.registers[index] = value;
            }
        } else {
            const auto value = index == 15U ? read_register(15) + 4U : read_register(index);
            bus_.write32(address & ~3U, value);
        }
        address += 4U;
    }

    if (uses_user_bank) state_.switch_mode(previous_mode);
    if (writeback && !(load && bit(list, rn))) write_register(rn, moved);
}

void Arm9::execute_branch(std::uint32_t opcode) {
    const auto offset = sign_extend(bits(opcode, 0, 24), 24) << 2U;
    if (bit(opcode, 24)) write_register(14, state_.registers[15] - 4U);
    write_register(15, state_.registers[15] + offset);
}

void Arm9::execute_branch_exchange(std::uint32_t opcode, bool link) {
    const auto target = read_register(bits(opcode, 0, 4));
    if (link) write_register(14, state_.registers[15] - 4U);
    state_.set_flag(psr::thumb, bit(target, 0));
    write_register(15, target & ~1U);
}

void Arm9::execute_psr_transfer(std::uint32_t opcode) {
    const bool use_spsr = bit(opcode, 22);
    const auto current = state_.mode();

    if (!bit(opcode, 21)) {                                   // MRS
        const auto value = use_spsr && CpuState::has_spsr(current)
            ? state_.spsr_for(current) : state_.cpsr;
        write_register(bits(opcode, 12, 4), value);
        return;
    }

    const auto source = bit(opcode, 25)
        ? rotate_right(bits(opcode, 0, 8), bits(opcode, 8, 4) * 2U)
        : read_register(bits(opcode, 0, 4));

    std::uint32_t mask = 0U;
    if (bit(opcode, 16)) mask |= 0x0000'00ffU;
    if (bit(opcode, 17)) mask |= 0x0000'ff00U;
    if (bit(opcode, 18)) mask |= 0x00ff'0000U;
    if (bit(opcode, 19)) mask |= 0xff00'0000U;

    if (use_spsr) {
        // Pas de garde sur les modes sans SPSR : `spsr_for` les dirige déjà vers
        // un emplacement de rebut. Deux mécanismes pour un même refus, c'en est
        // un de trop, et le second ne serait jamais exercé.
        auto& target = state_.spsr_for(current);
        target = (target & ~mask) | (source & mask);
        return;
    }

    // En mode utilisateur, seuls les indicateurs sont modifiables : un
    // programme ne doit pas pouvoir s'octroyer un mode privilégié.
    if (current == CpuMode::user) mask &= 0xff00'0000U;
    const auto updated = (state_.cpsr & ~mask) | (source & mask);
    if ((mask & psr::mode_mask) != 0U) {
        state_.switch_mode(static_cast<CpuMode>(updated & psr::mode_mask));
    }
    state_.cpsr = (state_.cpsr & ~mask) | (source & mask);
}

void Arm9::execute_clz(std::uint32_t opcode) {
    const auto value = read_register(bits(opcode, 0, 4));
    write_register(bits(opcode, 12, 4), static_cast<std::uint32_t>(std::countl_zero(value)));
}

void Arm9::execute_saturating(std::uint32_t opcode) {
    const auto rn = static_cast<std::int64_t>(static_cast<std::int32_t>(read_register(bits(opcode, 16, 4))));
    const auto rm = static_cast<std::int64_t>(static_cast<std::int32_t>(read_register(bits(opcode, 0, 4))));
    const auto rd = bits(opcode, 12, 4);
    bool saturated = false;

    std::uint32_t result = 0U;
    switch (bits(opcode, 21, 2)) {
    case 0x0: result = saturate(rm + rn, saturated); break;
    case 0x1: result = saturate(rm - rn, saturated); break;
    case 0x2: {
        const auto doubled = static_cast<std::int64_t>(
            static_cast<std::int32_t>(saturate(rn * 2, saturated))
        );
        result = saturate(rm + doubled, saturated);
        break;
    }
    default: {
        const auto doubled = static_cast<std::int64_t>(
            static_cast<std::int32_t>(saturate(rn * 2, saturated))
        );
        result = saturate(rm - doubled, saturated);
        break;
    }
    }

    write_register(rd, result);
    // L'indicateur de saturation est collant : il se pose et ne s'efface que par
    // une écriture explicite du registre d'état.
    if (saturated) state_.set_flag(psr::saturation, true);
}

} // namespace ravenemu::nds
