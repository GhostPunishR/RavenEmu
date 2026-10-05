#pragma once

#include "system/interrupt_controller.hpp"

#include <array>
#include <cstdint>

namespace ravenemu::nds {

/** Lequel des deux processeurs s'adresse au matériel partagé. */
enum class Processor {
    main,
    secondary,
};

/**
 * Communication entre les deux processeurs.
 *
 * C'est par ici que la console cesse d'être deux machines côte à côte. Deux
 * mécanismes, et ils ne servent pas à la même chose :
 *
 * - **Le registre de synchronisation** porte quatre bits dans chaque sens, plus
 *   de quoi réveiller l'autre. Il sert aux échanges brefs — un état, un accusé,
 *   une étape d'amorçage — là où ouvrir une file serait disproportionné.
 * - **Les deux files** portent seize mots chacune, dans un sens et dans
 *   l'autre. Elles servent aux commandes et à leurs réponses.
 *
 * ### Sur les erreurs de file
 *
 * Lire une file vide ou écrire dans une file pleine n'est pas refusé : le
 * matériel inscrit une erreur, rend la dernière valeur lue pour l'une, écarte le
 * mot pour l'autre, et continue. Le logiciel est censé consulter cette erreur.
 * La modéliser plutôt que de lever une exception est le seul choix fidèle — un
 * programme qui déborde sa file ne s'arrête pas sur console.
 *
 * L'erreur ne s'efface pas toute seule : elle s'acquitte en écrivant un bit à
 * un, comme les demandes d'interruption.
 *
 * ### Sur les interruptions
 *
 * Trois sources naissent ici, et chacune va au bon destinataire, ce qui n'est
 * pas évident : la file qui se remplit réveille **celui qui reçoit**, la file
 * qui se vide réveille **celui qui envoie**, et la synchronisation réveille
 * celui qu'on désigne. Se tromper de destinataire donne deux processeurs qui
 * s'attendent l'un l'autre sans fin.
 */
class InterProcessor {
public:
    InterProcessor(InterruptController& main, InterruptController& secondary) noexcept;

    /** Profondeur de chaque file, en mots. */
    static constexpr std::size_t queue_depth = 16;

    // Bits du registre de synchronisation.
    static constexpr std::uint16_t sync_input_mask = 0x000f;
    static constexpr std::uint16_t sync_output_mask = 0x0f00;
    static constexpr std::uint16_t sync_send_interrupt = 1U << 13U;
    static constexpr std::uint16_t sync_accept_interrupt = 1U << 14U;

    // Bits du registre de commande des files.
    static constexpr std::uint16_t send_queue_empty = 1U << 0U;
    static constexpr std::uint16_t send_queue_full = 1U << 1U;
    static constexpr std::uint16_t send_empty_interrupt = 1U << 2U;
    static constexpr std::uint16_t send_queue_clear = 1U << 3U;
    static constexpr std::uint16_t receive_queue_empty = 1U << 8U;
    static constexpr std::uint16_t receive_queue_full = 1U << 9U;
    static constexpr std::uint16_t receive_filled_interrupt = 1U << 10U;
    static constexpr std::uint16_t queue_error = 1U << 14U;
    static constexpr std::uint16_t queues_enabled = 1U << 15U;

    void reset() noexcept;

    [[nodiscard]] std::uint16_t read_sync(Processor side) const noexcept;
    void write_sync(Processor side, std::uint16_t value) noexcept;

    [[nodiscard]] std::uint16_t read_control(Processor side) const noexcept;
    void write_control(Processor side, std::uint16_t value) noexcept;

    /** Dépose un mot à destination de l'autre processeur. */
    void send(Processor side, std::uint32_t value) noexcept;
    /** Retire un mot déposé par l'autre processeur. */
    [[nodiscard]] std::uint32_t receive(Processor side) noexcept;

private:
    /** File circulaire de profondeur fixe. */
    class Queue {
    public:
        [[nodiscard]] bool empty() const noexcept { return count_ == 0U; }
        [[nodiscard]] bool full() const noexcept { return count_ == queue_depth; }
        void clear() noexcept { head_ = 0U; count_ = 0U; }
        void push(std::uint32_t value) noexcept;
        [[nodiscard]] std::uint32_t pop() noexcept;

    private:
        std::array<std::uint32_t, queue_depth> words_{};
        std::size_t head_{};
        std::size_t count_{};
    };

    /** Tout ce qu'un côté possède en propre. */
    struct Side {
        std::uint16_t sync_output{};
        bool accepts_sync_interrupt{};
        bool sends_empty_interrupt{};
        bool receives_filled_interrupt{};
        bool queues_enabled{};
        bool error{};
        std::uint32_t last_received{};
    };

    [[nodiscard]] Side& side_of(Processor side) noexcept;
    [[nodiscard]] const Side& side_of(Processor side) const noexcept;
    [[nodiscard]] InterruptController& controller_of(Processor side) noexcept;
    /** File que ce processeur remplit. */
    [[nodiscard]] Queue& send_queue_of(Processor side) noexcept;
    [[nodiscard]] const Queue& send_queue_of(Processor side) const noexcept;
    /** File que ce processeur vide. */
    [[nodiscard]] Queue& receive_queue_of(Processor side) noexcept;
    [[nodiscard]] const Queue& receive_queue_of(Processor side) const noexcept;

    [[nodiscard]] static Processor other(Processor side) noexcept {
        return side == Processor::main ? Processor::secondary : Processor::main;
    }

    InterruptController& main_controller_;
    InterruptController& secondary_controller_;

    Side main_{};
    Side secondary_{};

    /** Remplie par le processeur principal, vidée par le secondaire. */
    Queue to_secondary_{};
    /** Remplie par le processeur secondaire, vidée par le principal. */
    Queue to_main_{};
};

} // namespace ravenemu::nds
