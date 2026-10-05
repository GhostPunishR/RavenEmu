#include "system/inter_processor.hpp"

namespace ravenemu::nds {

void InterruptController::reset() noexcept {
    master_enable_ = 0;
    enabled_ = 0;
    requested_ = 0;
}

void InterProcessor::Queue::push(std::uint32_t value) noexcept {
    words_[(head_ + count_) % queue_depth] = value;
    ++count_;
}

std::uint32_t InterProcessor::Queue::pop() noexcept {
    const auto value = words_[head_];
    head_ = (head_ + 1U) % queue_depth;
    --count_;
    return value;
}

InterProcessor::InterProcessor(InterruptController& main, InterruptController& secondary) noexcept
    : main_controller_(main), secondary_controller_(secondary) {}

void InterProcessor::reset() noexcept {
    main_ = Side{};
    secondary_ = Side{};
    to_secondary_.clear();
    to_main_.clear();
}

InterProcessor::Side& InterProcessor::side_of(Processor side) noexcept {
    return side == Processor::main ? main_ : secondary_;
}

const InterProcessor::Side& InterProcessor::side_of(Processor side) const noexcept {
    return side == Processor::main ? main_ : secondary_;
}

InterruptController& InterProcessor::controller_of(Processor side) noexcept {
    return side == Processor::main ? main_controller_ : secondary_controller_;
}

InterProcessor::Queue& InterProcessor::send_queue_of(Processor side) noexcept {
    return side == Processor::main ? to_secondary_ : to_main_;
}

const InterProcessor::Queue& InterProcessor::send_queue_of(Processor side) const noexcept {
    return side == Processor::main ? to_secondary_ : to_main_;
}

InterProcessor::Queue& InterProcessor::receive_queue_of(Processor side) noexcept {
    return side == Processor::main ? to_main_ : to_secondary_;
}

const InterProcessor::Queue& InterProcessor::receive_queue_of(Processor side) const noexcept {
    return side == Processor::main ? to_main_ : to_secondary_;
}

std::uint16_t InterProcessor::read_sync(Processor side) const noexcept {
    const auto& own = side_of(side);
    const auto& peer = side_of(other(side));
    // Ce que l'autre a écrit arrive dans les quatre bits bas ; ce qu'on a écrit
    // soi-même se relit à sa place. Le registre est ainsi le même des deux
    // côtés, à l'échange des deux champs près.
    std::uint16_t value = static_cast<std::uint16_t>(peer.sync_output >> 8U);
    value = static_cast<std::uint16_t>(value | own.sync_output);
    if (own.accepts_sync_interrupt) value = static_cast<std::uint16_t>(value | sync_accept_interrupt);
    return value;
}

void InterProcessor::write_sync(Processor side, std::uint16_t value) noexcept {
    auto& own = side_of(side);
    own.sync_output = static_cast<std::uint16_t>(value & sync_output_mask);
    own.accepts_sync_interrupt = (value & sync_accept_interrupt) != 0U;

    // Le bit d'appel ne se retient pas : il déclenche et disparaît. Et il ne
    // réveille que si le destinataire a dit accepter d'être réveillé.
    if ((value & sync_send_interrupt) != 0U && side_of(other(side)).accepts_sync_interrupt) {
        controller_of(other(side)).request(InterruptController::ipc_sync);
    }
}

std::uint16_t InterProcessor::read_control(Processor side) const noexcept {
    const auto& own = side_of(side);
    const auto& sending = send_queue_of(side);
    const auto& receiving = receive_queue_of(side);

    std::uint16_t value = 0;
    if (sending.empty()) value = static_cast<std::uint16_t>(value | send_queue_empty);
    if (sending.full()) value = static_cast<std::uint16_t>(value | send_queue_full);
    if (own.sends_empty_interrupt) value = static_cast<std::uint16_t>(value | send_empty_interrupt);
    if (receiving.empty()) value = static_cast<std::uint16_t>(value | receive_queue_empty);
    if (receiving.full()) value = static_cast<std::uint16_t>(value | receive_queue_full);
    if (own.receives_filled_interrupt) value = static_cast<std::uint16_t>(value | receive_filled_interrupt);
    if (own.error) value = static_cast<std::uint16_t>(value | queue_error);
    if (own.queues_enabled) value = static_cast<std::uint16_t>(value | queues_enabled);
    return value;
}

void InterProcessor::write_control(Processor side, std::uint16_t value) noexcept {
    auto& own = side_of(side);
    own.sends_empty_interrupt = (value & send_empty_interrupt) != 0U;
    own.receives_filled_interrupt = (value & receive_filled_interrupt) != 0U;
    own.queues_enabled = (value & queues_enabled) != 0U;

    // L'erreur s'acquitte comme une demande d'interruption : en écrivant un.
    if ((value & queue_error) != 0U) own.error = false;

    if ((value & send_queue_clear) != 0U) {
        auto& sending = send_queue_of(side);
        const bool was_filled = !sending.empty();
        sending.clear();
        // Vider sa file d'envoi la rend vide : si on a demandé à être prévenu
        // de cela, la demande est due, exactement comme si le destinataire
        // l'avait consommée.
        if (was_filled && own.sends_empty_interrupt) {
            controller_of(side).request(InterruptController::ipc_send_queue_empty);
        }
    }
}

void InterProcessor::send(Processor side, std::uint32_t value) noexcept {
    auto& own = side_of(side);
    // Files éteintes : le mot est perdu sans erreur. C'est l'état d'un logiciel
    // qui n'a pas encore ouvert le canal, pas une faute.
    if (!own.queues_enabled) return;

    auto& sending = send_queue_of(side);
    if (sending.full()) {
        own.error = true;
        return;
    }

    const bool was_empty = sending.empty();
    sending.push(value);

    // Une file qui se remplit réveille celui qui reçoit, et seulement au
    // passage du vide au plein : c'est un front, pas un niveau.
    auto& peer = side_of(other(side));
    if (was_empty && peer.receives_filled_interrupt) {
        controller_of(other(side)).request(InterruptController::ipc_receive_queue_filled);
    }
}

std::uint32_t InterProcessor::receive(Processor side) noexcept {
    auto& own = side_of(side);
    auto& receiving = receive_queue_of(side);

    // Files éteintes : la dernière valeur lue est rendue de nouveau, sans que
    // la file avance.
    if (!own.queues_enabled) return own.last_received;

    if (receiving.empty()) {
        own.error = true;
        return own.last_received;
    }

    own.last_received = receiving.pop();

    // Une file qui se vide réveille celui qui envoie, et non celui qui vient de
    // lire : c'est lui qui attend de pouvoir en déposer d'autres.
    auto& peer = side_of(other(side));
    if (receiving.empty() && peer.sends_empty_interrupt) {
        controller_of(other(side)).request(InterruptController::ipc_send_queue_empty);
    }
    return own.last_received;
}

} // namespace ravenemu::nds
