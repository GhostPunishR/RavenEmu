#pragma once

#include "cpu/bus.hpp"
#include "memory/system_memory.hpp"
#include "system/inter_processor.hpp"
#include "system/dma.hpp"
#include "system/cartridge.hpp"
#include "system/input.hpp"
#include "system/timers.hpp"
#include "video/video_system.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::nds {

/**
 * Carte mémoire vue par le processeur secondaire.
 *
 * Elle est beaucoup plus courte que celle du processeur principal, et c'est
 * fidèle : ce processeur ne voit ni palette, ni mémoire d'objets, ni la plupart
 * des banques vidéo. Il tient l'amorçage, le son, l'écran tactile et la liaison
 * sans fil, et sa mémoire s'organise autour de cela.
 *
 * ### Ce qu'il partage, ce qu'il a en propre
 *
 * La mémoire principale et la mémoire commune viennent de `SystemMemory` : ce
 * sont les mêmes octets que ceux du processeur principal, et c'est par eux que
 * les deux se parleront. En propre, il a soixante-quatre kilooctets de mémoire
 * de travail que l'autre ne voit pas.
 *
 * ### Le repli quand il n'a aucune part
 *
 * Le partage de la mémoire commune peut ne rien lui laisser. Dans ce cas, la
 * fenêtre qui lui était destinée ne devient pas muette : elle donne sur sa
 * mémoire propre. Le matériel prévoit ce repli, et il compte — un programme qui
 * s'y adresse continue de fonctionner après que l'autre processeur lui a tout
 * pris, au lieu de lire du vide.
 *
 * ### Ce qui n'est pas décodé
 *
 * Son propre programme d'amorçage, la cartouche, le port Game Boy Advance, et
 * les banques vidéo qui peuvent lui être confiées. Aucun de ces contenus
 * n'existe encore : les accès rendent zéro et sont comptés, plutôt qu'absorbés
 * en silence.
 */
class Arm7MemoryMap final : public Bus {
public:
    Arm7MemoryMap(
        SystemMemory& system,
        VideoSystem& video,
        InterProcessor& link,
        InterruptController& interrupts,
        InputState& input,
        Cartridge& cartridge
    );

    /** Mémoire de travail que ce processeur ne partage avec personne. */
    static constexpr std::uint32_t private_wram_bytes = 64U * 1024U;

    /** Première adresse de la mémoire propre, au-delà de la fenêtre partagée. */
    static constexpr std::uint32_t private_wram_base = 0x0380'0000;

    /**
     * Étendue de la région du programme d'amorçage de ce processeur.
     *
     * Elle est en bas de l'espace, là où le matériel la place, et non en haut
     * comme celle du processeur principal : c'est aussi là que se trouve sa
     * table des vecteurs d'exception, ce processeur n'ayant pas de coprocesseur
     * pour la déplacer.
     */
    static constexpr std::uint32_t bios_base = 0x0000'0000;
    static constexpr std::uint32_t bios_bytes = 0x4000;

    /** La région du programme d'amorçage, que le seul organe qui l'écrit remplit. */
    [[nodiscard]] std::span<std::uint8_t> bios() noexcept { return bios_; }
    /** Vue en lecture seule du partage de la mémoire commune. */
    static constexpr std::uint32_t shared_wram_status = 0x0400'0241;

    /**
     * Registres du balayage, aux mêmes adresses que chez l'autre processeur.
     *
     * L'état est propre à ce processeur, avec ses propres autorisations
     * d'interruption ; le compteur de lignes est le même faisceau pour les deux.
     */
    static constexpr std::uint32_t display_status = 0x0400'0004;
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

    /**
     * Registre des entrées que seul ce processeur voit.
     *
     * Les deux touches supplémentaires, le contact de l'écran tactile et celui
     * du couvercle. Le processeur principal n'y a pas accès : il doit les
     * demander par la file.
     */
    static constexpr std::uint32_t extra_key_input = 0x0400'0136;


    /** Écart entre deux minuteries : deux registres de seize bits. */
    static constexpr std::uint32_t timer_stride = 4;

    /**
     * Registre d'arrêt du processeur.
     *
     * C'est par lui que ce processeur attend une interruption, là où l'autre
     * passe par son coprocesseur. Ses deux bits de poids fort portent le mode :
     * seul l'arrêt est modélisé. La mise en veille coupe l'horloge de la console
     * entière et ne se lève que par une touche ou la charnière, dont rien
     * n'existe ici ; l'approcher par un arrêt donnerait une console qui repart
     * au retour vertical alors qu'elle devrait rester éteinte. Elle est donc
     * comptée, comme le mode Game Boy Advance qui partage ce champ.
     *
     * En lecture, ce registre est compté lui aussi : ce qu'il rend n'est
     * affirmé nulle part, et l'inventer serait une affirmation que rien ne
     * vérifie.
     */
    static constexpr std::uint32_t halt_control = 0x0400'0301;
    /** Valeur du champ de mode qui arrête le processeur. */
    static constexpr std::uint8_t halt_mode = 2;

