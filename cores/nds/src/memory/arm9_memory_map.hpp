#pragma once

#include "cpu/bus.hpp"
#include "memory/system_memory.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::nds {

/**
 * Carte mémoire vue par le processeur principal.
 *
 * La mémoire principale et la mémoire commune ne lui appartiennent pas : elles
 * vivent dans `SystemMemory`, que les deux processeurs partagent. Cette carte
 * n'en décrit que la vue — où elles répondent, et quelle part de la mémoire
 * commune lui revient.
 *
 * ### Ce qu'elle décide
 *
 * Une adresse ne désigne rien par elle-même : c'est cette carte qui dit à quoi
 * elle mène, et le même nombre peut désigner deux choses différentes selon la
 * configuration. Deux mécanismes y pourvoient, et tous deux sont modélisés :
 *
 * - **Le partage de la mémoire commune.** Trente-deux kilooctets se répartissent
 *   entre les deux processeurs selon un registre, en quatre découpages. Le
 *   processeur principal peut en recevoir tout, la moitié haute, la moitié
 *   basse, ou rien du tout — et « rien du tout » est un état légitime, pas une
 *   panne.
 * - **Les mémoires locales du cœur.** Elles ne passent pas par ici : le
 *   processeur les consulte avant le bus, et cette carte ne les voit jamais.
 *   C'est ce qui explique qu'une adresse basse puisse ne rien désigner ici alors
 *   qu'elle répond très bien au processeur.
 *
 * Le reste est du miroir : chaque région se répète sur toute son étendue
 * d'adressage, parce que le matériel ne décode pas les bits hauts. Quatre
 * mégaoctets de mémoire principale se répètent quatre fois dans les seize
 * mégaoctets qui leur sont réservés, et deux kilooctets de palette huit mille
 * fois.
 *
 * ### Ce qu'elle ne décide pas encore
 *
 * Les fenêtres des moteurs graphiques. Les neuf banques vidéo existent et sont
 * atteignables par la fenêtre de transfert, celle qu'on emprunte pour les
 * remplir ; en revanche l'aiguillage qui les présente aux moteurs 2D et 3D
 * viendra avec ces moteurs, seuls à pouvoir dire s'il est juste. Un accès à ces
 * fenêtres est compté, pas absorbé en silence.
 *
 * Le BIOS, la cartouche et le port Game Boy Advance. Aucun de ces contenus
 * n'existe dans le dépôt, et aucun n'est fourni : les lectures rendent zéro et
 * sont comptées.
 *
 * ### Sur les écritures d'un seul octet
 *
 * La palette, les banques vidéo et la mémoire d'objets n'acceptent pas
 * l'écriture d'un octet seul : le matériel l'ignore. Le silence est ici le
 * comportement juste, et non un manque — un programme qui écrit un octet dans
 * la palette ne change rien, sur console comme ici.
 */
class Arm9MemoryMap final : public Bus {
public:
    explicit Arm9MemoryMap(SystemMemory& system);

    static constexpr std::uint32_t main_ram_bytes = SystemMemory::main_ram_bytes;
    static constexpr std::uint32_t shared_wram_bytes = SystemMemory::shared_wram_bytes;
    static constexpr std::uint32_t palette_bytes = 2U * 1024U;
    static constexpr std::uint32_t oam_bytes = 2U * 1024U;

    /** Nombre de banques vidéo, nommées de A à I sur le matériel. */
    static constexpr std::size_t vram_bank_count = 9;
    /** Première adresse de la fenêtre de transfert des banques vidéo. */
    static constexpr std::uint32_t vram_transfer_base = 0x0680'0000;

    /** Registres qui gouvernent la carte elle-même. */
    static constexpr std::uint32_t vram_control_base = 0x0400'0240;
    static constexpr std::uint32_t shared_wram_control = 0x0400'0247;

    void reset() noexcept;

    [[nodiscard]] std::uint8_t read8(std::uint32_t address) override;
    [[nodiscard]] std::uint16_t read16(std::uint32_t address) override;
    [[nodiscard]] std::uint32_t read32(std::uint32_t address) override;

    void write8(std::uint32_t address, std::uint8_t value) override;
    void write16(std::uint32_t address, std::uint16_t value) override;
    void write32(std::uint32_t address, std::uint32_t value) override;

    // Accès direct au contenu, pour préparer un décor ou charger une image sans
    // passer par des milliers d'écritures.
    [[nodiscard]] std::span<std::uint8_t> main_ram() noexcept { return system_.main_ram(); }
    [[nodiscard]] std::span<std::uint8_t> shared_wram() noexcept { return system_.shared_wram(); }
    [[nodiscard]] std::span<std::uint8_t> palette() noexcept { return palette_; }
    [[nodiscard]] std::span<std::uint8_t> object_attributes() noexcept { return oam_; }
    [[nodiscard]] std::span<std::uint8_t> vram_bank(std::size_t index) noexcept;

    /** Part de la mémoire commune revenant à ce processeur. */
    [[nodiscard]] SystemMemory::Window shared_window() const noexcept {
        return system_.main_processor_window();
    }

    /** Accès à une adresse que rien ne décode. */
    [[nodiscard]] std::uint32_t unmapped_count() const noexcept { return unmapped_; }
    [[nodiscard]] std::uint32_t first_unmapped() const noexcept { return first_unmapped_; }

    /** Accès à un registre d'entrée-sortie encore sans effet. */
    [[nodiscard]] std::uint32_t unimplemented_io_count() const noexcept { return unimplemented_io_; }
    [[nodiscard]] std::uint32_t first_unimplemented_io() const noexcept { return first_unimplemented_io_; }

private:
    /** Nature d'une adresse, telle que le décodeur la reconnaît. */
    enum class Region {
        unmapped,
        main_ram,
        shared_wram,
        input_output,
        palette,
        video,
        object_attributes,
    };

    /** Où mène une adresse : une région, et le cas échéant l'octet visé. */
    struct Location {
        Region region{Region::unmapped};
        std::uint8_t* data{nullptr};
    };

    [[nodiscard]] Location locate(std::uint32_t address) noexcept;
    [[nodiscard]] Location locate_video(std::uint32_t address) noexcept;

    [[nodiscard]] std::uint32_t read(std::uint32_t address, std::uint32_t width) noexcept;
    void write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept;

    [[nodiscard]] static bool bank_control_index(std::uint32_t address, std::size_t& index) noexcept;

    [[nodiscard]] std::uint8_t read_io(std::uint32_t address) noexcept;
    void write_io(std::uint32_t address, std::uint8_t value) noexcept;

    void note_unmapped(std::uint32_t address) noexcept;
    void note_unimplemented_io(std::uint32_t address) noexcept;

    /** Vrai pour les régions que le matériel refuse d'écrire octet par octet. */
    [[nodiscard]] static bool ignores_byte_writes(Region region) noexcept {
        return region == Region::palette || region == Region::video ||
            region == Region::object_attributes;
    }

    SystemMemory& system_;
    std::vector<std::uint8_t> palette_;
    std::vector<std::uint8_t> oam_;
    std::vector<std::uint8_t> vram_;

    /** Un octet de commande par banque vidéo. */
    std::array<std::uint8_t, vram_bank_count> vram_control_{};

    std::uint32_t unmapped_{};
    std::uint32_t first_unmapped_{};
    std::uint32_t unimplemented_io_{};
    std::uint32_t first_unimplemented_io_{};
};

} // namespace ravenemu::nds
