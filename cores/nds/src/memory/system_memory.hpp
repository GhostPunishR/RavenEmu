#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace ravenemu::nds {

/**
 * Mémoire que les deux processeurs se partagent.
 *
 * Deux blocs seulement, mais ce sont eux qui font de la console une machine à
 * deux têtes plutôt que deux machines côte à côte. Les tenir ici, et non dans
 * l'une des deux cartes, est ce qui permet qu'une écriture faite par un
 * processeur soit vue par l'autre — sans quoi la communication entre eux serait
 * impossible à écrire.
 *
 * ### Le partage de la mémoire commune
 *
 * Trente-deux kilooctets se répartissent selon un registre, en quatre
 * découpages **complémentaires** : ce que l'un reçoit, l'autre ne l'a pas. Deux
 * de ces découpages laissent l'un des processeurs sans aucune part, et c'est un
 * état légitime — le matériel prévoit qu'un processeur cède tout à l'autre.
 *
 * La complémentarité est modélisée par deux fenêtres calculées depuis le même
 * registre, plutôt que par deux tables indépendantes : une seule source, donc
 * pas de découpage où les deux processeurs se croiraient propriétaires du même
 * octet.
 */
class SystemMemory {
public:
    SystemMemory();

    static constexpr std::uint32_t main_ram_bytes = 4U * 1024U * 1024U;
    static constexpr std::uint32_t shared_wram_bytes = 32U * 1024U;

    void reset() noexcept;

    /** Part de la mémoire commune revenant à un processeur. */
    struct Window {
        /** Décalage de la part dans les trente-deux kilooctets. */
        std::uint32_t offset;
        /** Étendue de la part, nulle si le processeur n'en reçoit aucune. */
        std::uint32_t size;
    };

    [[nodiscard]] Window main_processor_window() const noexcept;
    [[nodiscard]] Window secondary_processor_window() const noexcept;

    /**
     * Découpage qui confie **toute** la mémoire commune au processeur
     * secondaire.
     *
     * C'est le seul des quatre qui soit nommé, parce que c'est le seul dont un
     * autre organe ait besoin : l'amorçage le pose pour que le bloc de code du
     * processeur secondaire tombe où son en-tête le demande. Les trois autres
     * ne se désignent que par le registre, qu'un programme écrit lui-même.
     */
    static constexpr std::uint8_t shared_to_secondary = 3;

    /** Registre qui commande le partage ; seuls ses deux bits bas comptent. */
    [[nodiscard]] std::uint8_t shared_control() const noexcept { return shared_control_; }
    void set_shared_control(std::uint8_t value) noexcept { shared_control_ = value & 0x3U; }

    [[nodiscard]] std::span<std::uint8_t> main_ram() noexcept { return main_ram_; }
    [[nodiscard]] std::span<std::uint8_t> shared_wram() noexcept { return shared_wram_; }

private:
    std::vector<std::uint8_t> main_ram_;
    std::vector<std::uint8_t> shared_wram_;
    std::uint8_t shared_control_{};
};

} // namespace ravenemu::nds
