#include <ravenemu/gbc/core.hpp>
#include <ravenemu/gbc/infrared_port.hpp>
#include <ravenemu/gbc/speed_controller.hpp>

#include "apu/apu.hpp"
#include "cartridge/cartridge_factory.hpp"
#include "input/joypad.hpp"
#include "interrupt/interrupt_controller.hpp"
#include "memory/memory_bus.hpp"
#include "machine/machine.hpp"
#include "ppu/ppu.hpp"
#include "serial/serial_port.hpp"
#include "timer/timer.hpp"
#include "check.hpp"
#include "synthetic_roms.hpp"

#include <cstdint>
#include <memory>
#include <vector>

using ravenemu::testing::check;
using ravenemu::testing::expect_failure;
using ravenemu::testing::minimal_game_boy_rom;

namespace ravenemu::cgb::testing {

struct Fixture {
    explicit Fixture(gb::HardwareMode mode = gb::HardwareMode::cgb_native,
                     std::span<const std::uint8_t> boot_image = {})
        : image(std::make_shared<const std::vector<std::uint8_t>>(make_rom())),
          cartridge(Cartridge::create(image, [] { return std::int64_t{0}; })),
          boot_rom(mode, boot_image), timer(interrupts, mode), joypad(interrupts),
          speed(gb::cgb_features_enabled(mode)), serial(interrupts, gb::cgb_features_enabled(mode)),
          infrared(gb::cgb_features_enabled(mode)), ppu(interrupts, mode), apu(mode),
          bus(*cartridge, ppu, interrupts, timer, serial, joypad, apu,
              mode, mode, speed, infrared, boot_rom) {
        ppu.write_lcdc(0x11); // LCD désactivé pour les tests mémoire directs.
    }

    static std::vector<std::uint8_t> make_rom() {
        auto rom = minimal_game_boy_rom();
        rom[0x0143] = 0x80;
        return rom;
    }

