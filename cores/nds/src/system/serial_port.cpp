#include "system/serial_port.hpp"

#include "system/registers.hpp"

namespace ravenemu::nds {

void PowerManagement::reset() noexcept {
    registers_.fill(0);
    registers_[register_battery] = healthy_battery;
    index_ = 0;
    reading_ = false;
    has_index_ = false;
    powered_off_ = false;
    unknown_ = 0;
}

std::uint8_t PowerManagement::exchange(std::uint8_t byte) noexcept {
    if (!has_index_) {
        index_ = static_cast<std::uint8_t>(byte & index_mask);
        reading_ = (byte & read_flag) != 0U;
        has_index_ = true;
        // Le premier octet ne fait que désigner : la puce n'a pas encore
        // entendu la demande au moment où elle renvoie celui-là.
        return 0;
    }

    if (index_ >= register_count) {
        // La puce n'a que quatre registres. Un cinquième n'existe pas, et lui
        // inventer une valeur ferait croire à un programme qu'il l'a trouvé.
        ++unknown_;
        return 0;
    }

    if (reading_) return registers_[index_];

    if (index_ == register_battery) {
        // La batterie se mesure, elle ne se pose pas : l'écriture est ignorée,
        // et ce silence est le comportement du matériel plutôt qu'un manque.
        return 0;
    }

    registers_[index_] = byte;
    // L'extinction est retenue, non exécutée : ce n'est pas à cet organe de
    // décider de la fin d'une partie.
    if (index_ == register_control && (byte & power_off) != 0U) powered_off_ = true;
    return 0;
}

void FirmwareFlash::reset() noexcept {
    deselect();
    // Le registre d'état survit à une désélection sur la puce — c'est ce qui
    // permet d'armer l'écriture puis de l'employer — mais pas à une remise sous
    // tension.
    status_ = 0;
    unsupported_ = 0;
    first_unsupported_ = 0;
}

void FirmwareFlash::deselect() noexcept {
    // L'adresse de lecture n'est pas remise à zéro ici, et ce n'est pas un
    // oubli : toute commande qui s'en sert l'établit elle-même avant de la
    // lire, si bien qu'une valeur restée là ne peut jamais être employée. La
    // remettre à zéro serait du code qu'aucune vérification ne pourrait
    // départager d'une absence.
    has_command_ = false;
    command_ = 0;
}

std::uint8_t FirmwareFlash::begin_command(std::uint8_t command) noexcept {
    switch (command) {
    case command_read:
        address_ = 0;
        address_remaining_ = address_bytes;
        break;
    case command_read_status:
        break;
    case command_write_enable:
        status_ = static_cast<std::uint8_t>(status_ | status_write_enabled);
        break;
    case command_write_disable:
        status_ = static_cast<std::uint8_t>(status_ & ~status_write_enabled);
        break;
    default:
        if (unsupported_ == 0U) first_unsupported_ = command;
        ++unsupported_;
        break;
    }
    return 0;
}

std::uint8_t FirmwareFlash::exchange(std::uint8_t byte) noexcept {
    if (!has_command_) {
        has_command_ = true;
        command_ = byte;
        return begin_command(byte);
    }

    switch (command_) {
    case command_read:
        if (address_remaining_ != 0U) {
            // L'adresse arrive poids fort d'abord : c'est l'ordre du bus, et non
            // celui de la mémoire du processeur.
            address_ = (address_ << 8U) | byte;
            --address_remaining_;
            return 0;
        }
        // La lecture avance toute seule : une seule adresse suffit à lire une
        // suite aussi longue qu'on veut, et c'est ainsi qu'un bloc se lit.
        return content_.byte_at(address_++);
    case command_read_status:
        return status_;
    default:
        return 0;
    }
}

void Touchscreen::reset() noexcept {
    pending_ = 0;
    remaining_ = 0;
    unknown_ = 0;
}

std::uint16_t Touchscreen::convert(std::uint8_t channel) noexcept {
    // Les deux axes se lisent sans condition : un stylet levé n'a **pas** de
    // coordonnées, l'état partagé les effaçant en même temps qu'il lève le
    // contact. Les reconditionner ici ferait deux gardes pour une seule règle,
    // dont l'une ne pourrait jamais être prise en défaut.
    //
    // Les deux canaux de pression, eux, rendent une valeur qui ne vient pas de
    // l'état : c'est le contact qui décide s'ils ont quelque chose à mesurer.
    switch (channel) {
    case channel_x: return measure(input_.touch_x());
    case channel_y: return measure(input_.touch_y());
    case channel_first_pressure: return input_.touching() ? first_pressure_value : 0;
    case channel_second_pressure: return input_.touching() ? second_pressure_value : 0;
    default:
        // Température, tension de la batterie, microphone : le convertisseur en
        // porte d'autres, dont rien ici n'a la mesure.
        ++unknown_;
        return 0;
    }
}

std::uint8_t Touchscreen::exchange(std::uint8_t byte) noexcept {
    if ((byte & start_flag) != 0U) {
        const auto channel = static_cast<std::uint8_t>((byte >> channel_shift) & channel_mask);
        // Aucun masque : les sources de mesure tiennent sur douze bits par
        // construction, ce qu'une assertion de compilation garantit. Un masque
        // ici serait un filet que rien ne pourrait faire jouer.
        pending_ = static_cast<std::uint16_t>(convert(channel) << presentation_shift);
        remaining_ = 2;
        return 0;
    }

    if (remaining_ == 0U) return 0;
    // Poids fort d'abord. Les douze bits ont été décalés de trois à la
    // conversion, si bien que l'octet haut en porte sept et l'octet bas cinq,
    // calés à gauche.
    --remaining_;
    // La valeur passe en non signé avant d'être décalée : sur seize bits elle
    // serait promue en entier signé, et le compilateur signale à raison qu'un
    // masque non signé posé dessus change de signe. Le résultat est le même,
    // l'avertissement disparaît, et il n'y a plus rien à ignorer.
    const auto value = static_cast<std::uint32_t>(pending_);
    return static_cast<std::uint8_t>((value >> (remaining_ * 8U)) & 0xffU);
}

SerialPort::SerialPort(InterruptController& interrupts, const InputState& input) noexcept
    : interrupts_(interrupts), touchscreen_(input) {}

void SerialPort::reset() noexcept {
    control_ = 0;
    data_ = 0;
    unsupported_ = 0;
    power_.reset();
    firmware_.reset();
    touchscreen_.reset();
}

void SerialPort::deselect_all() noexcept {
    power_.deselect();
    firmware_.deselect();
    touchscreen_.deselect();
}

void SerialPort::set_control(std::uint16_t value) noexcept {
    const auto previous = control_;
    // Le bit d'occupation appartient au matériel : laisser un programme le poser
    // lui donnerait le pouvoir de se mentir sur l'état du bus.
    control_ = static_cast<std::uint16_t>(value & writable_control);

    // Changer de puce, ou couper le bus, met fin à toutes les commandes en
    // cours. Une puce qu'on cesse d'écouter au milieu d'une suite doit oublier,
    // sinon elle répondrait à la commande suivante ce qu'elle devait à la
    // précédente.
    constexpr auto device_field = static_cast<std::uint16_t>(device_mask << device_shift);
    const bool device_changed = ((previous ^ control_) & device_field) != 0U;
    if (device_changed || (control_ & bus_enable) == 0U) deselect_all();
}

void SerialPort::write_data(std::uint8_t byte) noexcept {
    // Bus éteint : rien ne sort, rien n'entre, et le registre garde ce qu'il
    // avait. C'est l'état dans lequel un programme laisse le port entre deux
    // séries d'échanges.
    if ((control_ & bus_enable) == 0U) return;

    // Un échange de seize bits se comporte particulièrement sur console, et rien
    // ici ne le reproduit : il est compté, et servi comme un échange ordinaire.
    if ((control_ & wide_transfer) != 0U) ++unsupported_;

    switch (device()) {
    case Device::power:
        data_ = power_.exchange(byte);
        break;
    case Device::firmware:
        data_ = firmware_.exchange(byte);
        break;
    case Device::touchscreen:
        data_ = touchscreen_.exchange(byte);
        break;
    case Device::reserved:
        // Rien ne pend à cette place : personne ne tire la ligne, et l'échange
        // est compté plutôt que servi par une valeur inventée.
        ++unsupported_;
        data_ = 0;
        break;
    }

    // La sélection tombe après l'échange quand le programme ne la retient pas :
    // c'est ce qui referme une commande de plusieurs octets.
    if ((control_ & hold_selection) == 0U) deselect_all();

    if ((control_ & interrupt_enable) != 0U) {
        interrupts_.request(InterruptController::serial);
    }
}

bool SerialPort::covers(std::uint32_t address, std::uint32_t width) noexcept {
    for (std::uint32_t index = 0; index < width; ++index) {
        const auto byte_address = address + index;
        if (byte_address < control_address) return false;
        if (byte_address >= data_address + 2U) return false;
    }
    return width != 0U;
}

std::uint8_t SerialPort::register_byte(std::uint32_t address) const noexcept {
    if (address < data_address) {
        return registers::byte_of(control_, address - control_address);
    }
    // Le registre de données n'a qu'un octet utile ; le matériel lui en réserve
    // deux, et la moitié haute ne porte rien.
    return address == data_address ? data_ : 0U;
}

void SerialPort::write_register_byte(std::uint32_t address, std::uint8_t byte) noexcept {
    if (address < data_address) {
        set_control(static_cast<std::uint16_t>(
            registers::with_byte(control_, address - control_address, byte)));
        return;
    }
    if (address == data_address) {
        write_data(byte);
        return;
    }
    // Moitié haute du registre de données : réservée, et l'ignorer est le
    // comportement du matériel.
    static_cast<void>(byte);
}

bool SerialPort::read_register(
    std::uint32_t address,
    std::uint32_t width,
    std::uint32_t& value
) const noexcept {
    if (!covers(address, width)) return false;

    value = 0;
    for (std::uint32_t index = 0; index < width; ++index) {
        value |= static_cast<std::uint32_t>(register_byte(address + index)) << (index * 8U);
    }
    return true;
}

bool SerialPort::write_register(
    std::uint32_t address,
    std::uint32_t width,
    std::uint32_t value
) noexcept {
    if (!covers(address, width)) return false;

    for (std::uint32_t index = 0; index < width; ++index) {
        write_register_byte(
            address + index, static_cast<std::uint8_t>((value >> (index * 8U)) & 0xffU));
    }
    return true;
}

} // namespace ravenemu::nds
