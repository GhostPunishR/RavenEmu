#include "system/firmware.hpp"
#include "system/machine.hpp"
#include "system/serial_port.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <initializer_list>
#include <span>
#include <string_view>
#include <vector>

/**
 * Le port série du processeur secondaire.
 *
 * Trois puces très différentes pendent au même fil : l'alimentation, la mémoire
 * de réglages, et le convertisseur de l'écran tactile. C'est le dernier obstacle
 * qu'un jeu du commerce rencontre avant d'afficher quoi que ce soit, et il ne
 * s'en plaint pas : il attend une réponse.
 *
 * Quatre choses sont éprouvées ici. Le **protocole du bus**, dont l'avance d'un
 * octet et le maintien de la sélection décident si une commande de plusieurs
 * octets aboutit. Le **contenu des réglages**, que RavenEmu écrit lui-même et
 * dont la somme de contrôle doit tenir. La **fermeture de l'étalonnage** : la
 * mesure que le convertisseur rend, passée dans la formule d'un jeu avec les
 * valeurs lues dans les réglages, doit retomber sur le pixel touché. Et le
 * **chemin complet**, un programme du processeur secondaire interrogeant la
 * dalle par les registres.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

constexpr std::size_t rom_bytes = 0x8000;
constexpr std::uint32_t arm9_rom_offset = 0x4000;
constexpr std::uint32_t arm7_rom_offset = 0x6000;
constexpr std::uint32_t block_bytes = 0x400;

constexpr std::uint32_t main_ram_base = 0x0200'0000;
constexpr std::uint32_t private_wram_base = 0x0380'0000;

constexpr std::uint32_t always = 0xeU;

constexpr std::uint32_t mov_immediate(
    std::uint32_t rd,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

/** `STR`/`LDR` d'un mot, adressage immédiat positif. */
constexpr std::uint32_t transfer(
    bool load,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset = 0U
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | offset;
}

/** Le même transfert, mais d'un seul octet : le port de données n'en veut qu'un. */
constexpr std::uint32_t transfer_byte(
    bool load,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset
) noexcept {
    return transfer(load, rn, rd, offset) | (1U << 22U);
}

