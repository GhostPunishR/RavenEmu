#include <ravenemu/gbc/core.hpp>
#include <ravenemu/gbc/infrared_port.hpp>
#include <ravenemu/gbc/speed_controller.hpp>

#include "apu/apu.hpp"
#include "cartridge/cartridge_factory.hpp"
#include "input/joypad.hpp"
#include "interrupt/interrupt_controller.hpp"
#include "memory/memory_bus.hpp"
#include "ppu/ppu.hpp"
#include "serial/serial_port.hpp"
#include "timer/timer.hpp"
#include "check.hpp"
#include "synthetic_roms.hpp"

#include <cstdint>
#include <memory>
#include <vector>

using ravenemu::testing::check;
using ravenemu::testing::minimal_game_boy_rom;

namespace ravenemu::cgb::testing {

struct Fixture {
    Fixture()
        : image(std::make_shared<const std::vector<std::uint8_t>>(make_rom())),
          cartridge(Cartridge::create(image, [] { return std::int64_t{0}; })),
          timer(interrupts), joypad(interrupts), speed(true), serial(interrupts, true), infrared(true),
          ppu(interrupts, true), bus(*cartridge, ppu, interrupts, timer, serial, joypad, apu, true, speed, infrared) {
        ppu.write_lcdc(0x11); // LCD désactivé pour les tests mémoire directs.
    }

    static std::vector<std::uint8_t> make_rom() {
        auto rom = minimal_game_boy_rom();
        rom[0x0143] = 0x80;
        return rom;
    }

    RomImage image;
    std::unique_ptr<Cartridge> cartridge;
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
    speed.tick_peripheral(67'108);
    check(!speed.double_speed(), "vitesse commutée trop tôt");
    speed.tick_peripheral(1);
    check(speed.double_speed(), "double vitesse non activée");
    check((speed.read_key1() & 0x80) != 0, "KEY1 ne reflète pas la double vitesse");
}

void infrared_test() {
    InfraredPort ir(true);
    ir.write(0xc1);
    check(ir.led_on(), "LED IR non activée");
    check(ir.receiver_enabled(), "récepteur IR non activé");
    check((ir.read() & 0x02) != 0, "absence de lumière IR mal lue");
    ir.set_light_detected(true);
    check((ir.read() & 0x02) == 0, "lumière IR détectée mal lue");

    InfraredPort dmg(false);
    dmg.write(0xc1);
    check(dmg.read() == 0xff, "RP doit être inerte en mode DMG");
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
}

void oam_dma_timing_test() {
    Fixture f;
    f.bus.write(0xc000, 0xa7);
    f.bus.write(0xff46, 0xc0);
    check(f.ppu.oam[0] == 0, "OAM DMA ne doit plus copier instantanément");
    f.bus.tick(3, 3);
    check(f.ppu.oam[0] == 0, "OAM DMA a transféré avant 4 cycles CPU");
    f.bus.tick(1, 1);
    check(f.ppu.oam[0] == 0xa7, "premier octet OAM DMA absent");

    for (int i = 1; i < 0xa0; ++i) f.bus.tick(4, 4);
    check(!f.bus.dma_active(), "OAM DMA reste actif après 160 octets");

    f.bus.write(0xff46, 0xf0); // source echo/haute invalide sur CGB
    f.bus.tick(4, 4);
    check(f.ppu.oam[0] == 0xff, "source OAM DMA F000 ne doit pas être traitée comme WRAM");
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
}

} // namespace ravenemu::cgb::testing

int main() {
    using namespace ravenemu::cgb::testing;
    speed_switch_test();
    infrared_test();
    serial_fast_clock_test();
    oam_dma_timing_test();
    vram_dma_timing_test();

    auto rom = minimal_game_boy_rom();
    rom[0x0143] = 0x80;
    auto core = ravenemu::gbc::make_core();
    core->load_rom(rom, {});
    check(core->framebuffer_format() == ravenemu::FramebufferFormat::argb_8888,
          "frontière GBC n'active pas le framebuffer couleur");
    return 0;
}
