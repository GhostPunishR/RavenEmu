#pragma once

#include "cpu/bus.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"
#include "system/dma.hpp"
#include "system/cartridge.hpp"
#include "system/input.hpp"
#include "system/timers.hpp"
#include "video/video_system.hpp"

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
 * ### Le programme d'amorçage
 *
 * La région du programme d'amorçage existe, en haut de l'espace d'adressage, et
 * elle est **en lecture seule**. Elle ne contient pas celui de la console, que
 * ce dépôt ne fournit pas : elle contient le peu de code que RavenEmu y écrit
 * lui-même pour que la table des vecteurs mène quelque part. Le reste des
 * services du programme d'amorçage est rendu hors du processeur, l'appel
 * logiciel étant intercepté avant d'atteindre son vecteur.
 *
 * ### Ce qu'elle ne décide pas encore
 *
 * La cartouche et le port Game Boy Advance. Aucun de ces contenus n'existe dans
 * le dépôt, et aucun n'est fourni : les lectures rendent zéro et sont comptées.
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
    Arm9MemoryMap(
        SystemMemory& system,
        VideoSystem& video,
        InterProcessor& link,
        InterruptController& interrupts,
        InputState& input,
        Cartridge& cartridge
    );

    static constexpr std::uint32_t main_ram_bytes = SystemMemory::main_ram_bytes;
    static constexpr std::uint32_t shared_wram_bytes = SystemMemory::shared_wram_bytes;
    static constexpr std::uint32_t palette_bytes = VideoSystem::palette_bytes;
    static constexpr std::uint32_t oam_bytes = VideoSystem::object_attribute_bytes;

    /** Nombre de banques vidéo, nommées de A à I sur le matériel. */
    static constexpr std::size_t vram_bank_count = VideoMemory::bank_count;
    /** Première adresse de la fenêtre de transfert des banques vidéo. */
    static constexpr std::uint32_t vram_transfer_base = VideoMemory::transfer_base;

    /** Registres qui gouvernent la carte elle-même. */
    /**
     * Étendue de la région du programme d'amorçage, celle du matériel.
     *
     * Seuls les premiers octets portent quelque chose ; le reste est nul. La
     * taille est celle de la console plutôt que celle du contenu, parce qu'un
     * programme qui lit au-delà de ce que RavenEmu y écrit doit trouver la même
     * étendue que sur console, et non un bord au premier octet inutilisé.
     */
    static constexpr std::uint32_t bios_base = 0xffff'0000;
    static constexpr std::uint32_t bios_bytes = 0x8000;

    /** La région du programme d'amorçage, que le seul organe qui l'écrit remplit. */
    [[nodiscard]] std::span<std::uint8_t> bios() noexcept { return bios_; }

    static constexpr std::uint32_t vram_control_base = 0x0400'0240;
    static constexpr std::uint32_t shared_wram_control = 0x0400'0247;

    /** Bloc de registres de chaque moteur graphique. */
    static constexpr std::uint32_t main_engine_base = 0x0400'0000;
    static constexpr std::uint32_t secondary_engine_base = 0x0400'1000;
    static constexpr std::uint32_t engine_register_bytes = 0x20;

    /** Registres du balayage, communs aux deux processeurs. */
    static constexpr std::uint32_t display_status = 0x0400'0004;
    /**
     * Compteur de lignes, en lecture seule ici.
     *
     * Le matériel accepte qu'on l'écrive, ce qui déplace le faisceau. Aucun
     * logiciel n'en a l'usage dans ce dépôt, et le modéliser sans rien pour
     * l'exercer serait une affirmation que rien ne vérifie : l'écriture tombe
     * donc dans le comptage des registres sans effet, comme n'importe quel autre.
     */
    static constexpr std::uint32_t line_counter = 0x0400'0006;
    /**
     * Les quatre minuteries de ce processeur.
     *
     * Chaque processeur a les siennes, aux mêmes adresses. Elles appartiennent
     * donc à sa carte, comme le registre d'alimentation, et non à un organe
     * partagé : rien de ce qu'elles comptent ne traverse d'un processeur à
     * l'autre.
     */
    static constexpr std::uint32_t timer_base = 0x0400'0100;

    /**
     * Les quatre canaux de transfert autonome de ce processeur.
     *
     * Comme les minuteries, ils appartiennent à sa carte : leurs registres sont
     * les siens, et ce qu'ils copient passe par sa vue de la mémoire.
     */
    static constexpr std::uint32_t dma_base = 0x0400'00b0;

    /**
     * Registre des dix touches, et réglage du réveil qu'elles peuvent poser.
     *
     * L'état des touches est partagé ; le réglage du réveil ne l'est pas, chaque
     * processeur choisissant les siennes.
     */
    static constexpr std::uint32_t key_input = 0x0400'0130;
    static constexpr std::uint32_t key_control = 0x0400'0132;


    /** Écart entre deux minuteries : deux registres de seize bits. */
    static constexpr std::uint32_t timer_stride = 4;

    /** Registre d'alimentation, dont un bit échange les deux écrans. */
    /**
     * Drapeau de fin d’amorçage.
     *
     * Le programme d’amorçage de la console y pose son bit bas juste avant de
     * rendre la main au jeu, et ce bit ne se retire plus : il dit « l’amorçage
     * est passé ». Un jeu le consulte pour savoir s’il démarre de la console ou
     * d’une relance.
     *
     * Ce cœur amorce sans faire tourner ce programme, mais il rend la main au
     * jeu **au même moment** : le drapeau sort donc de la remise à zéro déjà
     * posé. Le laisser nul faisait attendre les deux processeurs une fin
     * d’amorçage qui ne serait jamais venue : un vrai jeu le consultait cinq
     * cents fois par trame sans jamais obtenir sa réponse.
     */
    static constexpr std::uint32_t post_boot_flag = 0x0400'0300;
    /** Le bit que l’amorçage pose, et que rien ne retire ensuite. */
    static constexpr std::uint8_t post_boot_done = 0x01;

    static constexpr std::uint32_t power_control = 0x0400'0304;
    static constexpr std::uint16_t power_swaps_screens = 1U << 15U;

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
    [[nodiscard]] std::span<std::uint8_t> palette() noexcept { return video_.palette(); }
    [[nodiscard]] std::span<std::uint8_t> object_attributes() noexcept {
        return video_.object_attributes();
    }
    [[nodiscard]] std::span<std::uint8_t> vram_bank(std::size_t index) noexcept;

    /** Les banques et leur aiguillage, que les deux moteurs se partagent. */
    [[nodiscard]] VideoMemory& video() noexcept { return video_.memory(); }
    /** L'un des deux moteurs graphiques. */
    [[nodiscard]] Engine2d& engine(Engine which) noexcept { return video_.engine(which); }
    /** Le balayage, que les deux processeurs consultent. */
    [[nodiscard]] DisplayController& display() noexcept { return video_.display(); }

    /** Le réglage du réveil par les touches, propre à ce processeur. */
    [[nodiscard]] KeyInterrupt& key_interrupt() noexcept { return key_interrupt_; }

    /** Les minuteries de ce processeur. */
    [[nodiscard]] Timers& timers() noexcept { return timers_; }

    /** Les canaux de transfert autonome de ce processeur. */
    [[nodiscard]] DmaController& dma() noexcept { return dma_; }


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
        boot_program,
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

    /**
     * Moteur commandé par une adresse, et le rang du registre visé.
     *
     * Ne reconnaît que les registres réellement portés par un moteur : entre la
     * commande d'affichage et les commandes de plans se trouvent l'état du
     * balayage et le compteur de lignes, qui appartiennent à l'écran et non au
     * moteur, et qui n'ont pas de jumeau du côté du moteur secondaire.
     */
    [[nodiscard]] Engine2d* engine_register(std::uint32_t address, std::uint32_t& offset) noexcept;
    [[nodiscard]] std::uint8_t read_engine_byte(Engine2d& engine, std::uint32_t offset) noexcept;
    void write_engine_byte(Engine2d& engine, std::uint32_t offset, std::uint8_t value) noexcept;

    // Les entrées-sorties sont sensibles à la largeur : retirer un mot d'une
    // file en quatre morceaux d'un octet le retirerait quatre fois. Les
    // registres larges sont donc traités d'un bloc, et le reste octet par octet.
    [[nodiscard]] std::uint32_t read_io(std::uint32_t address, std::uint32_t width) noexcept;
    void write_io(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept;
    [[nodiscard]] std::uint8_t read_io_byte(std::uint32_t address) noexcept;
    void write_io_byte(std::uint32_t address, std::uint8_t value) noexcept;

    void note_unmapped(std::uint32_t address) noexcept;
    void note_unimplemented_io(std::uint32_t address) noexcept;

    /**
     * Vrai pour une région que le matériel ne laisse pas écrire du tout.
     *
     * Le refus est silencieux, comme pour l'octet seul : un programme qui écrit
     * dans le programme d'amorçage ne change rien, sur console comme ici. Le
     * compter comme une adresse non décodée serait faux, l'adresse étant bel et
     * bien décodée.
     */
    [[nodiscard]] static bool is_read_only(Region region) noexcept {
        return region == Region::boot_program;
    }

    /** Vrai pour les régions que le matériel refuse d'écrire octet par octet. */
    [[nodiscard]] static bool ignores_byte_writes(Region region) noexcept {
        return region == Region::palette || region == Region::video ||
            region == Region::object_attributes;
    }

    SystemMemory& system_;
    VideoSystem& video_;
    InterProcessor& link_;
    InterruptController& interrupts_;
    InputState& input_;
    Cartridge& cartridge_;

    KeyInterrupt key_interrupt_{};

    Timers timers_{interrupts_};
    DmaController dma_{Processor::main, interrupts_};

    /** Registre d'alimentation, dont seul le bit d'échange agit. */
    std::uint16_t power_{};

    /**
     * Registre du partage des ports externes.
     *
     * Il est chez le processeur principal parce que c'est lui qui en décide :
     * le secondaire n'en voit qu'un reflet en lecture, qui n'est pas modélisé.
     */
    std::uint16_t external_memory_{};

    /**
     * Le programme d'amorçage, en lecture seule.
     *
     * Vecteurs d'exception et gestionnaire d'interruption y sont écrits par
     * l'organe qui rend les services du programme d'amorçage ; cette carte ne
     * fait que porter les octets et refuser qu'on les change, comme le matériel.
     */
    std::vector<std::uint8_t> bios_ = std::vector<std::uint8_t>(bios_bytes, 0);

    std::uint32_t unmapped_{};
    std::uint32_t first_unmapped_{};
    std::uint8_t post_boot_{};
    std::uint32_t unimplemented_io_{};
    std::uint32_t first_unimplemented_io_{};
};

} // namespace ravenemu::nds
