#pragma once

#include <cstdint>

namespace ravenemu::nds {

/**
 * Contrôleur d'interruptions d'un processeur.
 *
 * Trois registres, et une règle simple : une interruption est prise quand
 * l'autorisation générale est donnée, que la source est autorisée, et qu'elle a
 * une demande en attente. C'est ce qui transforme un message déposé par l'autre
 * processeur en interruption réellement prise, plutôt qu'en donnée qui dort
 * dans un tampon.
 *
 * ### Sur les demandes en attente
 *
 * Le registre des demandes se comporte à l'envers de ce qu'on attend : **écrire
 * un bit à un l'efface**. C'est ainsi que le matériel acquitte, et un
 * gestionnaire écrit sur ce registre pour dire « j'ai traité », non pour lever
 * une demande. Un émulateur qui l'écrirait normalement laisserait les
 * interruptions se redéclencher sans fin.
 *
 * Une demande se pose sur un front, pas sur un niveau : elle reste inscrite
 * jusqu'à l'acquittement, même si la condition qui l'a produite a cessé. C'est
 * pourquoi `request` existe à part des registres, et n'est appelée que par les
 * organes matériels.
 *
 * ### Ce qui n'est pas là
 *
 * Les sources autres que la communication entre processeurs. Le registre les
 * accepte toutes — il n'a aucune raison de trier, et sa sémantique est complète
 * telle quelle — mais aucune ne les pose encore : ni retour de balayage, ni
 * minuteries, ni transferts autonomes, faute des organes correspondants.
 */
class InterruptController {
public:
    // Sources liées à la communication entre les deux processeurs.
    static constexpr std::uint32_t ipc_sync = 1U << 16U;
    static constexpr std::uint32_t ipc_send_queue_empty = 1U << 17U;
    static constexpr std::uint32_t ipc_receive_queue_filled = 1U << 18U;

    void reset() noexcept;

    /** Autorisation générale ; sans elle, aucune interruption n'est prise. */
    [[nodiscard]] std::uint32_t master_enable() const noexcept { return master_enable_; }
    void set_master_enable(std::uint32_t value) noexcept { master_enable_ = value & 0x1U; }

    /** Sources autorisées, une par bit. */
    [[nodiscard]] std::uint32_t enabled() const noexcept { return enabled_; }
    void set_enabled(std::uint32_t value) noexcept { enabled_ = value; }

    /** Demandes en attente, une par bit. */
    [[nodiscard]] std::uint32_t requested() const noexcept { return requested_; }

    /** Pose une demande. Réservé aux organes matériels. */
    void request(std::uint32_t source) noexcept { requested_ |= source; }

    /** Acquitte : chaque bit à un dans [mask] efface la demande correspondante. */
    void acknowledge(std::uint32_t mask) noexcept { requested_ &= ~mask; }

    /** Vrai quand le processeur doit prendre une interruption. */
    [[nodiscard]] bool line() const noexcept {
        return master_enable_ != 0U && (enabled_ & requested_) != 0U;
    }

private:
    std::uint32_t master_enable_{};
    std::uint32_t enabled_{};
    std::uint32_t requested_{};
};

} // namespace ravenemu::nds