constexpr std::uint32_t branch(std::int32_t words, std::uint32_t condition = always) noexcept {
    return (condition << 28U) | (0x5U << 25U) |
        (static_cast<std::uint32_t>(words - 2) & 0x00ff'ffffU);
}

/** Adresse d'un registre du port, relative à la base des entrées-sorties. */
constexpr std::uint32_t io_base = 0x0400'0000;
constexpr std::uint32_t control_offset = SerialPort::control_address - io_base;
constexpr std::uint32_t data_offset = SerialPort::data_address - io_base;

// --------------------------------------------------------------------------

/** Le registre de commande qui désigne une puce et retient ou non sa sélection. */
constexpr std::uint16_t control_for(SerialPort::Device device, bool hold) noexcept {
    return static_cast<std::uint16_t>(
        SerialPort::bus_enable |
        static_cast<std::uint16_t>(static_cast<std::uint16_t>(device) << SerialPort::device_shift) |
        (hold ? SerialPort::hold_selection : std::uint16_t{0})
    );
}

/** Un échange : un octet sort, un octet entre, et c'est celui-là qu'on lit. */
std::uint8_t exchange(SerialPort& port, std::uint8_t byte) {
    port.write_data(byte);
    return port.data();
}

/** Lit [count] octets de la mémoire de réglages à partir de [address]. */
std::vector<std::uint8_t> read_firmware(
    SerialPort& port,
    std::uint32_t address,
    std::size_t count
) {
    port.set_control(control_for(SerialPort::Device::firmware, true));
    static_cast<void>(exchange(port, FirmwareFlash::command_read));
    static_cast<void>(exchange(port, static_cast<std::uint8_t>(address >> 16U)));
    static_cast<void>(exchange(port, static_cast<std::uint8_t>(address >> 8U)));
    static_cast<void>(exchange(port, static_cast<std::uint8_t>(address)));

    std::vector<std::uint8_t> bytes;
    bytes.reserve(count);
    for (std::size_t index = 0; index < count; ++index) {
        bytes.push_back(exchange(port, 0));
    }
    // Couper le bus referme la commande, comme le fait un programme entre deux
    // séries d'échanges.
    port.set_control(0);
    return bytes;
}

/** Demande une conversion sur un canal, et rend les douze bits reconstitués. */
std::uint16_t convert(SerialPort& port, std::uint8_t channel) {
    port.set_control(control_for(SerialPort::Device::touchscreen, true));
    static_cast<void>(exchange(port, static_cast<std::uint8_t>(
        Touchscreen::start_flag | (channel << Touchscreen::channel_shift))));
    const auto high = exchange(port, 0);
    const auto low = exchange(port, 0);
    port.set_control(0);
    // Les douze bits sont calés à gauche sur seize : les remettre à droite est le
    // travail du lecteur, et l'oublier donnerait des coordonnées huit fois trop
    // grandes.
    return static_cast<std::uint16_t>(
        ((static_cast<std::uint32_t>(high) << 8U) | low) >> Touchscreen::presentation_shift);
}

std::uint16_t read16(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return static_cast<std::uint16_t>(bytes[offset] | (bytes[offset + 1U] << 8U));
}

/** Le port seul, sans console autour : c'est ainsi que son protocole s'éprouve. */
struct Port {
    InterruptController interrupts{};
    InputState input{};
    SerialPort port{interrupts, input};

    Port() {
        interrupts.reset();
        input.reset();
        port.reset();
    }
};

/** Fabrique d'image de cartouche, dont le bloc secondaire porte le programme. */
class Cartouche {
public:
    Cartouche() : image_(rom_bytes, 0) {
        write_text(0x000, "RAVENCARD");
        write_text(0x00c, "ARVE");
        image_[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
        write_u32(0x080, static_cast<std::uint32_t>(rom_bytes));
        write_u32(0x084, 0x0000'4000);
        set_block(true, arm9_rom_offset, main_ram_base);
        set_block(false, arm7_rom_offset, private_wram_base);
        set_code(arm9_rom_offset, {branch(0)});
        set_code(arm7_rom_offset, {branch(0)});
    }

    void set_block(bool main, std::uint32_t rom_offset, std::uint32_t address) {
        const std::size_t base = main ? 0x020U : 0x030U;
        write_u32(base + 0x0U, rom_offset);
        write_u32(base + 0x4U, address);
        write_u32(base + 0x8U, address);
        write_u32(base + 0xcU, block_bytes);
    }

    void set_code(std::uint32_t rom_offset, std::initializer_list<std::uint32_t> program) {
        auto cursor = static_cast<std::size_t>(rom_offset);
        for (const auto word : program) {
            write_u32(cursor, word);
            cursor += 4U;
        }
    }

    void write_u32(std::size_t offset, std::uint32_t value) {
        for (std::size_t byte = 0; byte < 4U; ++byte) {
            image_[offset + byte] = static_cast<std::uint8_t>((value >> (byte * 8U)) & 0xffU);
        }
    }

    void write_text(std::size_t offset, std::string_view text) {
        for (std::size_t index = 0; index < text.size(); ++index) {
            image_[offset + index] = static_cast<std::uint8_t>(text[index]);
        }
    }

    [[nodiscard]] std::span<const std::uint8_t> image() const noexcept { return image_; }

private:
    std::vector<std::uint8_t> image_;
};

/** La console entière, pour ce qui ne s'observe que par les registres. */
struct Console {
    Machine machine{};
    Cartouche cartouche{};
    std::vector<std::int32_t> framebuffer;

    Console()
        : framebuffer(
              static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height),
              0
          ) {
        boot();
    }

    void boot() {
        machine.boot(CartridgeHeader::parse(cartouche.image()), cartouche.image());
    }

    void run_frame() { machine.run_frame(framebuffer); }

    [[nodiscard]] SerialPort& port() noexcept { return machine.serial(); }
    [[nodiscard]] Bus& main() noexcept { return machine.main_memory(); }
    [[nodiscard]] Bus& secondary() noexcept { return machine.secondary_memory(); }
};

// --------------------------------------------------------------------------

/**
 * Les valeurs du matériel, écrites en toutes lettres.
 *
 * Les reprendre des constantes qui les définissent ne prouverait rien : une
 * mutation les déplacerait des deux côtés à la fois.
 */
void les_valeurs_sont_celles_du_materiel() {
    check(SerialPort::control_address == 0x0400'01c0U, "l'adresse du registre de commande");
    check(SerialPort::data_address == 0x0400'01c2U, "et celle du registre de données");
    check(SerialPort::busy == 1U << 7U, "le bit d'occupation");
    check(SerialPort::device_shift == 8U, "le champ de la puce commence au neuvième bit");
    check(SerialPort::wide_transfer == 1U << 10U, "l'échange de seize bits");
    check(SerialPort::hold_selection == 1U << 11U, "le maintien de la sélection");
    check(SerialPort::interrupt_enable == 1U << 14U, "l'autorisation d'interruption");
    check(SerialPort::bus_enable == 1U << 15U, "l'allumage du bus occupe le bit le plus haut");
    check(InterruptController::serial == 1U << 23U, "la source d'interruption du port");

    check(static_cast<int>(SerialPort::Device::power) == 0, "l'alimentation est la première puce");
    check(static_cast<int>(SerialPort::Device::firmware) == 1, "les réglages la deuxième");
    check(static_cast<int>(SerialPort::Device::touchscreen) == 2, "l'écran tactile la troisième");

    check(FirmwareFlash::command_read == 0x03U, "la commande de lecture de la flash");
    check(FirmwareFlash::command_read_status == 0x05U, "celle du registre d'état");
    check(FirmwareFlash::command_write_enable == 0x06U, "celle qui arme l'écriture");
    check(FirmwareFlash::command_write_disable == 0x04U, "et celle qui la désarme");
    check(FirmwareFlash::address_bytes == 3U, "une adresse de flash tient sur trois octets");

    check(Touchscreen::start_flag == 0x80U, "le bit qui lance une conversion");
    check(Touchscreen::channel_shift == 4U, "le canal occupe les trois bits suivants");
    check(Touchscreen::channel_x == 5U, "le canal de l'abscisse");
    check(Touchscreen::channel_y == 1U, "et celui de l'ordonnée");
    check(Touchscreen::presentation_shift == 3U, "les douze bits sont calés à gauche");

    check(Firmware::settings_pointer_address == 0x20U, "l'adresse du pointeur de réglages");
    check(Firmware::settings_pointer_unit == 8U, "qui compte par groupes de huit octets");
    check(Firmware::settings_bytes == 0x100U, "la longueur du bloc de réglages");
    check(Firmware::calibration_offset == 0x58U, "la place de l'étalonnage dans le bloc");
    check(Firmware::checksum_offset == 0x72U, "et celle de sa somme de contrôle");
}

/** Le pointeur mène au bloc, et le bloc porte une somme de contrôle qui tient. */
void les_reglages_se_lisent_et_leur_somme_tient() {
    Port fixture;

    const auto pointer_bytes = read_firmware(fixture.port, Firmware::settings_pointer_address, 2);
    const auto pointer = read16(pointer_bytes, 0);
    const std::uint32_t settings_address =
        static_cast<std::uint32_t>(pointer) * Firmware::settings_pointer_unit;
    check(
        settings_address == Firmware::first_settings_address,
        "le pointeur mène au premier exemplaire des réglages"
    );

    const auto settings = read_firmware(fixture.port, settings_address, Firmware::settings_bytes);
    check(
        settings[Firmware::version_offset] == Firmware::settings_version,
        "le bloc annonce sa version"
    );

    // La somme porte sur tout ce qui précède le compteur de mise à jour. Elle est
    // recalculée ici plutôt que reprise : c'est le seul contrôle qu'un programme
    // de console fait avant de croire ce bloc.
    std::uint16_t crc = 0xffffU;
    for (std::uint32_t index = 0; index < Firmware::update_count_offset; ++index) {
        crc = static_cast<std::uint16_t>(crc ^ settings[index]);
        for (int bit = 0; bit < 8; ++bit) {
            const bool carry = (crc & 1U) != 0U;
            crc = static_cast<std::uint16_t>(crc >> 1U);
            if (carry) crc = static_cast<std::uint16_t>(crc ^ 0xa001U);
        }
    }
    check(
        crc == read16(settings, Firmware::checksum_offset),
        "et la somme de contrôle inscrite est celle du contenu"
    );

    // Un nom vide ferait afficher une ligne blanche à un jeu qui le montre.
    check(read16(settings, Firmware::nickname_length_offset) != 0U, "un nom est inscrit");
    check(
        read16(settings, Firmware::nickname_offset) == static_cast<std::uint16_t>('R'),
        "et il commence par la lettre attendue"
    );
}

/** Les deux exemplaires portent le même contenu : le choix n'a pas d'importance. */
void les_deux_exemplaires_sont_identiques() {
    Port fixture;
    const auto first = read_firmware(
        fixture.port, Firmware::first_settings_address, Firmware::settings_bytes);
    const auto second = read_firmware(
        fixture.port, Firmware::second_settings_address, Firmware::settings_bytes);
    check(first == second, "les deux exemplaires des réglages se valent");
}

/**
 * L'étalonnage inscrit et le convertisseur se referment l'un sur l'autre.
 *
 * C'est la vérification qui compte le plus de tout ce fichier. Un jeu ne reçoit
 * pas des pixels mais des mesures, et il les traduit avec les deux points
 * enregistrés dans les réglages. Si les deux moitiés ne sont pas construites
 * l'une pour l'autre, le jeu répond à côté du doigt sans que rien ne le signale.
 */
void l_etalonnage_ramene_la_mesure_au_pixel() {
    Port fixture;
    const auto settings = read_firmware(
        fixture.port, Firmware::first_settings_address, Firmware::settings_bytes);

    const auto adc_x1 = read16(settings, Firmware::calibration_offset);
    const auto adc_y1 = read16(settings, Firmware::calibration_offset + 2U);
    const auto screen_x1 = settings[Firmware::calibration_offset + 4U];
    const auto screen_y1 = settings[Firmware::calibration_offset + 5U];
    const auto adc_x2 = read16(settings, Firmware::calibration_offset + 6U);
    const auto adc_y2 = read16(settings, Firmware::calibration_offset + 8U);
    const auto screen_x2 = settings[Firmware::calibration_offset + 10U];
    const auto screen_y2 = settings[Firmware::calibration_offset + 11U];

    check(adc_x2 > adc_x1 && adc_y2 > adc_y1, "les deux points d'étalonnage sont distincts");

    // La formule d'un jeu, telle qu'elle s'écrit : une interpolation entre les
    // deux points enregistrés.
    const auto to_pixel = [](
        std::uint16_t raw,
        std::uint16_t low_adc,
        std::uint8_t low_screen,
        std::uint16_t high_adc,
        std::uint8_t high_screen
    ) {
        const auto span_screen = static_cast<int>(high_screen) - static_cast<int>(low_screen);
        const auto span_adc = static_cast<int>(high_adc) - static_cast<int>(low_adc);
        return (static_cast<int>(raw) - static_cast<int>(low_adc)) * span_screen / span_adc +
            static_cast<int>(low_screen);
    };

    for (const std::uint8_t x : {std::uint8_t{0}, std::uint8_t{1}, std::uint8_t{77},
                                 std::uint8_t{128}, std::uint8_t{255}}) {
        for (const std::uint8_t y : {std::uint8_t{0}, std::uint8_t{5}, std::uint8_t{96},
                                     std::uint8_t{191}}) {
            fixture.input.set_touch(true, x, y);
            const auto raw_x = convert(fixture.port, Touchscreen::channel_x);
            const auto raw_y = convert(fixture.port, Touchscreen::channel_y);
            check(
                to_pixel(raw_x, adc_x1, screen_x1, adc_x2, screen_x2) == static_cast<int>(x),
                "la mesure de l'abscisse retombe sur le pixel touché"
            );
            check(
                to_pixel(raw_y, adc_y1, screen_y1, adc_y2, screen_y2) == static_cast<int>(y),
                "et celle de l'ordonnée aussi"
            );
        }
    }
}

/** Stylet levé : le convertisseur n'a aucune tension à mesurer. */
void sans_contact_le_convertisseur_ne_rend_rien() {
    Port fixture;
    fixture.input.set_touch(true, 100, 50);
    check(convert(fixture.port, Touchscreen::channel_x) != 0U, "sous contact, une mesure vient");
    check(
        convert(fixture.port, Touchscreen::channel_first_pressure) != 0U,
        "et la pression aussi"
    );

    fixture.input.set_touch(false, 100, 50);
    check(convert(fixture.port, Touchscreen::channel_x) == 0U, "levé, l'abscisse tombe à zéro");
    check(convert(fixture.port, Touchscreen::channel_y) == 0U, "l'ordonnée de même");
    check(
        convert(fixture.port, Touchscreen::channel_first_pressure) == 0U,
        "et la pression avec elles"
    );
    check(!fixture.input.touching(), "le contact est bien levé");
    check(fixture.input.touch_x() == 0U, "et les coordonnées ne survivent pas au stylet");
}

/** Le convertisseur porte d'autres canaux, dont rien ici n'a la mesure. */
void un_canal_non_servi_est_compte() {
    Port fixture;
    fixture.input.set_touch(true, 10, 10);
    constexpr std::uint8_t temperature_channel = 0;
    check(
        convert(fixture.port, temperature_channel) == 0U,
        "un canal non servi ne rend rien"
    );
    check(
        fixture.port.touchscreen().unknown_channel_count() == 1U,
        "et il est compté plutôt qu'inventé"
    );
}

/**
 * L'octet de commande ne rend rien : la puce n'a pas encore entendu la demande.
 *
 * C'est l'avance d'un octet propre à un bus série, et un lecteur qui la
 * confondrait prendrait la moitié haute d'une mesure pour la mesure entière.
 */
void l_octet_de_commande_ne_rend_rien() {
    Port fixture;
    fixture.input.set_touch(true, 0x64, 0x30);
    fixture.port.set_control(control_for(SerialPort::Device::touchscreen, true));

    const auto answer_to_command = exchange(
        fixture.port,
        static_cast<std::uint8_t>(
            Touchscreen::start_flag | (Touchscreen::channel_x << Touchscreen::channel_shift))
    );
    check(answer_to_command == 0U, "l'octet de commande ne rapporte rien");

    const auto high = exchange(fixture.port, 0);
    const auto low = exchange(fixture.port, 0);
    check(high == 0x32U, "le deuxième octet porte les sept bits hauts");
    check(low == 0x00U, "et le troisième les cinq bas, calés à gauche");

    // La conversion est épuisée : un quatrième octet ne rend plus rien.
    check(exchange(fixture.port, 0) == 0U, "une conversion ne se lit qu'une fois");
}

/**
 * Sans maintien de la sélection, chaque octet repart d'une commande neuve.
 *
 * C'est ce bit qui rend possibles les commandes de plusieurs octets, et
 * l'ignorer servirait la première commande de chaque suite et rien d'autre.
 */
void le_maintien_de_la_selection_porte_la_commande() {
    Port fixture;

    // Sans maintien : les trois octets d'adresse sont lus comme trois commandes.
    fixture.port.set_control(control_for(SerialPort::Device::firmware, false));
    static_cast<void>(exchange(fixture.port, FirmwareFlash::command_read));
    static_cast<void>(exchange(fixture.port, 0));
    static_cast<void>(exchange(fixture.port, 0));
    static_cast<void>(exchange(fixture.port, Firmware::settings_pointer_address));
    check(
        fixture.port.firmware().unsupported_count() != 0U,
        "sans maintien, les octets d'adresse passent pour des commandes"
    );

    // Avec maintien : la même suite rend le contenu attendu.
    Port held;
    const auto bytes = read_firmware(held.port, Firmware::settings_pointer_address, 2);
    check(
        held.port.firmware().unsupported_count() == 0U,
        "avec maintien, la commande tient sur toute sa longueur"
    );
    check(read16(bytes, 0) != 0U, "et la lecture rapporte le pointeur");
}

/** Une lecture avance toute seule : une adresse suffit à lire une suite. */
void la_lecture_avance_toute_seule() {
    Port fixture;
    const auto bytes = read_firmware(fixture.port, Firmware::first_settings_address, 4);
    const Firmware reference{};
    const auto& expected = reference.settings();
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        check(bytes[index] == expected[index], "les octets se suivent dans l'ordre de la flash");
    }
}

/** Là où rien n'est défini, la flash se lit comme une flash effacée. */
void la_flash_se_lit_effacee_hors_du_contenu() {
    Port fixture;
    const auto bytes = read_firmware(fixture.port, 0x0001'0000, 4);
    for (const auto byte : bytes) {
        check(byte == Firmware::erased_byte, "hors du contenu, la flash rend des octets à un");
    }
}

/** Le registre d'état se lit, et l'armement de l'écriture s'y voit. */
void l_armement_de_l_ecriture_se_lit_dans_l_etat() {
    Port fixture;
    const auto status_of = [&fixture]() {
        fixture.port.set_control(control_for(SerialPort::Device::firmware, true));
        static_cast<void>(exchange(fixture.port, FirmwareFlash::command_read_status));
        const auto value = exchange(fixture.port, 0);
        fixture.port.set_control(0);
        return value;
    };

    check((status_of() & FirmwareFlash::status_busy) == 0U, "rien ne s'écrit jamais");
    check(
        (status_of() & FirmwareFlash::status_write_enabled) == 0U,
        "et l'écriture n'est pas armée au départ"
    );

    fixture.port.set_control(control_for(SerialPort::Device::firmware, false));
    static_cast<void>(exchange(fixture.port, FirmwareFlash::command_write_enable));
    check(
        (status_of() & FirmwareFlash::status_write_enabled) != 0U,
        "la commande d'armement se voit dans l'état"
    );

    fixture.port.set_control(control_for(SerialPort::Device::firmware, false));
    static_cast<void>(exchange(fixture.port, FirmwareFlash::command_write_disable));
    check(
        (status_of() & FirmwareFlash::status_write_enabled) == 0U,
        "et la commande inverse l'y retire"
    );
}

/** Une commande que la puce ne sert pas est comptée, non devinée. */
void une_commande_de_flash_non_servie_est_comptee() {
    Port fixture;
    constexpr std::uint8_t page_write = 0x0a;
    fixture.port.set_control(control_for(SerialPort::Device::firmware, true));
    check(exchange(fixture.port, page_write) == 0U, "la commande ne rend rien");
    check(exchange(fixture.port, 0) == 0U, "et la suite non plus");
    check(fixture.port.firmware().unsupported_count() == 1U, "elle est comptée");
    check(
        fixture.port.firmware().first_unsupported() == page_write,
        "et la première rencontrée est retenue"
    );
}

/** L'alimentation retient ce qu'on lui règle, et le rend quand on le demande. */
void l_alimentation_retient_ses_reglages() {
    Port fixture;
    constexpr std::uint8_t lights =
        PowerManagement::sound_amplifier | PowerManagement::lower_backlight |
        PowerManagement::upper_backlight;

    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_control));
    static_cast<void>(exchange(fixture.port, lights));
    fixture.port.set_control(0);

    check(fixture.port.power().control() == lights, "le registre garde ce qu'on y écrit");

    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(
        fixture.port,
        static_cast<std::uint8_t>(PowerManagement::read_flag | PowerManagement::register_control)));
    check(exchange(fixture.port, 0) == lights, "et il se relit tel quel");
    fixture.port.set_control(0);

    check(!fixture.port.power().powered_off(), "rien n'a demandé l'extinction");
}

/** La batterie se mesure et n'annonce pas une charge faible sans raison. */
void la_batterie_ne_s_annonce_pas_faible() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(
        fixture.port,
        static_cast<std::uint8_t>(PowerManagement::read_flag | PowerManagement::register_battery)));
    const auto battery = exchange(fixture.port, 0);
    fixture.port.set_control(0);
    check(
        (battery & PowerManagement::low_battery) == 0U,
        "la batterie ne s'annonce pas faible"
    );

    // Elle ne s'écrit pas non plus : c'est une mesure, pas un réglage.
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_battery));
    static_cast<void>(exchange(fixture.port, PowerManagement::low_battery));
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(
        fixture.port,
        static_cast<std::uint8_t>(PowerManagement::read_flag | PowerManagement::register_battery)));
    check(
        (exchange(fixture.port, 0) & PowerManagement::low_battery) == 0U,
        "et l'écrire ne la change pas"
    );
}

