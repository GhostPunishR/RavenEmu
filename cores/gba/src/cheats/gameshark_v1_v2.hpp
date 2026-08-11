#pragma once

#include "memory/bus.hpp"
#include "ravenemu/cheats.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::gba {

struct GameSharkV1V2Words {
    std::uint32_t left{};
    std::uint32_t right{};

    friend bool operator==(const GameSharkV1V2Words&, const GameSharkV1V2Words&) = default;
};

/** Déchiffrement original RavenEmu du bloc GameShark Advance v1/v2. */
[[nodiscard]] GameSharkV1V2Words decrypt_gameshark_v1_v2(
    GameSharkV1V2Words encrypted
) noexcept;

/** Programme compilé une fois lors du remplacement atomique des cheats. */
class GameSharkV1V2Program {
public:
    /** Représentation compilée interne, exposée seulement dans le cœur GBA. */
    enum class Kind : std::uint8_t {
        no_op,
        write_8,
        write_16,
        write_32,
        if_equal_16,
    };

    struct Instruction {
        Kind kind{Kind::no_op};
        std::uint32_t address{};
        std::uint32_t value{};
        std::size_t skip_if_false{};
    };

    [[nodiscard]] static GameSharkV1V2Program compile(
        const CheatCode& code,
        std::span<const std::uint8_t> rom
    );

    /** Applique uniquement les commandes périodiques, sur le thread du cœur. */
    void apply(Bus& bus) const noexcept;

    /** Patches de lecture ROM installés directement sur le bus émulé. */
    [[nodiscard]] std::span<const CheatRomPatch> rom_patches() const noexcept {
        return rom_patches_;
    }

private:
    std::vector<Instruction> instructions_;
    std::vector<CheatRomPatch> rom_patches_;
};

} // namespace ravenemu::gba
