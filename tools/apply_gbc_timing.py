from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)


# CPU: STOP réel + réveil joypad.
cpu_path = "cores/gb/src/cpu/cpu.hpp"
cpu = read(cpu_path)
cpu = replace_once(
    cpu,
    "    int step() {\n        if (locked) return 4;",
    "    int step() {\n        if (stopped) {\n            if (bus_.stop_wake_requested()) stopped = false;\n            else return 0;\n        }\n        if (locked) return 4;",
    "CPU step STOP",
)
cpu = replace_once(
    cpu,
    "        out.boolean(ime); out.boolean(halted); out.boolean(ei_pending); out.boolean(halt_bug); out.boolean(locked);",
    "        out.boolean(ime); out.boolean(halted); out.boolean(ei_pending); out.boolean(halt_bug); out.boolean(locked); out.boolean(stopped);",
    "CPU save STOP",
)
cpu = replace_once(
    cpu,
    "        ime = in.boolean(); halted = in.boolean(); ei_pending = in.boolean(); halt_bug = in.boolean(); locked = in.boolean();",
    "        ime = in.boolean(); halted = in.boolean(); ei_pending = in.boolean(); halt_bug = in.boolean(); locked = in.boolean(); stopped = in.boolean();",
    "CPU load STOP",
)
cpu = replace_once(
    cpu,
    "    bool ime{}; bool halted{}; bool locked{}; bool ei_pending{}; bool halt_bug{};",
    "    bool ime{}; bool halted{}; bool locked{}; bool ei_pending{}; bool halt_bug{}; bool stopped{};",
    "CPU field STOP",
)
cpu = replace_once(
    cpu,
    "        case 0x10: static_cast<void>(fetch_byte()); bus_.on_stop(); return 4; case 0x11: set_de(fetch_word()); return 12;",
    "        case 0x10: static_cast<void>(fetch_byte()); if (!bus_.on_stop()) stopped = true; return 4; case 0x11: set_de(fetch_word()); return 12;",
    "CPU opcode STOP",
)
write(cpu_path, cpu)

# Boucle machine : l'ordonnanceur Machine avance déjà tous les périphériques.
core_path = "cores/gb/src/core.cpp"
core = read(core_path)
core = replace_once(
    core,
    "            const int consumed = machine_->cpu.step(); machine_->tick(consumed);\n            ppu_cycles += consumed >> machine_->speed.peripheral_shift();",
    "            const int advanced = machine_->step();\n            if (advanced <= 0) break;\n            ppu_cycles += advanced;",
    "core run_frame",
)
core = replace_once(core, "out.u16(4);", "out.u16(5);", "save-state version write")
core = replace_once(
    core,
    "const auto version = in.u16(); if (version != 4) throw SaveStateError(\"Version d'état non prise en charge\");",
    "const auto version = in.u16(); if (version != 5) throw SaveStateError(\"Version d'état non prise en charge\");",
    "save-state version read",
)
write(core_path, core)