/** L'extinction demandée est retenue, non exécutée. */
void l_extinction_demandee_se_releve() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_control));
    static_cast<void>(exchange(fixture.port, PowerManagement::power_off));
    fixture.port.set_control(0);
    check(fixture.port.power().powered_off(), "la demande d'extinction se relève");
}

/** La puce n'a que quatre registres, et un cinquième n'existe pas. */
void un_registre_d_alimentation_inconnu_est_compte() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_count));
    check(exchange(fixture.port, 0) == 0U, "un registre absent ne rend rien");
    check(
        fixture.port.power().unknown_register_count() == 1U,
        "et il est compté plutôt qu'inventé"
    );
}

/** Changer de puce met fin à la commande en cours partout. */
void changer_de_puce_referme_la_commande() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::firmware, true));
    static_cast<void>(exchange(fixture.port, FirmwareFlash::command_read));

    // Le programme se tourne vers une autre puce au milieu de la commande.
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    fixture.port.set_control(control_for(SerialPort::Device::firmware, true));

    // La flash a oublié : l'octet suivant est lu comme une commande neuve.
    static_cast<void>(exchange(fixture.port, FirmwareFlash::command_read_status));
    check(
        exchange(fixture.port, 0) == fixture.port.firmware().status(),
        "la puce repart d'une commande neuve"
    );
    check(fixture.port.firmware().unsupported_count() == 0U, "sans rien prendre pour une commande");
}

