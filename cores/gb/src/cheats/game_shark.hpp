#pragma once

#include <ravenemu/cheats.hpp>

#include <cctype>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace ravenemu::cgb {

/** Écriture RAM décodée du format GameShark `AB CD EF GH` / adresse `GHEF`. */
struct GameSharkRamWrite {
    std::uint8_t external_ram_bank{};
    std::uint8_t value{};
    std::uint16_t address{};
    std::string normalized;

    [[nodiscard]] static GameSharkRamWrite parse(std::string_view raw) {
        if (raw.size() > max_raw_length) {
            throw std::invalid_argument("Code GameShark trop long");
        }
        std::string code;
        code.reserve(raw.size());
        for (const char character : raw) {
            const auto byte = static_cast<unsigned char>(character);
            if (character == '-' || std::isspace(byte) != 0) continue;
            if (character >= 'a' && character <= 'f') {
                code.push_back(static_cast<char>(character - 'a' + 'A'));
            } else {
                code.push_back(character);
            }
        }
        if (code.empty()) throw std::invalid_argument("Code GameShark vide");
        if (code.size() != code_length) {
            throw std::invalid_argument("Un code GameShark doit contenir huit chiffres hexadécimaux");
        }
        for (const char character : code) {
            if (hex_value(character) < 0) {
                throw std::invalid_argument("Caractère non hexadécimal dans le code GameShark");
            }
        }

        const int bank = hex_byte(code, 0);
        if (bank < min_external_ram_bank || bank > max_external_ram_bank) {
            throw std::invalid_argument("Banque ou variante GameShark non prise en charge");
        }
        const int value = hex_byte(code, 2);
        const int address = (hex_byte(code, 6) << 8) | hex_byte(code, 4);
        if (address < min_address || address > max_address) {
            throw std::invalid_argument("Adresse GameShark hors de la RAM A000-DFFF");
        }
        return {
            static_cast<std::uint8_t>(bank),
            static_cast<std::uint8_t>(value),
            static_cast<std::uint16_t>(address),
            std::move(code),
        };
    }

    [[nodiscard]] static GameSharkRamWrite parse(const CheatCode& code) {
        if (code.format != CheatFormat::gameshark_gb_gbc) {
            throw std::invalid_argument("Format de cheat non pris en charge par le cœur Game Boy");
        }
        return parse(code.normalized);
    }

    static constexpr std::size_t code_length = 8;
    static constexpr std::size_t max_raw_length = 64;
    static constexpr int min_external_ram_bank = 0x00;
    static constexpr int max_external_ram_bank = 0x0f;
    static constexpr int min_address = 0xa000;
    static constexpr int max_address = 0xdfff;

private:
    [[nodiscard]] static int hex_value(char character) noexcept {
        if (character >= '0' && character <= '9') return character - '0';
        if (character >= 'A' && character <= 'F') return character - 'A' + 10;
        return -1;
    }

    [[nodiscard]] static int hex_byte(const std::string& code, std::size_t offset) noexcept {
        return (hex_value(code[offset]) << 4) | hex_value(code[offset + 1]);
    }
};

} // namespace ravenemu::cgb