# PPU : accès CRAM interdit pendant mode 3 + pipeline de pixels cadencé par dot.
ppu_path = "cores/gb/src/ppu/ppu.hpp"
ppu = read(ppu_path)
ppu = replace_once(
    ppu,
    "    void write_bcpd(int value) noexcept {\n        bg_cram[static_cast<std::size_t>(bcps_index_)] = static_cast<std::uint8_t>(value);\n        recompute_argb(bg_cram, bg_argb_, bcps_index_);\n        if (bcps_auto_) bcps_index_ = (bcps_index_ + 1) & 0x3f;\n    }\n    [[nodiscard]] int read_bcpd() const noexcept { return bg_cram[static_cast<std::size_t>(bcps_index_)]; }",
    "    void write_bcpd(int value) noexcept {\n        if (lcd_enabled() && mode_ == mode_transfer) return;\n        bg_cram[static_cast<std::size_t>(bcps_index_)] = static_cast<std::uint8_t>(value);\n        recompute_argb(bg_cram, bg_argb_, bcps_index_);\n        if (bcps_auto_) bcps_index_ = (bcps_index_ + 1) & 0x3f;\n    }\n    [[nodiscard]] int read_bcpd() const noexcept {\n        if (lcd_enabled() && mode_ == mode_transfer) return 0xff;\n        return bg_cram[static_cast<std::size_t>(bcps_index_)];\n    }",
    "PPU BCPD mode3",
)
ppu = replace_once(
    ppu,
    "    void write_ocpd(int value) noexcept {\n        obj_cram[static_cast<std::size_t>(ocps_index_)] = static_cast<std::uint8_t>(value);\n        recompute_argb(obj_cram, obj_argb_, ocps_index_);\n        if (ocps_auto_) ocps_index_ = (ocps_index_ + 1) & 0x3f;\n    }\n    [[nodiscard]] int read_ocpd() const noexcept { return obj_cram[static_cast<std::size_t>(ocps_index_)]; }",
    "    void write_ocpd(int value) noexcept {\n        if (lcd_enabled() && mode_ == mode_transfer) return;\n        obj_cram[static_cast<std::size_t>(ocps_index_)] = static_cast<std::uint8_t>(value);\n        recompute_argb(obj_cram, obj_argb_, ocps_index_);\n        if (ocps_auto_) ocps_index_ = (ocps_index_ + 1) & 0x3f;\n    }\n    [[nodiscard]] int read_ocpd() const noexcept {\n        if (lcd_enabled() && mode_ == mode_transfer) return 0xff;\n        return obj_cram[static_cast<std::size_t>(ocps_index_)];\n    }",
    "PPU OCPD mode3",
)
ppu = replace_once(
    ppu,
    "    void tick(int cycles) {\n        if (!lcd_enabled()) return;\n        int remaining = cycles;\n        while (remaining > 0) {\n            const int step = std::min(remaining, next_event_delay());\n            line_dot_ += step; remaining -= step; process_line_position();\n        }\n    }",
    "    void tick(int cycles) {\n        if (!lcd_enabled() || cycles <= 0) return;\n        for (int dot = 0; dot < cycles; ++dot) advance_dot();\n    }",
    "PPU dot tick",
)

pattern = re.compile(
    r"    \[\[nodiscard\]\] int next_event_delay\(\) const noexcept \{.*?\n    void enter_vblank\(\) \{",
    re.S,
)
replacement = r'''    void advance_dot() {
        if (ly_ < height && mode_ == mode_transfer) advance_transfer();

        ++line_dot_;
        if (ly_ < height && line_dot_ == 80 && mode_ == mode_oam) begin_transfer();

        if (line_dot_ < 456) return;
        line_dot_ = 0;
        ++ly_;
        if (ly_ == height) {
            enter_vblank();
        } else if (ly_ > 153) {
            ly_ = 0;
            window_line_ = 0;
            set_mode(mode_oam);
        } else if (ly_ < height) {
            set_mode(mode_oam);
        }
        check_stat();
    }

    void begin_transfer() noexcept {
        transfer_x_ = 0;
        // 12 dots de mise en route du fetcher, puis rejet SCX fin.
        transfer_delay_ = 12 + (scx_ & 7);
        sprite_stall_remaining_ = 0;
        window_started_line_ = false;
        line_sprite_count_ = scan_sprites((lcdc_ & 4) != 0 ? 16 : 8);
        sprite_stall_used_.fill(false);
        set_mode(mode_transfer);
    }

    void advance_transfer() {
        if (transfer_delay_ > 0) {
            --transfer_delay_;
            return;
        }
        if (sprite_stall_remaining_ > 0) {
            --sprite_stall_remaining_;
            return;
        }

        const int window_start = wx_ - 7;
        if (!window_started_line_ && (lcdc_ & 0x20) != 0 && ly_ >= wy_ && wx_ <= 166 &&
            transfer_x_ >= std::max(0, window_start)) {
            window_started_line_ = true;
            // Redémarrage approximatif du fetcher au passage BG -> window.
            transfer_delay_ = 6;
            return;
        }

        for (int i = 0; i < line_sprite_count_; ++i) {
            if (sprite_stall_used_[static_cast<std::size_t>(i)]) continue;
            const int index = sprite_indices_[static_cast<std::size_t>(i)] * 4;
            const int sprite_x = oam[static_cast<std::size_t>(index + 1)] - 8;
            if (std::max(0, sprite_x) == transfer_x_) {
                sprite_stall_used_[static_cast<std::size_t>(i)] = true;
                sprite_stall_remaining_ = 5; // ce dot + 5 = pénalité de 6 dots
                return;
            }
        }

        render_timed_pixel(transfer_x_);
        ++transfer_x_;
        if (transfer_x_ >= width) {
            if (window_started_line_) ++window_line_;
            set_mode(mode_hblank);
            entered_hblank_ = true;
        }
    }

    void enter_vblank() {'''
ppu, n = pattern.subn(replacement, ppu, count=1)
if n != 1:
    raise RuntimeError(f"PPU scheduler: motif trouvé {n} fois")