/** Bus éteint : rien ne sort et rien n'entre. */
void le_bus_eteint_n_echange_rien() {
    Port fixture;
    fixture.input.set_touch(true, 0x64, 0x30);

    // Un échange complet, pour que le registre porte autre chose que zéro.
    fixture.port.set_control(control_for(SerialPort::Device::touchscreen, true));
    static_cast<void>(exchange(fixture.port, static_cast<std::uint8_t>(
        Touchscreen::start_flag | (Touchscreen::channel_x << Touchscreen::channel_shift))));
    const auto answer = exchange(fixture.port, 0);
    check(answer != 0U, "le port a bien reçu quelque chose");

    fixture.port.set_control(0);
    fixture.port.write_data(0xff);
    check(fixture.port.data() == answer, "bus éteint, le registre garde ce qu'il avait");
}

/** Rien ne pend à la quatrième place, et l'échange y est compté. */
void une_place_vide_est_comptee() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::reserved, true));
    check(exchange(fixture.port, 0xa5) == 0U, "personne ne tire la ligne");
    check(fixture.port.unsupported_count() == 1U, "et l'échange est compté");
}

/** Un échange de seize bits n'est pas reproduit, et il est compté. */
void un_echange_large_est_compte() {
    Port fixture;
    fixture.port.set_control(
        static_cast<std::uint16_t>(
            control_for(SerialPort::Device::power, true) | SerialPort::wide_transfer));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_control));
    check(fixture.port.unsupported_count() == 1U, "l'échange large est compté");
}

