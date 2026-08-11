#include "cheats/gameshark_v1_v2.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace ravenemu::gba {
namespace {

constexpr std::uint32_t seed_0 = 0x09f4'fbbdU;
constexpr std::uint32_t seed_1 = 0x9681'884aU;
constexpr std::uint32_t seed_2 = 0x3520'27e9U;
constexpr std::uint32_t seed_3 = 0xf3de'e5a7U;
constexpr std::uint32_t delta = 0x9e37'79b9U;
constexpr std::uint32_t address_mask = 0x0fff'ffffU;
constexpr std::uint32_t game_id_marker = 0x001d'c0deU;
constexpr std::uint32_t deadface = 0xdead'faceU;
// 16 chiffres + "RAW " + séparateurs de lignes. La borne protège aussi les
// appels JNI directs qui n'ont pas traversé le parseur Kotlin.
constexpr std::size_t max_program_characters = 20U * 128U + 127U;
constexpr std::size_t max_program_lines = 128U;

struct DecodedCommand {
    GameSharkV1V2Program::Instruction instruction{};
    std::optional<CheatRomPatch> rom_patch;
};

struct Attempt {
    std::optional<DecodedCommand> command;
    const char* error{"Commande GameShark GBA v1/v2 inconnue"};
};

struct SourceWords {
    GameSharkV1V2Words words{};
    bool raw{};
};

[[nodiscard]] bool region_allowed(std::uint32_t address, int width, bool write) noexcept {
    const auto byte_width = static_cast<std::uint32_t>(width);
    if (width != 1 && width != 2 && width != 4) return false;
    if ((address % byte_width) != 0U) return false;
    const auto last = static_cast<std::uint64_t>(address) + byte_width - 1U;
    if ((last >> 24U) != (address >> 24U)) return false;
    const auto region = (address >> 24U) & 0xffU;
    if (write) {
        return (region >= 0x02U && region <= 0x07U) ||
            region == 0x0eU || region == 0x0fU;
    }
    return region >= 0x02U && region <= 0x07U;
}

[[nodiscard]] Attempt success(GameSharkV1V2Program::Instruction instruction) {
    return Attempt{DecodedCommand{instruction, std::nullopt}, ""};
}

[[nodiscard]] Attempt write_command(
    GameSharkV1V2Program::Kind kind,
    std::uint32_t address,
    std::uint32_t value,
    int width
) {
    if ((width == 1 && value > 0xffU) || (width == 2 && value > 0xffffU)) {
        return Attempt{std::nullopt, "Valeur GameShark incompatible avec la largeur"};
    }
    if (!region_allowed(address, width, true)) {
        return Attempt{std::nullopt, "Adresse GameShark GBA invalide ou non alignée"};
    }
    return success(GameSharkV1V2Program::Instruction{kind, address, value, 0U});
}

[[nodiscard]] bool hook_value(std::uint32_t value) noexcept {
    constexpr std::array accepted{
        0x0000'0001U,
        0x0000'0002U,
        0x0000'0003U,
        0x0000'0101U,
        0x0000'0102U,
        0x0000'0103U,
    };
    return std::ranges::find(accepted, value) != accepted.end();
}

[[nodiscard]] Attempt interpret(
    GameSharkV1V2Words words,
    std::span<const std::uint8_t> rom
) {
    if (words.left == deadface) {
        return Attempt{
            std::nullopt,
            "Master Code DEADFACE et changement de graines non pris en charge",
        };
    }
    if (words.right == game_id_marker) {
        // Vérification de cartouche du périphérique physique. La définition
        // RavenEmu est déjà attachée au SHA-256 de la ROM : aucun accès mémoire
        // ni rejet tardif n'est nécessaire ici.
        return success({});
    }

    const auto type = words.left >> 28U;
    const auto address = words.left & address_mask;
    switch (type) {
    case 0x0U:
        return write_command(
            GameSharkV1V2Program::Kind::write_8,
            address,
            words.right,
            1
        );
    case 0x1U:
        return write_command(
            GameSharkV1V2Program::Kind::write_16,
            address,
            words.right,
            2
        );
    case 0x2U:
        return write_command(
            GameSharkV1V2Program::Kind::write_32,
            address,
            words.right,
            4
        );
    case 0x3U:
        return Attempt{
            std::nullopt,
            "Group write GameShark GBA non pris en charge",
        };
    case 0x6U: {
        if ((words.left & 0xff00'0000U) != 0x6000'0000U) {
            return Attempt{std::nullopt, "Type de patch ROM GameShark non pris en charge"};
        }
        const auto mode = words.right >> 28U;
        if (mode > 1U || (words.right & 0x0fff'0000U) != 0U) {
            return Attempt{std::nullopt, "Type de patch ROM GameShark non pris en charge"};
        }
        const auto offset = (words.left & 0x00ff'ffffU) << 1U;
        if (static_cast<std::uint64_t>(offset) + 1U >= rom.size()) {
            return Attempt{std::nullopt, "Patch GameShark hors de la ROM chargée"};
        }
        DecodedCommand result{};
        result.rom_patch = CheatRomPatch{
            offset,
            static_cast<std::uint16_t>(words.right & 0xffffU),
        };
        return Attempt{std::move(result), ""};
    }
    case 0x8U: {
        const auto button = (words.left & 0xfff0'0000U) == 0x8a10'0000U ||
            (words.left & 0xfff0'0000U) == 0x8a20'0000U ||
            words.left == 0x80f0'0000U;
        return Attempt{
            std::nullopt,
            button
                ? "Commande liée au bouton physique GameShark non prise en charge"
                : "Commande GameShark GBA v1/v2 inconnue",
        };
    }
    case 0xdU:
        if (words.right > 0xffffU) {
            return Attempt{std::nullopt, "Valeur conditionnelle GameShark invalide"};
        }
        if (!region_allowed(address, 2, false)) {
            return Attempt{std::nullopt, "Adresse conditionnelle GameShark invalide"};
        }
        return success(GameSharkV1V2Program::Instruction{
            GameSharkV1V2Program::Kind::if_equal_16,
            address,
            words.right,
            1U,
        });
    case 0xeU: {
        if ((words.left & 0xff00'0000U) != 0xe000'0000U ||
            (words.right & 0xf000'0000U) != 0U
        ) {
            return Attempt{std::nullopt, "Commande conditionnelle GameShark inconnue"};
        }
        const auto count = static_cast<std::size_t>((words.left >> 16U) & 0xffU);
        const auto conditional_address = words.right & address_mask;
        if (count == 0U) {
            return Attempt{std::nullopt, "Condition GameShark multiligne vide"};
        }
        if (!region_allowed(conditional_address, 2, false)) {
            return Attempt{std::nullopt, "Adresse conditionnelle GameShark invalide"};
        }
        return success(GameSharkV1V2Program::Instruction{
            GameSharkV1V2Program::Kind::if_equal_16,
            conditional_address,
            words.left & 0xffffU,
            count,
        });
    }
    case 0xfU:
        if (address < 0x0800'0000U || address > 0x09ff'ffffU || !hook_value(words.right)) {
            return Attempt{std::nullopt, "Ligne de hook du Master Code GameShark invalide"};
        }
        // Le matériel détourne cette adresse pour appeler son interpréteur.
        // RavenEmu exécute déjà le programme après chaque trame : aucun patch
        // du code du jeu n'est nécessaire, mais la ligne est validée.
        return success({});
    default:
        return Attempt{std::nullopt, "Commande GameShark GBA v1/v2 inconnue"};
    }
}

[[nodiscard]] std::uint32_t hex_word(std::string_view text) {
    std::uint32_t result{};
    for (const auto character : text) {
        result <<= 4U;
        if (character >= '0' && character <= '9') {
            result |= static_cast<std::uint32_t>(character - '0');
        } else if (character >= 'A' && character <= 'F') {
            result |= static_cast<std::uint32_t>(character - 'A' + 10);
        } else {
            throw std::invalid_argument("Caractère non hexadécimal dans le code GameShark GBA");
        }
    }
    return result;
}

[[nodiscard]] SourceWords parse_words(std::string_view source) {
    constexpr std::string_view raw_prefix{"RAW"};
    while (!source.empty() && std::isspace(static_cast<unsigned char>(source.front())) != 0) {
        source.remove_prefix(1U);
    }
    const auto explicit_raw = source.size() > raw_prefix.size() &&
        std::ranges::equal(
            source.substr(0U, raw_prefix.size()),
            raw_prefix,
            {},
            [](char character) {
                return static_cast<char>(std::toupper(static_cast<unsigned char>(character)));
            }
        ) &&
        (std::isspace(static_cast<unsigned char>(source[raw_prefix.size()])) != 0 ||
            source[raw_prefix.size()] == ':');
    if (explicit_raw) source.remove_prefix(raw_prefix.size() + 1U);

    std::string compact;
    compact.reserve(source.size());
    for (const auto character : source) {
        const auto byte = static_cast<unsigned char>(character);
        if (std::isspace(byte) != 0) continue;
        compact.push_back(static_cast<char>(std::toupper(byte)));
    }
    if (compact.size() != 16U) {
        throw std::invalid_argument("Une ligne GameShark GBA contient deux mots de huit chiffres");
    }
    return SourceWords{
        GameSharkV1V2Words{
            hex_word(std::string_view{compact}.substr(0U, 8U)),
            hex_word(std::string_view{compact}.substr(8U, 8U)),
        },
        explicit_raw,
    };
}

[[nodiscard]] std::vector<std::string_view> program_lines(std::string_view source) {
    std::vector<std::string_view> result;
    std::size_t start{};
    while (start <= source.size()) {
        const auto end = source.find('\n', start);
        const auto length = end == std::string_view::npos ? source.size() - start : end - start;
        const auto line = source.substr(start, length);
        const auto non_space = std::ranges::any_of(line, [](char character) {
            return std::isspace(static_cast<unsigned char>(character)) == 0;
        });
        if (non_space) result.push_back(line);
        if (end == std::string_view::npos) break;
        start = end + 1U;
    }
    if (result.empty()) throw std::invalid_argument("Programme GameShark GBA vide");
    if (result.size() > max_program_lines) {
        throw std::invalid_argument("Programme GameShark GBA trop long");
    }
    return result;
}

} // namespace

GameSharkV1V2Words decrypt_gameshark_v1_v2(GameSharkV1V2Words encrypted) noexcept {
    auto left = encrypted.left;
    auto right = encrypted.right;
    for (std::uint32_t round = 32U; round > 0U; --round) {
        const auto sum = round * delta;
        right -= ((left << 4U) + seed_2) ^
            (left + sum) ^
            ((left >> 5U) + seed_3);
        left -= ((right << 4U) + seed_0) ^
            (right + sum) ^
            ((right >> 5U) + seed_1);
    }
    return GameSharkV1V2Words{left, right};
}

GameSharkV1V2Program GameSharkV1V2Program::compile(
    const CheatCode& code,
    std::span<const std::uint8_t> rom
) {
    if (code.format != CheatFormat::gameshark_gba_v1_v2) {
        throw std::invalid_argument("Format non pris en charge par le cœur GBA");
    }
    if (code.normalized.size() > max_program_characters) {
        throw std::invalid_argument("Programme GameShark GBA trop long");
    }

    GameSharkV1V2Program result;
    for (const auto source : program_lines(code.normalized)) {
        const auto parsed = parse_words(source);
        Attempt selected;
        if (parsed.raw) {
            const auto raw = interpret(parsed.words, rom);
            if (!raw.command) throw std::invalid_argument(raw.error);
            selected = raw;
        } else {
            const auto decrypted_words = decrypt_gameshark_v1_v2(parsed.words);
            if (decrypted_words.left == deadface) {
                throw std::invalid_argument(
                    "Master Code DEADFACE et changement de graines non pris en charge"
                );
            }
            const auto decrypted = interpret(decrypted_words, rom);
            if (decrypted.command) selected = decrypted;
            else throw std::invalid_argument(decrypted.error);
        }

        result.instructions_.push_back(selected.command->instruction);
        if (selected.command->rom_patch) {
            result.rom_patches_.push_back(*selected.command->rom_patch);
        }
    }

    for (std::size_t index = 0; index < result.instructions_.size(); ++index) {
        const auto skip = result.instructions_[index].skip_if_false;
        if (skip > 0U && index + skip >= result.instructions_.size()) {
            throw std::invalid_argument("Commande conditionnelle GameShark GBA incomplète");
        }
        if (skip > 0U) {
            const auto first = result.instructions_.begin() + static_cast<std::ptrdiff_t>(index + 1U);
            const auto last = first + static_cast<std::ptrdiff_t>(skip);
            if (std::ranges::any_of(first, last, [](const Instruction& instruction) {
                    return instruction.kind == Kind::no_op;
                })) {
                throw std::invalid_argument(
                    "Une condition GameShark ne peut pas cibler un Master Code ou patch ROM"
                );
            }
        }
    }
    return result;
}

void GameSharkV1V2Program::apply(Bus& bus) const noexcept {
    std::size_t index{};
    while (index < instructions_.size()) {
        const auto& instruction = instructions_[index];
        switch (instruction.kind) {
        case Kind::no_op:
            ++index;
            break;
        case Kind::write_8:
            static_cast<void>(bus.write_cheat(instruction.address, instruction.value, 1));
            ++index;
            break;
        case Kind::write_16:
            static_cast<void>(bus.write_cheat(instruction.address, instruction.value, 2));
            ++index;
            break;
        case Kind::write_32:
            static_cast<void>(bus.write_cheat(instruction.address, instruction.value, 4));
            ++index;
            break;
        case Kind::if_equal_16: {
            const auto current = bus.read_cheat(instruction.address, 2);
            if (!current || (*current & 0xffffU) != instruction.value) {
                index += instruction.skip_if_false + 1U;
            } else {
                ++index;
            }
            break;
        }
        }
    }
}

} // namespace ravenemu::gba