marker = "    void render_line() {\n"
if ppu.count(marker) != 1:
    raise RuntimeError("PPU render_line marker introuvable")
helpers = r'''    void render_timed_pixel(int x) {
        if (x < 0 || x >= width || ly_ < 0 || ly_ >= height) return;
        const int base = ly_ * width;
        if (cgb_mode_) render_timed_pixel_cgb(base, x);
        else render_timed_pixel_dmg(base, x);
    }

    void render_timed_pixel_dmg(int base, int x) {
        int color = 0;
        if ((lcdc_ & 1) != 0) {
            const bool use_window = window_started_line_ && x >= std::max(0, wx_ - 7);
            const int map = use_window ? ((lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800)
                                       : ((lcdc_ & 8) != 0 ? 0x1c00 : 0x1800);
            const int px = use_window ? x - (wx_ - 7) : ((x + scx_) & 0xff);
            const int py = use_window ? window_line_ : ((ly_ + scy_) & 0xff);
            const int tile = vram_byte(0, map + (py >> 3) * 32 + (px >> 3));
            color = tile_pixel_dmg(tile, py & 7, px & 7);
        }
        bg_color_line_[static_cast<std::size_t>(x)] = color;
        bg_priority_line_[static_cast<std::size_t>(x)] = false;
        working_frame_[static_cast<std::size_t>(base + x)] = (bgp_ >> (color * 2)) & 3;
        render_timed_sprite_dmg(base, x);
    }

    void render_timed_sprite_dmg(int base, int x) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8;
        int best = -1;
        int best_x = 256;
        for (int i = 0; i < line_sprite_count_; ++i) {
            const int sprite = sprite_indices_[static_cast<std::size_t>(i)];
            const int index = sprite * 4;
            const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            if (x < sx || x >= sx + 8) continue;
            const int raw_x = oam[static_cast<std::size_t>(index + 1)];
            if (raw_x < best_x || (raw_x == best_x && (best < 0 || sprite < best))) {
                best = sprite;
                best_x = raw_x;
            }
        }
        if (best < 0) return;
        const int index = best * 4;
        const int sy = oam[static_cast<std::size_t>(index)] - 16;
        const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
        int tile = oam[static_cast<std::size_t>(index + 2)];
        const int attr = oam[static_cast<std::size_t>(index + 3)];
        if (sprite_height == 16) tile &= 0xfe;
        int row = ly_ - sy;
        if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
        const int px = x - sx;
        const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
        const int lo = vram_byte(0, tile * 16 + row * 2);
        const int hi = vram_byte(0, tile * 16 + row * 2 + 1);
        const int obj_color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
        if (obj_color == 0) return;
        if ((attr & 0x80) != 0 && bg_color_line_[static_cast<std::size_t>(x)] != 0) return;
        const int palette = (attr & 0x10) != 0 ? obp1_ : obp0_;
        working_frame_[static_cast<std::size_t>(base + x)] = (palette >> (obj_color * 2)) & 3;
    }

    void render_timed_pixel_cgb(int base, int x) {
        const bool use_window = window_started_line_ && x >= std::max(0, wx_ - 7);
        const int map = use_window ? ((lcdc_ & 0x40) != 0 ? 0x1c00 : 0x1800)
                                   : ((lcdc_ & 8) != 0 ? 0x1c00 : 0x1800);
        const int px = use_window ? x - (wx_ - 7) : ((x + scx_) & 0xff);
        const int py = use_window ? window_line_ : ((ly_ + scy_) & 0xff);
        draw_bg_pixel_cgb(base, x, map, py >> 3, py & 7, px >> 3, px & 7);
        render_timed_sprite_cgb(base, x);
    }

    void render_timed_sprite_cgb(int base, int x) {
        if ((lcdc_ & 2) == 0) return;
        const int sprite_height = (lcdc_ & 4) != 0 ? 16 : 8;
        for (int i = 0; i < line_sprite_count_; ++i) {
            const int sprite = sprite_indices_[static_cast<std::size_t>(i)];
            const int index = sprite * 4;
            const int sy = oam[static_cast<std::size_t>(index)] - 16;
            const int sx = oam[static_cast<std::size_t>(index + 1)] - 8;
            if (x < sx || x >= sx + 8) continue;
            int tile = oam[static_cast<std::size_t>(index + 2)];
            const int attr = oam[static_cast<std::size_t>(index + 3)];
            if (sprite_height == 16) tile &= 0xfe;
            int row = ly_ - sy;
            if ((attr & 0x40) != 0) row = sprite_height - 1 - row;
            const int px = x - sx;
            const int bit_index = (attr & 0x20) != 0 ? px : 7 - px;
            const int bank = (attr >> 3) & 1;
            const int palette = attr & 7;
            const int lo = vram_byte(bank, tile * 16 + row * 2);
            const int hi = vram_byte(bank, tile * 16 + row * 2 + 1);
            const int obj_color = (((hi >> bit_index) & 1) << 1) | ((lo >> bit_index) & 1);
            if (obj_color == 0) continue;
            const bool bg_visible = bg_color_line_[static_cast<std::size_t>(x)] != 0;
            const bool master_priority = (lcdc_ & 1) != 0;
            if (bg_visible && master_priority &&
                (bg_priority_line_[static_cast<std::size_t>(x)] || (attr & 0x80) != 0)) return;
            working_frame_[static_cast<std::size_t>(base + x)] =
                obj_argb_[static_cast<std::size_t>(palette * 4 + obj_color)];
            return; // priorité CGB : premier OBJ dans l'ordre OAM
        }
    }

'''
ppu = ppu.replace(marker, helpers + marker, 1)