/** La fin d'un échange peut poser une interruption, quand elle est autorisée. */
void la_fin_d_un_echange_peut_interrompre() {
    Port fixture;
    fixture.port.set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(fixture.port, PowerManagement::register_control));
    check(
        (fixture.interrupts.requested() & InterruptController::serial) == 0U,
        "sans autorisation, aucune demande"
    );

    fixture.port.set_control(static_cast<std::uint16_t>(
        control_for(SerialPort::Device::power, true) | SerialPort::interrupt_enable));
    static_cast<void>(exchange(fixture.port, 0));
    check(
        (fixture.interrupts.requested() & InterruptController::serial) != 0U,
        "avec elle, la fin de l'échange en pose une"
    );
}

/** Le bit d'occupation appartient au matériel et ne s'écrit pas. */
void le_bit_d_occupation_ne_s_ecrit_pas() {
    Port fixture;
    fixture.port.set_control(static_cast<std::uint16_t>(
        control_for(SerialPort::Device::power, true) | SerialPort::busy));
    check((fixture.port.control() & SerialPort::busy) == 0U, "l'occupation ne s'écrit pas");
    check(
        fixture.port.device() == SerialPort::Device::power,
        "et le reste du registre est bien pris"
    );
}

/** Les registres du port sont décodés par la carte du processeur secondaire. */
void les_registres_du_port_sont_decodes() {
    Console console;
    const auto before = console.machine.secondary_memory().unimplemented_io_count();

    console.secondary().write16(SerialPort::control_address, control_for(
        SerialPort::Device::touchscreen, true));
    check(
        console.secondary().read16(SerialPort::control_address) ==
            control_for(SerialPort::Device::touchscreen, true),
        "le registre de commande se relit"
    );

    console.machine.input().set_touch(true, 0x64, 0x30);
    console.secondary().write8(
        SerialPort::data_address,
        static_cast<std::uint8_t>(
            Touchscreen::start_flag | (Touchscreen::channel_x << Touchscreen::channel_shift))
    );
    console.secondary().write8(SerialPort::data_address, 0);
    check(
        console.secondary().read8(SerialPort::data_address) == 0x32U,
        "et le registre de données rend la moitié haute de la mesure"
    );

    check(
        console.machine.secondary_memory().unimplemented_io_count() == before,
        "aucun de ces accès n'est compté comme inconnu"
    );

    // Lire le registre de données ne consomme rien : l'octet est déjà entré.
    check(
        console.secondary().read8(SerialPort::data_address) == 0x32U,
        "une relecture rend le même octet"
    );
}