    RomImage image;
    std::unique_ptr<Cartridge> cartridge;
    BootRom boot_rom;
    InterruptController interrupts;
    Timer timer;
    Joypad joypad;
    SpeedController speed;
    SerialPort serial;
    InfraredPort infrared;
    Ppu ppu;
    Apu apu;
    MemoryBus bus;
};

void speed_switch_test() {
    SpeedController speed(true);
    check((speed.read_key1() & 0x80) == 0, "KEY1 démarre en vitesse normale");
    speed.write_key1(1);
    check(speed.begin_switch_from_stop(), "STOP n'a pas lancé la transition KEY1");
    speed.tick_peripheral(8'199);
    check(!speed.double_speed(), "vitesse commutée trop tôt");
    speed.tick_peripheral(1);
    check(speed.double_speed(), "double vitesse non activée");
    check((speed.read_key1() & 0x80) != 0, "KEY1 ne reflète pas la double vitesse");
}

void div_apu_bus_clock_test() {
    const auto arm_one_tick_length = [](Apu& apu) {
        apu.write(0xff11, 0x3f);
        apu.write(0xff12, 0xf0);
        apu.write(0xff14, 0xc0);
        check((apu.read(0xff26) & 1) != 0, "précondition canal APU longueur 1 absente");
    };

    Fixture normal(gb::HardwareMode::dmg);
    normal.bus.write(0xff04, 0);
    normal.timer.tick(0x1ffc);
    arm_one_tick_length(normal.apu);
    normal.bus.tick_mcycle(); // bit interne 12 : 1ffc -> 2000
    check((normal.apu.read(0xff26) & 1) == 0,
          "front DIV-APU normal non détecté à la frontière M-cycle");

    Fixture div_write(gb::HardwareMode::dmg);
    div_write.bus.write(0xff04, 0);
    div_write.timer.tick(0x1000); // ligne DIV-APU haute
    arm_one_tick_length(div_write.apu);
    div_write.bus.write(0xff04, 0);
    check((div_write.apu.read(0xff26) & 1) == 0,
          "écriture DIV sur ligne APU haute n'a pas produit de front descendant");

    Fixture stop(gb::HardwareMode::dmg);
    stop.bus.write(0xff04, 0);
    stop.timer.tick(0x1000);
    arm_one_tick_length(stop.apu);
    check(!stop.bus.on_stop(), "STOP DMG a lancé une transition de vitesse CGB");
    check((stop.apu.read(0xff26) & 1) == 0,
          "reset DIV de STOP n'a pas cadencé le séquenceur APU");

    Fixture doubled(gb::HardwareMode::cgb_native);
    doubled.speed.write_key1(1);
    check(doubled.bus.on_stop(), "STOP CGB n'a pas lancé la transition préparée");
    doubled.bus.tick_speed_switch(8'200);
    check(doubled.speed.double_speed(), "précondition double vitesse absente");
    doubled.timer.tick(0x3ffc);
    arm_one_tick_length(doubled.apu);
    doubled.bus.tick_mcycle(); // bit interne 13 : 3ffc -> 4000
    check((doubled.apu.read(0xff26) & 1) == 0,
          "double vitesse n'a pas sélectionné le bit 13 pour DIV-APU");
}

void infrared_test() {
    InfraredPort ir(true);
    check(ir.read() == 0x3e, "RP désactivé doit lire FF56=$3E");
    ir.write(0xc1);
    check(ir.led_on(), "LED IR non activée");
    check(ir.receiver_enabled(), "récepteur IR non activé");
    check((ir.read() & 0x02) != 0, "absence de lumière IR mal lue");
    ir.set_light_detected(true);
    check((ir.read() & 0x02) == 0, "lumière IR détectée mal lue");

    InfraredPort dmg(false);
    dmg.write(0xc1);
    check(dmg.read() == 0xff, "RP doit être inerte en mode DMG");

    LocalInfraredEndpoint local;
    InfraredPort a(true);
    InfraredPort b(true);
    check(a.connect(&local) && b.connect(&local), "connexion IR locale refusée");
    b.write(0xc0); // récepteur actif, LED éteinte
    a.write(0xc1); // récepteur actif, LED allumée
    check(b.light_detected() && (b.read() & 0x02) == 0,
          "la LED d'un CGB n'atteint pas le récepteur IR de l'autre");
    a.write(0xc0);
    check(!b.light_detected() && (b.read() & 0x02) != 0,
          "extinction IR non propagée entre les deux CGB");
}

void serial_fast_clock_test() {
    InterruptController interrupts;
    interrupts.flags = 0;
    SerialPort serial(interrupts, true);
    serial.write_data(0x00);
    serial.write_control(0x83); // start + horloge interne + fast CGB
    check(serial.fast_clock(), "SC.1 n'active pas l'horloge rapide CGB");
    serial.tick(16 * 7);
    check(serial.transfer_active(), "transfert série rapide terminé trop tôt");
    serial.tick(16);
    check(!serial.transfer_active(), "transfert série rapide non terminé");
    check(serial.read_data() == 0xff, "décalage série interne incorrect");
    check((interrupts.flags & interrupt_mask(Interrupt::serial)) != 0, "IRQ série absente");

    interrupts.flags = 0;
    serial.write_data(0x80);
    serial.write_control(0x80); // horloge externe
    for (int i = 0; i < 8; ++i) serial.clock_external_bit(false);
    check(serial.read_data() == 0x00, "horloge série externe incorrecte");
    check((interrupts.flags & interrupt_mask(Interrupt::serial)) != 0, "IRQ série externe absente");

    InterruptController phase_interrupts;
    SerialPort phase_source(phase_interrupts, false);
    phase_source.initialize_hle_post_boot(0x1fe);
    phase_source.write_data(0x00);
    phase_source.write_control(0x81);
    phase_source.tick(1);
    check(phase_source.bits_remaining() == 8,
          "horloge serie libre a produit un front avant sa limite");

    detail::BinaryWriter writer;
    phase_source.save(writer);
    const auto state = std::move(writer).take();
    SerialPort phase_restored(phase_interrupts, false);
    detail::BinaryReader reader(state);
    phase_restored.load(reader);
    phase_source.tick(1);
    phase_restored.tick(1);
    check(reader.exhausted() && phase_source.bits_remaining() == 7 &&
          phase_restored.bits_remaining() == 7,
          "phase libre de l'horloge serie non restauree au cycle pres");
}

void pcm_register_test() {
    Fixture native;
    check(native.bus.read(0xff76) == 0x00 && native.bus.read(0xff77) == 0x00,
          "sorties PCM CGB au repos incorrectes");
    native.apu.write(0xff11, 0xc0); // duty 75 %, étape 1 haute
    native.apu.write(0xff12, 0xf0); // DAC + volume 15
    native.apu.write(0xff13, 0xff);
    native.apu.write(0xff14, 0x87); // trigger, première étape forcée à zéro
    check((native.bus.read(0xff76) & 0x0f) == 0x00,
          "FF76 n'expose pas la première étape duty forcée à zéro");
    native.apu.tick(4);
    check((native.bus.read(0xff76) & 0x0f) == 0x0f,
          "FF76 n'expose pas la sortie numérique après le premier clock duty");
    native.bus.write(0xff76, 0x00);
    check((native.bus.read(0xff76) & 0x0f) == 0x0f,
          "registre PCM CGB en lecture seule modifié par une écriture");

    Fixture compatibility(gb::HardwareMode::cgb_compatibility);
    Fixture dmg(gb::HardwareMode::dmg);
    check(compatibility.bus.read(0xff76) == 0x00 &&
          compatibility.bus.read(0xff77) == 0x00 &&
          dmg.bus.read(0xff77) == 0xff,
          "visibilite PCM incorrecte entre materiel CGB compatible et DMG");
}

void undocumented_cgb_io_test() {
    Fixture compatibility(gb::HardwareMode::cgb_compatibility);
    check(compatibility.bus.read(0xff72) == 0x00 &&
          compatibility.bus.read(0xff73) == 0x00 &&
          compatibility.bus.read(0xff74) == 0xff &&
          compatibility.bus.read(0xff75) == 0x8f,
          "valeurs initiales FF72-FF75 incorrectes en compatibilité CGB");
    compatibility.bus.write(0xff72, 0x12);
    compatibility.bus.write(0xff73, 0x34);
    compatibility.bus.write(0xff74, 0x56);
    compatibility.bus.write(0xff75, 0xff);
    check(compatibility.bus.read(0xff72) == 0x12 &&
          compatibility.bus.read(0xff73) == 0x34 &&
          compatibility.bus.read(0xff74) == 0xff &&
          compatibility.bus.read(0xff75) == 0xff,
          "masques FF72-FF75 incorrects en compatibilité CGB");

    Fixture native;
    native.bus.write(0xff74, 0x56);
    check(native.bus.read(0xff74) == 0x56,
          "FF74 doit être accessible uniquement en mode CGB natif");

    detail::BinaryWriter writer;
    compatibility.bus.save(writer);
    const auto state = std::move(writer).take();
    Fixture restored(gb::HardwareMode::cgb_compatibility);
    detail::BinaryReader reader(state);
    restored.bus.load(reader);
    check(reader.exhausted() && restored.bus.read(0xff72) == 0x12 &&
          restored.bus.read(0xff73) == 0x34 && restored.bus.read(0xff75) == 0xff,
          "registres CGB FF72/FF73/FF75 absents du save state");
}

void joypad_save_state_test() {
    InterruptController source_interrupts;
    Joypad source(source_interrupts);
    source.write(0x00);
    source.set_button(Button::a, true);
    source.set_button(Button::left, true);

    detail::BinaryWriter writer;
    source.save(writer);
    const auto state = std::move(writer).take();

    InterruptController restored_interrupts;
    Joypad restored(restored_interrupts);
    detail::BinaryReader reader(state);
    restored.load(reader);
    check(reader.exhausted() && restored.read() == source.read(),
          "boutons ou lignes P1 non restaures par le save state");
    check(restored.take_stop_wake() && !restored.take_stop_wake(),
          "front de reveil STOP joypad non restaure exactement une fois");

    restored.write(0x20); // directions uniquement
    check((restored.read() & 0x0f) == 0x0d,
          "etat directionnel joypad perdu apres restauration");
    restored.write(0x10); // boutons d'action uniquement
    check((restored.read() & 0x0f) == 0x0e,
          "etat des boutons d'action perdu apres restauration");
}

void local_link_endpoint_test() {
    LocalLinkEndpoint link;
    InterruptController interrupts_a;
    InterruptController interrupts_b;
    interrupts_a.flags = 0;
    interrupts_b.flags = 0;
    SerialPort a(interrupts_a, true);
    SerialPort b(interrupts_b, true);
    check(a.connect(&link) && b.connect(&link), "connexion du câble local refusée");

    a.write_data(0xa5);
    b.write_data(0x3c);
    b.write_control(0x80); // esclave, horloge externe
    a.write_control(0x81); // maître, horloge interne normale
    a.tick(512 * 8);

    check(a.read_data() == 0x3c && b.read_data() == 0xa5,
          "deux SerialPort reliés n'ont pas échangé leurs octets bit par bit");
    check(!a.transfer_active() && !b.transfer_active(), "transfert link local laissé actif");
    check((interrupts_a.flags & interrupt_mask(Interrupt::serial)) != 0 &&
          (interrupts_b.flags & interrupt_mask(Interrupt::serial)) != 0,
          "IRQ série absente sur une extrémité du link local");
}


void ppu_dot_timing_test() {
    InterruptController interrupts;
    Ppu ppu(interrupts, true);
    ppu.write_lcdc(0x11);
    ppu.set_scx(7);
    ppu.write_bcps(0);
    ppu.write_bcpd(0x12);
    ppu.write_lcdc(0x91);

    ppu.tick(80);
    check((ppu.read_stat() & 3) == Ppu::mode_transfer, "PPU n'entre pas en mode 3 au dot 80");
    check(ppu.read_bcpd() == 0xff, "CRAM doit être inaccessible pendant le mode 3");
    ppu.write_bcpd(0x34);

    ppu.tick(174);
    check((ppu.read_stat() & 3) == Ppu::mode_transfer, "mode 3 avec SCX fin trop court");
    ppu.tick(1);
    check((ppu.read_stat() & 3) == Ppu::mode_hblank, "PPU ne termine pas le transfert pixel au bon moment");
    ppu.write_lcdc(0x11);
    check(ppu.read_bcpd() == 0x12, "écriture CRAM pendant mode 3 n'a pas été bloquée");
}

void opri_and_hardware_mode_test() {
    InterruptController interrupts;
    Ppu dmg(interrupts, gb::HardwareMode::dmg);
    check(dmg.read_opri() == 0xff, "OPRI doit être absent du matériel DMG");
    dmg.write_opri(0);
    check(dmg.read_opri() == 0xff, "écriture OPRI visible sur DMG");

    Ppu native(interrupts, gb::HardwareMode::cgb_native);
    check(native.read_opri() == 0xfe, "OPRI CGB natif doit démarrer en priorité OAM");
    native.write_opri(1);
    check(native.read_opri() == 0xff, "OPRI n'expose pas son bit de priorité DMG");
    native.write_opri(0xfe);
    check(native.read_opri() == 0xfe, "bits inutilisés d'OPRI ou écriture bit 0 incorrects");
    native.write_opri(1);
    detail::BinaryWriter writer;
    native.save(writer);
    const auto state = std::move(writer).take();
    Ppu restored(interrupts, gb::HardwareMode::cgb_native);
    detail::BinaryReader reader(state);
    restored.load(reader);
    check(restored.read_opri() == 0xff && reader.exhausted(),
          "OPRI non restauré par le save state PPU");

    Ppu compatibility(interrupts, gb::HardwareMode::cgb_compatibility);
    check(compatibility.read_opri() == 0xff,
          "OPRI doit etre masque au logiciel apres l'entree en mode compatible");
    compatibility.write_opri(0);
    check(compatibility.read_opri() == 0xff,
          "une ecriture OPRI doit etre ignoree en mode compatible");
    check(compatibility.read_vram_bank() == 0xfe,
          "VBK doit exposer la banque interne 0 sur matériel CGB compatible");
}

void boot_rom_power_on_state_test() {
    auto rom = std::make_shared<const std::vector<std::uint8_t>>(Fixture::make_rom());
    const std::vector<std::uint8_t> boot(BootRom::cgb_size);
    Machine machine(rom, [] { return std::int64_t{0}; },
                    gb::HardwareMode::cgb_native, boot);
    check(machine.cpu.pc == 0 && machine.cpu.sp == 0 && machine.cpu.af() == 0,
          "CPU non placé dans l'état de mise sous tension avec boot ROM");
    check(!machine.ppu.lcd_enabled() && machine.timer.read_div() == 0,
          "PPU ou DIV laissé dans l'état HLE post-boot avec boot ROM");
    check(machine.bus.read(0xff0f) == 0xe0 && machine.apu.read(0xff26) == 0x70,
          "interruptions ou APU non réinitialisées avant le firmware");
}

void cgb_boot_to_compatibility_transition_test() {
    auto rom = std::make_shared<const std::vector<std::uint8_t>>(minimal_game_boy_rom());
    std::vector<std::uint8_t> boot(BootRom::cgb_size);
    Machine machine(rom, [] { return std::int64_t{0}; },
                    gb::HardwareMode::cgb_compatibility, boot);

    check(machine.bus.read(0xff68) == 0x40 && machine.bus.read(0xff4d) == 0x7e,
          "boot ROM CGB d'une cartouche DMG privée prématurément des registres CGB");
    machine.bus.write(0xff6c, 0x01);
    machine.bus.write(0xff50, 0x01);
    check(machine.bus.read(0xff68) == 0x40 && machine.bus.read(0xff4d) == 0xff &&
          machine.bus.read(0xff56) == 0xff,
          "FF50 n'a pas basculé le firmware CGB vers le mode compatibilité DMG");
    check(machine.ppu.read_opri() == 0xff && machine.ppu.read_vram_bank() == 0xfe,
          "priorité OPRI ou lecture VBK incorrecte pendant la transition de compatibilité");
}

void hle_post_boot_register_test() {
    {
        auto rom = Fixture::make_rom();
        auto image = std::make_shared<const std::vector<std::uint8_t>>(std::move(rom));
        Machine machine(image, [] { return std::int64_t{0}; }, gb::HardwareMode::cgb_native);
        check(machine.cpu.af() == 0x1180 && machine.cpu.bc() == 0x0000 &&
              machine.cpu.de() == 0xff56 && machine.cpu.hl() == 0x000d &&
              machine.cpu.sp == 0xfffe && machine.cpu.pc == 0x0100,
              "état CPU HLE post-boot CGB natif incorrect");
        check(machine.timer.read_div() == 0x26 && machine.joypad.read() == 0xff,
              "phase DIV ou P1 HLE post-boot CGB incorrecte");
        check(machine.bus.read(0xff68) == 0xc8 && machine.bus.read(0xff6a) == 0xd0,
              "index de palettes HLE post-boot CGB incorrect");
    }

    {
        auto rom = minimal_game_boy_rom();
        rom[0x014b] = 0x01;
        rom[0x0134] = 0x43; // somme des 16 octets de titre = $43
        auto image = std::make_shared<const std::vector<std::uint8_t>>(std::move(rom));
        Machine machine(image, [] { return std::int64_t{0}; },
                        gb::HardwareMode::cgb_compatibility);
        check(machine.cpu.af() == 0x1180 && machine.cpu.bc() == 0x4300 &&
              machine.cpu.de() == 0x0008 && machine.cpu.hl() == 0x991a,
              "état CPU HLE du mode compatibilité CGB incorrect");
    }

    {
        auto rom = minimal_game_boy_rom();
        rom[0x014d] = 0x01;
        auto image = std::make_shared<const std::vector<std::uint8_t>>(std::move(rom));
        Machine machine(image, [] { return std::int64_t{0}; }, gb::HardwareMode::dmg);
        check(machine.cpu.af() == 0x01b0 && machine.cpu.bc() == 0x0013 &&
              machine.cpu.de() == 0x00d8 && machine.cpu.hl() == 0x014d,
              "état CPU HLE post-boot DMG incorrect");
        check(machine.timer.read_div() == 0xab && machine.joypad.read() == 0xcf,
              "phase DIV ou P1 HLE post-boot DMG incorrecte");
    }
}

void boot_rom_mapping_test() {
    std::vector<std::uint8_t> dmg_image(BootRom::dmg_size);
    dmg_image[0] = 0x5a;
    Fixture dmg(gb::HardwareMode::dmg, dmg_image);
    check(dmg.bus.read(0x0000) == 0x5a,
          "boot ROM DMG absente de 0000-00FF");
    check(dmg.bus.read(0x0100) != 0x5a,
          "boot ROM DMG a masque la cartouche au-dela de 00FF");
    dmg.bus.write(0xff50, 0x01);
    check(dmg.bus.read(0x0000) != 0x5a,
          "FF50 n'a pas desactive le mapping DMG");

    std::vector<std::uint8_t> image(BootRom::cgb_size);
    image[0] = 0xa1;
    image[0x100] = 0xb2; // adresse CPU 0200 dans le mapping scindé CGB
    Fixture f(gb::HardwareMode::cgb_native, image);

    check(f.bus.read(0x0000) == 0xa1, "boot ROM CGB absente de 0000-00FF");
    check(f.bus.read(0x0100) != 0xb2, "boot ROM CGB a masqué l'en-tête cartouche 0100-01FF");
    check(f.bus.read(0x0200) == 0xb2, "seconde plage de boot ROM CGB mal mappée");
    f.bus.write(0xff50, 0x00);
    check(f.bus.read(0x0000) == 0xa1, "FF50=0 a désactivé la boot ROM");
    f.bus.write(0xff50, 0x01);
    check(f.bus.read(0x0000) != 0xa1, "FF50 non nul n'a pas désactivé la boot ROM");
    f.bus.write(0xff50, 0x00);
    check(f.bus.read(0x0000) != 0xa1, "FF50 a remappé la boot ROM après verrouillage");

    std::vector<std::uint8_t> mapped_image(BootRom::cgb_mapped_size);
    mapped_image[0x0200] = 0xc3;
    Fixture mapped(gb::HardwareMode::cgb_native, mapped_image);
    check(mapped.bus.read(0x0200) == 0xc3,
          "layout CGB 2304 octets non mappe a son adresse physique");

    std::vector<std::uint8_t> first_image(BootRom::cgb_size);
    first_image[0] = 0x11;
    BootRom first(gb::HardwareMode::cgb_native, first_image);
    detail::BinaryWriter writer;
    first.save(writer);
    const auto state = std::move(writer).take();

    BootRom same(gb::HardwareMode::cgb_native, first_image);
    detail::BinaryReader same_reader(state);
    same.load(same_reader);
    check(same.mapped() && same_reader.exhausted(),
          "état de boot ROM identique non restauré");

    auto other_image = first_image;
    other_image[0] = 0x22;
    BootRom other(gb::HardwareMode::cgb_native, other_image);
    expect_failure<ravenemu::SaveStateError>(
        [&] {
            detail::BinaryReader other_reader(state);
            other.load(other_reader);
        },
        "un état de boot ROM a été chargé avec une autre image de même taille"
    );

    expect_failure<ravenemu::RomLoadError>(
        [] { BootRom invalid(gb::HardwareMode::dmg, std::vector<std::uint8_t>(255)); },
        "une boot ROM DMG de taille invalide a été acceptée"
    );
}

void mbc5_rumble_test() {
    auto rom = minimal_game_boy_rom();
    rom[0x0143] = 0x80;
    rom[0x0147] = 0x1e; // MBC5 + rumble + RAM + batterie
    rom[0x0149] = 0x02;
    auto image = std::make_shared<const std::vector<std::uint8_t>>(std::move(rom));
    auto cartridge = Cartridge::create(image, [] { return std::int64_t{0}; });
    check(!cartridge->rumble_active(), "rumble MBC5 actif au démarrage");
    cartridge->write_control(0x4000, 0x08);
    check(cartridge->rumble_active(), "bit rumble MBC5 ignoré");
    cartridge->write_control(0x4000, 0x00);
    check(!cartridge->rumble_active(), "rumble MBC5 ne s'arrête pas");
}

void oam_dma_timing_test() {
    Fixture f;
    f.bus.write(0xc000, 0xa7);
    f.bus.write(0xff46, 0xc0);
    check(f.ppu.oam[0] == 0, "OAM DMA ne doit plus copier instantanément");
    f.bus.tick(4, 4);
    check(f.ppu.oam[0] == 0, "OAM DMA a ignoré son M-cycle de démarrage");
    f.bus.tick(3, 3);
    check(f.ppu.oam[0] == 0, "OAM DMA a transféré avant quatre cycles de copie");
    f.bus.tick(1, 1);
    check(f.ppu.oam[0] == 0xa7, "premier octet OAM DMA absent");

    for (int i = 1; i < 0xa0; ++i) f.bus.tick(4, 4);
    check(!f.bus.dma_active(), "OAM DMA reste actif après 160 octets");

    f.bus.write(0xff46, 0xf0); // source echo/haute invalide sur CGB
    f.bus.tick(8, 8);
    check(f.ppu.oam[0] == 0xff, "source OAM DMA F000 ne doit pas être traitée comme WRAM");

    Fixture split;
    split.bus.write(0xc000, 0x6a);
    split.bus.write(0xff46, 0xc0);
    check(split.bus.read(0xc000) == 0x6a,
          "CGB a bloqué le bus pendant le M-cycle de démarrage OAM DMA");
    split.bus.tick(5, 5);
    check(split.bus.read(0x0000) != 0xff, "CGB a bloqué le bus cartouche pendant un DMA depuis WRAM");
    check(split.bus.read(0xc000) == 0xff, "CGB a laissé le CPU lire le bus source WRAM du DMA");
    check(split.bus.read(0xff80) == 0x00, "HRAM bloquée pendant OAM DMA CGB");

    Fixture dmg_vram(gb::HardwareMode::dmg);
    dmg_vram.bus.write(0x8000, 0x5c);
    dmg_vram.bus.write(0xc000, 0x6a);
    dmg_vram.bus.write(0xff46, 0x80);
    dmg_vram.bus.tick(8, 8); // démarrage + premier octet
    check(dmg_vram.bus.read(0x0000) != 0xff && dmg_vram.bus.read(0xc000) == 0x6a &&
          dmg_vram.bus.read(0x8000) == 0xff,
          "arbitrage DMG n'a pas séparé ROM/WRAM du bus source VRAM");
    dmg_vram.bus.tick(4 * 158, 4 * 158);
    check(dmg_vram.bus.read_mcycle(0x8000) == 0xff,
          "dernier M-cycle OAM DMA n'a pas conservé le conflit du bus source");
    check(dmg_vram.bus.read_mcycle(0x8000) == 0x5c,
          "bus source non libéré au M-cycle suivant l'OAM DMA");

    Fixture dmg_high(gb::HardwareMode::dmg);
    dmg_high.bus.write(0xc000, 0x39);
    dmg_high.bus.write(0xff46, 0xe0);
    dmg_high.bus.tick(8, 8);
    check(dmg_high.ppu.oam[0] == 0x39,
          "source OAM DMA DMG E000 non rebouclée vers C000");

    Fixture compatibility(gb::HardwareMode::cgb_compatibility);
    compatibility.bus.write(0xff46, 0xc0);
    compatibility.bus.tick(5, 5);
    check(compatibility.bus.read(0x0000) != 0xff,
          "compatibilité CGB n'utilise pas les bus physiques séparés du CGB");

    Fixture dmg(gb::HardwareMode::dmg);
    dmg.bus.write(0xff46, 0xc0);
    check(dmg.bus.read(0x0000) != 0xff,
          "DMG a bloqué le bus pendant le M-cycle de démarrage OAM DMA");
    dmg.bus.tick(5, 5);
    check(dmg.bus.read(0x0000) == 0xff && dmg.bus.read(0xc000) == 0xff,
          "DMG doit restreindre le CPU à HRAM pendant OAM DMA");
}

void vram_dma_timing_test() {
    Fixture f;
    for (int i = 0; i < 32; ++i) f.bus.write(0xc000 + i, 0x40 + i);
    f.bus.write(0xff51, 0xc0);
    f.bus.write(0xff52, 0x00);
    f.bus.write(0xff53, 0x00);
    f.bus.write(0xff54, 0x00);
    f.bus.write(0xff55, 0x00); // GDMA, 16 octets
    check(f.bus.cpu_blocked(), "GDMA doit bloquer le CPU");
    f.bus.tick(0, 31);
    check(f.ppu.vram[15] == 0, "GDMA a terminé avant 32 dots");
    f.bus.tick(0, 1);
    check(f.ppu.vram[15] == 0x4f, "dernier octet GDMA absent");
    check(!f.bus.cpu_blocked(), "CPU encore bloqué après GDMA");

    f.bus.write(0xff51, 0xc0);
    f.bus.write(0xff52, 0x00);
    f.bus.write(0xff53, 0x00);
    f.bus.write(0xff54, 0x20);
    f.ppu.write_lcdc(0x91);
    f.bus.write(0xff55, 0x81); // HDMA, deux blocs
    check(!f.bus.cpu_blocked(), "HDMA ne doit pas bloquer hors HBlank");
    f.bus.notify_hblank();
    check(f.bus.cpu_blocked(), "bloc HDMA HBlank doit bloquer le CPU");
    f.bus.tick(0, 32);
    check(f.ppu.vram[0x2f] == 0x4f, "premier bloc HDMA incomplet");
    check(!f.bus.cpu_blocked(), "HDMA doit libérer le CPU entre deux HBlank");
    f.bus.notify_hblank();
    f.bus.tick(0, 32);
    check(f.ppu.vram[0x3f] == 0x5f, "second bloc HDMA incomplet");
    check(f.bus.read(0xff55) == 0xff, "HDMA terminé doit lire FF55=FF");

    Fixture lcd_off;
    lcd_off.bus.write(0xff51, 0xc0);
    lcd_off.bus.write(0xff52, 0x00);
    lcd_off.bus.write(0xff53, 0x00);
    lcd_off.bus.write(0xff54, 0x00);
    lcd_off.bus.write(0xff55, 0x80);
    check(lcd_off.bus.cpu_blocked(), "HDMA demandé LCD off doit devenir un GDMA bloquant");

    Fixture disable_during_hdma;
    for (int i = 0; i < 32; ++i) disable_during_hdma.bus.write(0xc000 + i, 0x70 + i);
    disable_during_hdma.ppu.write_lcdc(0x91);
    disable_during_hdma.bus.write(0xff51, 0xc0);
    disable_during_hdma.bus.write(0xff52, 0x00);
    disable_during_hdma.bus.write(0xff53, 0x00);
    disable_during_hdma.bus.write(0xff54, 0x00);
    disable_during_hdma.bus.write(0xff55, 0x81);
    disable_during_hdma.bus.write(0xff40, 0x11);
    check(disable_during_hdma.bus.cpu_blocked(),
          "désactivation LCD n'a pas converti le HDMA en transfert général");
    disable_during_hdma.bus.tick(0, 64);
    check(disable_during_hdma.ppu.vram[31] == 0x8f &&
          disable_during_hdma.bus.read(0xff55) == 0xff,
          "HDMA non terminé après désactivation du LCD");

    Fixture cancel;
    cancel.ppu.write_lcdc(0x91);
    cancel.bus.write(0xff55, 0x81);
    cancel.bus.write(0xff55, 0x00);
    check(!cancel.bus.cpu_blocked() && cancel.bus.read(0xff55) == 0x81,
          "annulation HDMA ne conserve pas le nombre de blocs restant dans FF55");

    Fixture overflow;
    for (int i = 0; i < 32; ++i) overflow.bus.write(0xc000 + i, 0x20 + i);
    overflow.bus.write(0xff51, 0xc0);
    overflow.bus.write(0xff52, 0x00);
    overflow.bus.write(0xff53, 0x1f);
    overflow.bus.write(0xff54, 0xf0);
    overflow.bus.write(0xff55, 0x01);
    overflow.bus.tick(0, 64);
    check(overflow.ppu.vram[0x1fff] == 0x2f && overflow.ppu.vram[0] == 0x00,
          "overflow destination VRAM du GDMA a rebouclé vers 8000");
    check(overflow.bus.read(0xff55) == 0x80,
          "GDMA arrêté sur overflow ne signale pas le bloc restant");
}

} // namespace ravenemu::cgb::testing

int main() {
    using namespace ravenemu::cgb::testing;
    speed_switch_test();
    div_apu_bus_clock_test();
    infrared_test();
    serial_fast_clock_test();
    pcm_register_test();
    undocumented_cgb_io_test();
    joypad_save_state_test();
    local_link_endpoint_test();
    ppu_dot_timing_test();
    opri_and_hardware_mode_test();
    boot_rom_mapping_test();
    boot_rom_power_on_state_test();
    cgb_boot_to_compatibility_transition_test();
    hle_post_boot_register_test();
    mbc5_rumble_test();
    oam_dma_timing_test();
    vram_dma_timing_test();

    auto rom = minimal_game_boy_rom();
    rom[0x0143] = 0x80;
    auto core = ravenemu::gbc::make_core();
    core->load_rom(rom, {});
    check(core->framebuffer_format() == ravenemu::FramebufferFormat::argb_8888,
          "frontière GBC n'active pas le framebuffer couleur");

    auto dmg_rom = minimal_game_boy_rom();
    auto cgb_compatibility = ravenemu::gbc::make_core();
    cgb_compatibility->load_rom(dmg_rom, {});
    check(cgb_compatibility->framebuffer_format() == ravenemu::FramebufferFormat::argb_8888,
          "une ROM DMG sur matériel CGB doit conserver la sortie couleur de compatibilité");
    const auto state = cgb_compatibility->save_state();
    cgb_compatibility->load_state(state);

    auto automatic_dmg = ravenemu::make_game_boy_core();
    automatic_dmg->load_rom(dmg_rom, {});
    expect_failure<ravenemu::SaveStateError>(
        [&] { automatic_dmg->load_state(state); },
        "un save state CGB compatibilité a été accepté par une machine DMG"
    );
    return 0;
}