    void reset() noexcept;

    [[nodiscard]] std::uint8_t read8(std::uint32_t address) override;
    [[nodiscard]] std::uint16_t read16(std::uint32_t address) override;
    [[nodiscard]] std::uint32_t read32(std::uint32_t address) override;

    void write8(std::uint32_t address, std::uint8_t value) override;
    void write16(std::uint32_t address, std::uint16_t value) override;
    void write32(std::uint32_t address, std::uint32_t value) override;

    [[nodiscard]] std::span<std::uint8_t> private_wram() noexcept { return private_wram_; }

    /**
     * Retire la demande d'arrêt, s'il y en a une.
     *
     * La carte ne connaît pas le processeur et ne peut pas l'arrêter elle-même :
     * elle ne fait que retenir ce que le registre a reçu. C'est l'organe qui
     * monte les deux ensemble qui transporte la demande, comme il transporte la
     * ligne d'interruption.
     */
    [[nodiscard]] bool take_halt_request() noexcept {
        const bool requested = halt_requested_;
        halt_requested_ = false;
        return requested;
    }

    /** Le réglage du réveil par les touches, propre à ce processeur. */
    [[nodiscard]] KeyInterrupt& key_interrupt() noexcept { return key_interrupt_; }

    /** Les minuteries de ce processeur. */
    [[nodiscard]] Timers& timers() noexcept { return timers_; }

    /** Les canaux de transfert autonome de ce processeur. */
    [[nodiscard]] DmaController& dma() noexcept { return dma_; }


    /** Part de la mémoire commune revenant à ce processeur. */
    [[nodiscard]] SystemMemory::Window shared_window() const noexcept {
        return system_.secondary_processor_window();
    }

    [[nodiscard]] std::uint32_t unmapped_count() const noexcept { return unmapped_; }
    [[nodiscard]] std::uint32_t first_unmapped() const noexcept { return first_unmapped_; }

    [[nodiscard]] std::uint32_t unimplemented_io_count() const noexcept { return unimplemented_io_; }
    [[nodiscard]] std::uint32_t first_unimplemented_io() const noexcept { return first_unimplemented_io_; }

private:
    enum class Region {
        unmapped,
        main_ram,
        shared_wram,
        private_wram,
        input_output,
        boot_program,
    };

    struct Location {
        Region region{Region::unmapped};
        std::uint8_t* data{nullptr};
    };

    [[nodiscard]] Location locate(std::uint32_t address) noexcept;

    [[nodiscard]] std::uint32_t read(std::uint32_t address, std::uint32_t width) noexcept;
    void write(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept;

    // Les entrées-sorties sont sensibles à la largeur : retirer un mot d'une
    // file en quatre morceaux d'un octet le retirerait quatre fois. Les
    // registres larges sont donc traités d'un bloc, et le reste octet par octet.
    [[nodiscard]] std::uint32_t read_io(std::uint32_t address, std::uint32_t width) noexcept;
    void write_io(std::uint32_t address, std::uint32_t value, std::uint32_t width) noexcept;
    [[nodiscard]] std::uint8_t read_io_byte(std::uint32_t address) noexcept;
    void write_io_byte(std::uint32_t address, std::uint8_t value) noexcept;

    void note_unmapped(std::uint32_t address) noexcept;
    void note_unimplemented_io(std::uint32_t address) noexcept;

    SystemMemory& system_;
    VideoSystem& video_;
    InterProcessor& link_;
    InterruptController& interrupts_;
    InputState& input_;
    Cartridge& cartridge_;

    KeyInterrupt key_interrupt_{};

    Timers timers_{interrupts_};
    DmaController dma_{Processor::secondary, interrupts_};
    std::vector<std::uint8_t> private_wram_;

    /**
     * Le programme d'amorçage, en lecture seule.
     *
     * Il ne contient pas celui de la console, que ce dépôt ne fournit pas, mais
     * le peu de code que RavenEmu y écrit pour que la table des vecteurs mène
     * quelque part.
     */
    std::vector<std::uint8_t> bios_ = std::vector<std::uint8_t>(bios_bytes, 0);

    bool halt_requested_{};

    std::uint32_t unmapped_{};
    std::uint32_t first_unmapped_{};
    std::uint32_t unimplemented_io_{};
    std::uint32_t first_unimplemented_io_{};
};

} // namespace ravenemu::nds
