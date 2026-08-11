#pragma once

#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace ravenemu {

/** Identifiants natifs figés, alignés sur CheatFormat.storageId côté Kotlin. */
enum class CheatFormat : std::uint8_t {
    gameshark_gb_gbc = 0,
    gameshark_gba_v1_v2 = 1,
};

struct CheatCode {
    CheatFormat format{};
    std::string normalized;
};

/** Capability native séparée de Core, découverte explicitement par JNI. */
class CheatCapableCore {
public:
    virtual ~CheatCapableCore() = default;

    /** Formats utilisables pour la ROM actuellement chargée. */
    [[nodiscard]] virtual std::vector<CheatFormat> supported_cheat_formats() const = 0;

    /** Validation native puis remplacement atomique de la liste active. */
    virtual void replace_active_cheats(std::span<const CheatCode> codes) = 0;
};

} // namespace ravenemu
