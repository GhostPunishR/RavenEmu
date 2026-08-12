#include "cartridge/cartridge_factory.hpp"
#include "check.hpp"

#include <memory>
#include <vector>

using ravenemu::testing::check;

namespace ravenemu::cgb::testing {

void finalize_header_checksum(std::vector<std::uint8_t>& rom, std::size_t base) {
    std::uint8_t checksum{};
    for (std::size_t offset = 0x134; offset <= 0x14c; ++offset) {
        checksum = static_cast<std::uint8_t>(checksum - rom[base + offset] - 1U);
    }
    rom[base + 0x14d] = checksum;
}

std::vector<std::uint8_t> make_one_megabyte_mbc1(bool multicart) {
    std::vector<std::uint8_t> rom(0x100000);
    for (int bank = 0; bank < 64; ++bank) {
        std::fill_n(rom.begin() + static_cast<std::ptrdiff_t>(bank * Cartridge::rom_bank_size),
                    Cartridge::rom_bank_size, static_cast<std::uint8_t>(bank));
    }
    rom[0x147] = 0x03; // MBC1 + RAM + batterie
    rom[0x149] = 0x02; // 8 Kio
    finalize_header_checksum(rom, 0);

    constexpr std::size_t second_header = 0x40000; // banque $10
    rom[second_header + 0x147] = 0x00;
    finalize_header_checksum(rom, second_header);
    if (!multicart) rom[second_header + 0x14d] ^= 1;
    return rom;
}

std::unique_ptr<Cartridge> create(std::vector<std::uint8_t> rom) {
    auto image = std::make_shared<const std::vector<std::uint8_t>>(std::move(rom));
    return Cartridge::create(std::move(image), [] { return std::int64_t{0}; });
}

std::vector<std::uint8_t> make_mbc3_rtc() {
    std::vector<std::uint8_t> rom(0x8000);
    rom[0x147] = 0x10; // MBC3 + timer + RAM + batterie
    rom[0x149] = 0x03;
    return rom;
}

std::unique_ptr<Cartridge> create_rtc(std::int64_t& epoch) {
    auto image = std::make_shared<const std::vector<std::uint8_t>>(make_mbc3_rtc());
    return Cartridge::create(std::move(image), [&epoch] { return epoch; });
}

void rtc_write(Cartridge& cartridge, int reg, int value) {
    cartridge.write_control(0x0000, 0x0a);
    cartridge.write_control(0x4000, reg);
    cartridge.write_ram(0xa000, value);
}

void rtc_latch(Cartridge& cartridge) {
    cartridge.write_control(0x0000, 0x0a);
    cartridge.write_control(0x6000, 0);
    cartridge.write_control(0x6000, 1);
}

int rtc_read(Cartridge& cartridge, int reg) {
    cartridge.write_control(0x4000, reg);
    return cartridge.read_ram(0xa000);
}

void mbc1m_detection_and_banking_test() {
    auto multicart = create(make_one_megabyte_mbc1(true));
    check(multicart->header().mbc1_multicart, "MBC1M synthétique non détecté");
    check(multicart->read_rom(0x4000) == 1, "banque initiale MBC1M incorrecte");

    multicart->write_control(0x4000, 1);
    check(multicart->read_rom(0x4000) == 17,
          "registre secondaire MBC1M non câblé sur les bits 4-5");
    multicart->write_control(0x6000, 1);
    check(multicart->read_rom(0x0000) == 16,
          "mode 1 MBC1M ne remappe pas la fenêtre ROM basse");
    multicart->write_control(0x2000, 0x10);
    check(multicart->read_rom(0x4000) == 16,
          "bit 4 MBC1M ignoré sans préserver la règle spéciale banque zéro");

    auto regular = create(make_one_megabyte_mbc1(false));
    check(!regular->header().mbc1_multicart, "MBC1 standard détecté à tort comme MBC1M");
    regular->write_control(0x4000, 1);
    check(regular->read_rom(0x4000) == 33,
          "MBC1 standard 1 Mio a perdu son câblage sur les bits 5-6");
}

void mbc1m_state_and_persistence_test() {
    auto source = create(make_one_megabyte_mbc1(true));
    source->write_control(0x0000, 0x0a);
    source->write_control(0x4000, 2);
    source->write_control(0x2000, 3);
    source->write_control(0x6000, 1);
    source->write_ram(0xa000, 0x5a);

    detail::BinaryWriter writer;
    source->save_state(writer);
    const auto state = std::move(writer).take();
    auto restored = create(make_one_megabyte_mbc1(true));
    detail::BinaryReader reader(state);
    restored->load_state(reader);
    check(reader.exhausted(), "save state MBC1M laisse des données excédentaires");
    check(restored->read_rom(0x0000) == 32 && restored->read_rom(0x4000) == 35,
          "banques MBC1M non restaurées");
    check(restored->read_ram(0xa000) == 0x5a, "RAM MBC1M non restaurée");

    const auto battery = source->export_battery();
    check(battery.has_value(), "persistance batterie MBC1M absente");
    auto persisted = create(make_one_megabyte_mbc1(true));
    persisted->import_battery(*battery);
    persisted->write_control(0x0000, 0x0a);
    check(persisted->read_ram(0xa000) == 0x5a, "RAM batterie MBC1M non réimportée");
}

void mbc3_rtc_progress_halt_and_carry_test() {
    std::int64_t epoch = 1'000;
    auto rtc = create_rtc(epoch);
    rtc_write(*rtc, 0x08, 58);
    rtc_write(*rtc, 0x09, 59);
    rtc_write(*rtc, 0x0a, 23);
    rtc_write(*rtc, 0x0b, 0);
    rtc_write(*rtc, 0x0c, 0);
    epoch += 3;
    rtc_latch(*rtc);
    check(rtc_read(*rtc, 0x08) == 1 && rtc_read(*rtc, 0x09) == 0 &&
          rtc_read(*rtc, 0x0a) == 0 && rtc_read(*rtc, 0x0b) == 1,
          "RTC MBC3 ne propage pas correctement seconde/minute/heure/jour");

    rtc_write(*rtc, 0x0c, 0x40); // halt
    epoch += 100'000;
    rtc_latch(*rtc);
    check(rtc_read(*rtc, 0x08) == 1 && (rtc_read(*rtc, 0x0c) & 0x40) != 0,
          "RTC MBC3 avance malgré le bit halt");
    rtc_write(*rtc, 0x0c, 0x00); // reprise sans rattraper la période suspendue
    epoch += 2;
    rtc_latch(*rtc);
    check(rtc_read(*rtc, 0x08) == 3,
          "RTC MBC3 rattrape le temps suspendu ou ne reprend pas après halt");

    rtc_write(*rtc, 0x08, 0); rtc_write(*rtc, 0x09, 0); rtc_write(*rtc, 0x0a, 0);
    rtc_write(*rtc, 0x0b, 0xff); rtc_write(*rtc, 0x0c, 0x01); // jour 511
    epoch += 86'400;
    rtc_latch(*rtc);
    check(rtc_read(*rtc, 0x0b) == 0 && (rtc_read(*rtc, 0x0c) & 0x81) == 0x80,
          "RTC MBC3 ne reboucle pas le jour 511 avec carry");
    rtc_write(*rtc, 0x0c, 0x00);
    rtc_latch(*rtc);
    check((rtc_read(*rtc, 0x0c) & 0x80) == 0, "carry RTC MBC3 impossible à effacer");
}

void mbc3_rtc_battery_and_state_test() {
    std::int64_t epoch = 10'000;
    auto source = create_rtc(epoch);
    rtc_write(*source, 0x08, 7);
    rtc_write(*source, 0x09, 6);
    rtc_write(*source, 0x0a, 5);
    rtc_latch(*source);

    const auto battery = source->export_battery();
    check(battery.has_value() && battery->size() == 32U * 1024U + 48U,
          "footer batterie RTC MBC3 absent ou de taille incorrecte");
    epoch += 60;
    auto imported = create_rtc(epoch);
    imported->import_battery(*battery);
    rtc_latch(*imported);
    const int imported_seconds = rtc_read(*imported, 0x08);
    const int imported_minutes = rtc_read(*imported, 0x09);
    const int imported_hours = rtc_read(*imported, 0x0a);
    if (imported_seconds != 7 || imported_minutes != 7 || imported_hours != 5) {
        throw std::runtime_error("RTC MBC3 persisté incorrect après arrêt : " +
            std::to_string(imported_hours) + ":" + std::to_string(imported_minutes) +
            ":" + std::to_string(imported_seconds));
    }

    detail::BinaryWriter writer;
    imported->save_state(writer);
    const auto state = std::move(writer).take();
    auto restored = create_rtc(epoch);
    detail::BinaryReader reader(state);
    restored->load_state(reader);
    rtc_latch(*restored);
    check(reader.exhausted() && rtc_read(*restored, 0x09) == 7,
          "save state RTC MBC3 non déterministe à horloge figée");

    epoch += 86'400LL * 1'025LL;
    rtc_latch(*restored);
    check(rtc_read(*restored, 0x0b) == 1 && (rtc_read(*restored, 0x0c) & 0x80) != 0,
          "RTC MBC3 échoue après une période supérieure à deux tours de compteur");
}

} // namespace ravenemu::cgb::testing

int main() {
    ravenemu::cgb::testing::mbc1m_detection_and_banking_test();
    ravenemu::cgb::testing::mbc1m_state_and_persistence_test();
    ravenemu::cgb::testing::mbc3_rtc_progress_halt_and_carry_test();
    ravenemu::cgb::testing::mbc3_rtc_battery_and_state_test();
    return 0;
}
