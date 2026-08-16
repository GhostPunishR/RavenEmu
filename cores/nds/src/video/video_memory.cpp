#include "video/video_memory.hpp"

#include <algorithm>

namespace ravenemu::nds {

namespace {

/** Étendue d'une banque et sa place dans la fenêtre de transfert. */
struct BankLayout {
    std::uint32_t size;
    std::uint32_t transfer_offset;
};

constexpr std::array<BankLayout, VideoMemory::bank_count> bank_layouts{{
    {128U * 1024U, 0x0'0000U},   // A
    {128U * 1024U, 0x2'0000U},   // B
    {128U * 1024U, 0x4'0000U},   // C
    {128U * 1024U, 0x6'0000U},   // D
    { 64U * 1024U, 0x8'0000U},   // E
    { 16U * 1024U, 0x9'0000U},   // F
    { 16U * 1024U, 0x9'4000U},   // G
    { 32U * 1024U, 0x9'8000U},   // H
    { 16U * 1024U, 0xa'0000U},   // I
}};

/** Bit qui allume une banque ; éteinte, elle ne répond nulle part. */
constexpr std::uint8_t bank_enabled = 0x80;

constexpr std::uint32_t total_bytes = []() {
    std::uint32_t total = 0;
    for (const auto& layout : bank_layouts) total += layout.size;
    return total;
}();

/** Décalage d'une banque dans le bloc unique qui les porte toutes. */
constexpr std::array<std::uint32_t, VideoMemory::bank_count> bank_offsets = []() {
    std::array<std::uint32_t, VideoMemory::bank_count> offsets{};
    std::uint32_t cursor = 0;
    for (std::size_t index = 0; index < bank_layouts.size(); ++index) {
        offsets[index] = cursor;
        cursor += bank_layouts[index].size;
    }
    return offsets;
}();

/**
 * Place des petites banques dans la fenêtre du moteur principal.
 *
 * Les deux bits d'écart ne se suivent pas : le premier déplace de seize
 * kilooctets, le second de cent vingt-huit. C'est la seule formule de ce genre,
 * et elle vaut aussi bien pour les décors que pour les sprites.
 */
[[nodiscard]] constexpr std::uint32_t small_bank_offset(std::uint32_t selector) noexcept {
    return 0x4000U * (selector & 1U) + 0x1'0000U * ((selector >> 1U) & 1U);
}

} // namespace

VideoMemory::VideoMemory() : bytes_(total_bytes, 0) {}

void VideoMemory::reset() noexcept {
    std::fill(bytes_.begin(), bytes_.end(), std::uint8_t{0});
    std::fill(control_.begin(), control_.end(), std::uint8_t{0});
    overlaps_ = 0;
    unbacked_ = 0;
}

std::uint8_t VideoMemory::control(std::size_t bank) const noexcept {
    if (bank >= bank_count) return 0;
    return control_[bank];
}

void VideoMemory::set_control(std::size_t bank, std::uint8_t value) noexcept {
    if (bank >= bank_count) return;
    control_[bank] = value;
}

std::span<std::uint8_t> VideoMemory::bank(std::size_t index) noexcept {
    if (index >= bank_count) return {};
    return std::span<std::uint8_t>{bytes_}.subspan(bank_offsets[index], bank_layouts[index].size);
}

/**
 * Décodage du branchement d'une banque.
 *
 * Chaque banque a sa propre table : ni le nombre de destinations, ni la façon
 * dont le champ d'écart les place, ne se déduisent d'une règle commune. Les
 * écrire une par une est plus long qu'une formule, et c'est le seul moyen d'être
 * fidèle — une banque branchée à la mauvaise place donne un décor faux, sans que
 * rien ne le signale.
 */
VideoMemory::Assignment VideoMemory::assignment(std::size_t index) const noexcept {
    if (index >= bank_count) return {};

    const auto value = control_[index];
    const bool enabled = (value & bank_enabled) != 0U;
    const auto mode = static_cast<std::uint32_t>(value & 0x7U);
    const auto selector = static_cast<std::uint32_t>((value >> 3U) & 0x3U);

    const auto place = [enabled](VramTarget target, std::uint32_t offset) {
        return VideoMemory::Assignment{target, offset, enabled};
    };

    switch (index) {
    case 0:   // A
    case 1: { // B
        switch (mode & 0x3U) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_main, 0x2'0000U * selector);
        case 2: return place(VramTarget::object_main, 0x2'0000U * (selector & 1U));
        default: return place(VramTarget::texture, 0x2'0000U * selector);
        }
    }
    case 2: { // C
        switch (mode) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_main, 0x2'0000U * selector);
        case 2: return place(VramTarget::secondary_processor, 0x2'0000U * (selector & 1U));
        case 3: return place(VramTarget::texture, 0x2'0000U * selector);
        case 4: return place(VramTarget::background_secondary, 0);
        default: return place(VramTarget::reserved, 0);
        }
    }
    case 3: { // D
        switch (mode) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_main, 0x2'0000U * selector);
        case 2: return place(VramTarget::secondary_processor, 0x2'0000U * (selector & 1U));
        case 3: return place(VramTarget::texture, 0x2'0000U * selector);
        case 4: return place(VramTarget::object_secondary, 0);
        default: return place(VramTarget::reserved, 0);
        }
    }
    case 4: { // E
        switch (mode) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_main, 0);
        case 2: return place(VramTarget::object_main, 0);
        case 3: return place(VramTarget::texture_palette, 0);
        case 4: return place(VramTarget::background_palette_main, 0);
        default: return place(VramTarget::reserved, 0);
        }
    }
    case 5:   // F
    case 6: { // G
        switch (mode) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_main, small_bank_offset(selector));
        case 2: return place(VramTarget::object_main, small_bank_offset(selector));
        case 3: return place(VramTarget::texture_palette, small_bank_offset(selector));
        case 4: return place(VramTarget::background_palette_main, 0);
        case 5: return place(VramTarget::object_palette_main, 0);
        default: return place(VramTarget::reserved, 0);
        }
    }
    case 7: { // H
        switch (mode & 0x3U) {
        case 0: return place(VramTarget::transfer, 0);
        case 1: return place(VramTarget::background_secondary, 0);
        case 2: return place(VramTarget::background_palette_secondary, 0);
        default: return place(VramTarget::reserved, 0);
        }
    }
    default: { // I
        switch (mode & 0x3U) {
        case 0: return place(VramTarget::transfer, 0);
        // La seule banque qui ne commence pas au début de sa fenêtre : elle
        // complète celle qui la précède plutôt que de la recouvrir.
        case 1: return place(VramTarget::background_secondary, 0x8000U);
        case 2: return place(VramTarget::object_secondary, 0);
        default: return place(VramTarget::object_palette_secondary, 0);
        }
    }
    }
}

std::uint8_t* VideoMemory::transfer(std::uint32_t address) noexcept {
    for (std::size_t index = 0; index < bank_count; ++index) {
        const auto& layout = bank_layouts[index];
        const auto base = transfer_base + layout.transfer_offset;
        if (address < base || address >= base + layout.size) continue;

        const auto placement = assignment(index);
        // Éteinte, ou branchée sur un moteur : dans les deux cas elle ne se voit
        // plus par cette fenêtre, et le matériel ne les distingue pas non plus.
        if (!placement.enabled || placement.target != VramTarget::transfer) return nullptr;
        return &bytes_[bank_offsets[index] + (address - base)];
    }
    return nullptr;
}

std::uint8_t VideoMemory::read_window(VramTarget target, std::uint32_t offset) const noexcept {
    const std::uint8_t* found = nullptr;
    for (std::size_t index = 0; index < bank_count; ++index) {
        const auto placement = assignment(index);
        if (!placement.enabled || placement.target != target) continue;
        // Sous la place de la banque, la soustraction repasse par le haut : elle
        // rend alors au moins quatre milliards moins la place, soit bien plus
        // que toute taille de banque, et la comparaison suivante écarte le cas.
        // Une garde explicite serait plus lisible mais ne serait jamais exercée,
        // et une ligne qu'aucun test ne peut atteindre est une ligne dont
        // personne ne sait si elle est juste.
        const auto inner = offset - placement.offset;
        if (inner >= bank_layouts[index].size) continue;

        if (found != nullptr) {
            // Deux banques pour une même place : le matériel ne tranche pas, et
            // le signaler vaut mieux que d'inventer une règle.
            ++overlaps_;
            continue;
        }
        found = &bytes_[bank_offsets[index] + inner];
    }

    if (found == nullptr) {
        ++unbacked_;
        return 0;
    }
    return *found;
}

std::uint8_t VideoMemory::read_background(Engine engine, std::uint32_t offset) const noexcept {
    return read_window(
        engine == Engine::main ? VramTarget::background_main : VramTarget::background_secondary,
        offset
    );
}

std::uint16_t VideoMemory::read_background16(Engine engine, std::uint32_t offset) const noexcept {
    const auto low = read_background(engine, offset);
    const auto high = read_background(engine, offset + 1U);
    return static_cast<std::uint16_t>(static_cast<std::uint32_t>(low) |
        (static_cast<std::uint32_t>(high) << 8U));
}

std::uint8_t VideoMemory::read_object(Engine engine, std::uint32_t offset) const noexcept {
    return read_window(
        engine == Engine::main ? VramTarget::object_main : VramTarget::object_secondary,
        offset
    );
}

} // namespace ravenemu::nds