/** Le processeur principal ne voit pas ce port : il n'est pas à lui. */
void le_principal_ne_voit_pas_le_port() {
    Console console;
    const auto before = console.machine.main_memory().unimplemented_io_count();
    static_cast<void>(console.main().read16(SerialPort::control_address));
    check(
        console.machine.main_memory().unimplemented_io_count() > before,
        "le port série n'est pas décodé du côté principal"
    );
}

/** La remise à zéro rend le port à son état de mise sous tension. */
void la_remise_a_zero_efface_le_port() {
    Console console;
    console.machine.input().set_touch(true, 0x64, 0x30);
    console.port().set_control(control_for(SerialPort::Device::power, true));
    static_cast<void>(exchange(console.port(), PowerManagement::register_control));
    static_cast<void>(exchange(console.port(), PowerManagement::power_off));
    static_cast<void>(exchange(console.port(), 0));

    console.machine.reset();

    check(console.port().control() == 0U, "le registre de commande repart de zéro");
    check(console.port().data() == 0U, "le registre de données aussi");
    check(!console.port().power().powered_off(), "et l'extinction demandée est oubliée");
    check(console.port().unsupported_count() == 0U, "les comptes repartent de zéro");
}

/**
 * Le chemin complet : un programme du processeur secondaire interroge la dalle.
 *
 * Rien n'est simulé ici. Le programme écrit dans les registres du port, la carte
 * mémoire les route, le bus mène l'échange, le convertisseur répond, et les deux
 * octets atterrissent dans la mémoire propre du processeur.
 */