# État du pipeline PPU.
ppu = replace_once(
    ppu,
    "        constexpr int field_count = 20;",
    "        constexpr int field_count = 25;",
    "PPU state count save",
)
ppu = replace_once(
    ppu,
    "            bcps_auto_ ? 1 : 0, ocps_index_, ocps_auto_ ? 1 : 0,\n        };",
    "            bcps_auto_ ? 1 : 0, ocps_index_, ocps_auto_ ? 1 : 0,\n            transfer_x_, transfer_delay_, window_started_line_ ? 1 : 0,\n            sprite_stall_remaining_, line_sprite_count_,\n        };",
    "PPU state fields save",
)
ppu = replace_once(
    ppu,
    "        if (in.i32() != 20) throw SaveStateError(\"État instantané corrompu (PPU)\");",
    "        if (in.i32() != 25) throw SaveStateError(\"État instantané corrompu (PPU)\");",
    "PPU state count load",
)
ppu = replace_once(
    ppu,
    "        ocps_auto_ = in.i32() != 0;\n        in.raw(vram); in.raw(oam); in.raw(bg_cram); in.raw(obj_cram); rebuild_color_cache();",
    "        ocps_auto_ = in.i32() != 0; transfer_x_ = in.i32(); transfer_delay_ = in.i32();\n        window_started_line_ = in.i32() != 0; sprite_stall_remaining_ = in.i32(); line_sprite_count_ = in.i32();\n        sprite_stall_used_.fill(false);\n        in.raw(vram); in.raw(oam); in.raw(bg_cram); in.raw(obj_cram); rebuild_color_cache();",
    "PPU state fields load",
)

# Champs pipeline placés près des autres compteurs de ligne.
ppu = replace_once(
    ppu,
    "    int window_line_{};\n    bool stat_line_{};",
    "    int window_line_{};\n    int transfer_x_{};\n    int transfer_delay_{};\n    bool window_started_line_{};\n    int sprite_stall_remaining_{};\n    int line_sprite_count_{};\n    std::array<bool, 10> sprite_stall_used_{};\n    bool stat_line_{};",
    "PPU pipeline fields",
)
write(ppu_path, ppu)

# Tests natifs dédiés GBC.
gbc_cmake = '''# Frontière CGB distincte : le cœur complet partage encore l'implémentation GB,
# mais les composants matériels CGB vivent et sont testés sous cores/gbc.
add_library(gbc_raven_core INTERFACE)
target_include_directories(gbc_raven_core INTERFACE include)
target_link_libraries(gbc_raven_core INTERFACE gb_raven_core)

if(RAVENEMU_BUILD_TESTS)
    add_executable(gbc_raven_core_tests tests/gbc_hardware_test.cpp)
    target_include_directories(gbc_raven_core_tests PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/../gb/src
        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support
    )
    target_link_libraries(gbc_raven_core_tests PRIVATE gbc_raven_core)
    add_test(NAME gbc_raven_core COMMAND gbc_raven_core_tests)
endif()
'''
write("cores/gbc/CMakeLists.txt", gbc_cmake)

test = r'''#include <ravenemu/gbc/core.hpp>
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
'''
write("cores/gbc/tests/gbc_hardware_test.cpp", test)

# L'ancien test de frontière à pointeur non nul n'apporte plus de couverture.
old_boundary = ROOT / "cores/gbc/tests/gbc_boundary_test.cpp"
if old_boundary.exists():
    old_boundary.unlink()

print("GBC timing refactor applied")