void un_programme_interroge_l_ecran_tactile() {
    Console console;
    constexpr std::uint8_t touch_x = 0x6d;
    constexpr std::uint8_t touch_y = 0x30;
    constexpr std::uint32_t destination = private_wram_base + 0x100;

    console.cartouche.set_code(arm7_rom_offset, {
        mov_immediate(0U, 0x04U, 8U),                       // r0 = 0x0400'0000
        mov_immediate(4U, 0x0eU, 10U),                      // r4 = 0x0380'0000

        // Allumer le bus sur l'écran tactile, en retenant la sélection. Le
        // registre s'écrit octet par octet : un transfert de mot toucherait au
        // registre de données et lancerait un échange à l'improviste.
        mov_immediate(1U, control_for(SerialPort::Device::touchscreen, true) & 0xffU),
        transfer_byte(false, 0U, 1U, control_offset),
        mov_immediate(1U, control_for(SerialPort::Device::touchscreen, true) >> 8U),
        transfer_byte(false, 0U, 1U, control_offset + 1U),

        // La commande, puis deux octets creux pour recueillir la réponse.
        mov_immediate(1U, Touchscreen::start_flag |
            (Touchscreen::channel_x << Touchscreen::channel_shift)),
        transfer_byte(false, 0U, 1U, data_offset),
        mov_immediate(1U, 0U),
        transfer_byte(false, 0U, 1U, data_offset),
        transfer_byte(true, 0U, 2U, data_offset),
        transfer_byte(false, 0U, 1U, data_offset),
        transfer_byte(true, 0U, 3U, data_offset),

        transfer(false, 4U, 2U, 0x100U),
        transfer(false, 4U, 3U, 0x104U),
        branch(0),
    });
    console.boot();
    console.machine.input().set_touch(true, touch_x, touch_y);
    console.run_frame();

    const auto high = console.secondary().read32(destination);
    const auto low = console.secondary().read32(destination + 4U);
    const auto raw = static_cast<std::uint16_t>(
        ((high << 8U) | low) >> Touchscreen::presentation_shift);
    check(
        raw == Touchscreen::measure(touch_x),
        "le programme a recueilli la mesure de l'endroit touché"
    );
    check(low != 0U, "et les deux moitiés ont bien été lues");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_valeurs_sont_celles_du_materiel();
    les_reglages_se_lisent_et_leur_somme_tient();
    les_deux_exemplaires_sont_identiques();
    l_etalonnage_ramene_la_mesure_au_pixel();
    sans_contact_le_convertisseur_ne_rend_rien();
    un_canal_non_servi_est_compte();
    l_octet_de_commande_ne_rend_rien();
    le_maintien_de_la_selection_porte_la_commande();
    la_lecture_avance_toute_seule();
    la_flash_se_lit_effacee_hors_du_contenu();
    l_armement_de_l_ecriture_se_lit_dans_l_etat();
    une_commande_de_flash_non_servie_est_comptee();
    l_alimentation_retient_ses_reglages();
    la_batterie_ne_s_annonce_pas_faible();
    l_extinction_demandee_se_releve();
    un_registre_d_alimentation_inconnu_est_compte();
    changer_de_puce_referme_la_commande();
    le_bus_eteint_n_echange_rien();
    une_place_vide_est_comptee();
    un_echange_large_est_compte();
    la_fin_d_un_echange_peut_interrompre();
    le_bit_d_occupation_ne_s_ecrit_pas();
    les_registres_du_port_sont_decodes();
    le_principal_ne_voit_pas_le_port();
    la_remise_a_zero_efface_le_port();
    un_programme_interroge_l_ecran_tactile();
    return 0;
}
