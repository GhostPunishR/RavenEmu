#include "ppu/ppu.hpp"
#include "check.hpp"
#include <ravenemu/binary_io.hpp>

using ravenemu::testing::check;

namespace ravenemu::cgb::testing {

std::vector<std::uint8_t> snapshot(const Ppu& ppu);
void check_snapshot_equal(const std::vector<std::uint8_t>& expected,
                          const std::vector<std::uint8_t>& actual,
                          std::string_view message);

int enter_and_measure_mode3(Ppu& ppu) {
    while (ppu.mode() != Ppu::mode_transfer) ppu.tick(1);
    const int start = ppu.line_dot();
    while (ppu.mode() == Ppu::mode_transfer) ppu.tick(1);
    return ppu.line_dot() - start;
}

void fifo_baseline_and_scx_test() {
    for (int scx = 0; scx < 8; ++scx) {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_lcdc(0x11);
        ppu.set_scx(scx);
        ppu.write_lcdc(0x91);
        const int length = enter_and_measure_mode3(ppu);
        check(length == 172 + scx, "durée mode 3 incorrecte pour la pénalité SCX fine");
    }
}

void window_restart_and_line_counter_test() {
    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_lcdc(0x11);
        ppu.set_wy(0);
        ppu.set_wx(7);
        ppu.write_lcdc(0xb1); // LCD + BG + window
        check(enter_and_measure_mode3(ppu) == 178, "restart du fetcher window différent de six dots");
        check(ppu.window_line() == 1, "compteur interne window non incrémenté après affichage");
    }

    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_lcdc(0x11);
        ppu.set_wy(0);
        ppu.set_wx(167); // invisible
        ppu.write_lcdc(0xb1);
        check(enter_and_measure_mode3(ppu) == 172, "window invisible a allongé le mode 3");
        check(ppu.window_line() == 0, "compteur window incrémenté sans rendu");
    }

    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_lcdc(0x11);
        ppu.set_scx(3);
        ppu.set_wy(0);
        ppu.set_wx(0);
        ppu.write_lcdc(0xb1);
        const int wx_zero_length = enter_and_measure_mode3(ppu);
        check(wx_zero_length == 180,
              "cas WX=0/SCX fin n'applique pas le décalage et le raccourcissement documentés");
    }
}

void mid_scanline_wx_glitch_test() {
    for (const auto hardware_mode : {gb::HardwareMode::dmg,
                                     gb::HardwareMode::cgb_compatibility,
                                     gb::HardwareMode::cgb_native}) {
        InterruptController interrupts;
        Ppu ppu(interrupts, hardware_mode);
        ppu.write_lcdc(0x11);
        ppu.set_bgp(0xe4);
        if (hardware_mode == gb::HardwareMode::cgb_compatibility) {
            ppu.initialize_hle_compatibility_palettes();
        } else if (hardware_mode == gb::HardwareMode::cgb_native) {
            // Palette BG 0 : couleur 0 noire, couleur 1 rouge.
            ppu.write_bcps(0); ppu.write_bcpd(0x00);
            ppu.write_bcps(1); ppu.write_bcpd(0x00);
            ppu.write_bcps(2); ppu.write_bcpd(0x1f);
            ppu.write_bcps(3); ppu.write_bcpd(0x00);
        }
        for (int row = 0; row < 8; ++row) {
            ppu.vram[static_cast<std::size_t>(row * 2)] = 0xff; // tuile 0, couleur 1
        }
        ppu.set_wy(0);
        ppu.set_wx(7);
        ppu.write_lcdc(0xb1);
        while (ppu.mode() != Ppu::mode_transfer || ppu.transfer_x() < 8) ppu.tick(1);
        ppu.set_wx(31); // nouvelle coïncidence à X=24, après le démarrage window

        const auto armed_state = snapshot(ppu);
        InterruptController restored_interrupts;
        Ppu restored(restored_interrupts, hardware_mode);
        detail::BinaryReader reader(armed_state);
        restored.load(reader);
        check(reader.exhausted(), "état PPU avec glitch WX armé laisse des données");

        while (ppu.mode() != Ppu::mode_vblank) ppu.tick(1);
        while (restored.mode() != Ppu::mode_vblank) restored.tick(1);
        check_snapshot_equal(snapshot(ppu), snapshot(restored),
                             "glitch WX divergent après restauration en mode 3");

        const auto regular_pixel = ppu.completed_frame[0];
        int injected_pixels{};
        for (int x = 0; x < Ppu::width; ++x) {
            if (ppu.completed_frame[static_cast<std::size_t>(x)] != regular_pixel) ++injected_pixels;
        }
        check(injected_pixels == 1,
              "changement WX après démarrage window n'a pas injecté exactement un pixel FIFO");
    }
}

void mid_scanline_scx_tile_fetch_test() {
    InterruptController reference_interrupts;
    InterruptController changed_interrupts;
    Ppu reference(reference_interrupts, gb::HardwareMode::dmg);
    Ppu changed(changed_interrupts, gb::HardwareMode::dmg);
    const auto prepare = [](Ppu& ppu) {
        ppu.write_lcdc(0x11);
        ppu.set_bgp(0xe4);
        for (int row = 0; row < 8; ++row) {
            ppu.vram[static_cast<std::size_t>(row * 2)] = 0xff;      // tuile 0, couleur 1
            ppu.vram[static_cast<std::size_t>(16 + row * 2 + 1)] = 0xff; // tuile 1, couleur 2
        }
        for (int tile = 0; tile < 32; ++tile) {
            ppu.vram[static_cast<std::size_t>(0x1800 + tile)] =
                static_cast<std::uint8_t>(tile & 1);
        }
        ppu.write_lcdc(0x91);
    };
    prepare(reference);
    prepare(changed);
    while (changed.mode() != Ppu::mode_transfer || changed.transfer_x() < 24) {
        reference.tick(1);
        changed.tick(1);
    }
    changed.set_scx(8); // conserve le décalage fin, décale les prochains Get Tile
    while (changed.mode() != Ppu::mode_vblank) {
        reference.tick(1);
        changed.tick(1);
    }

    for (int x = 0; x < 24; ++x) {
        check(reference.completed_frame[static_cast<std::size_t>(x)] ==
                  changed.completed_frame[static_cast<std::size_t>(x)],
              "écriture SCX mode 3 a modifié des pixels déjà sortis de la FIFO");
    }
    bool future_tile_changed{};
    for (int x = 24; x < Ppu::width; ++x) {
        future_tile_changed = future_tile_changed ||
            reference.completed_frame[static_cast<std::size_t>(x)] !=
            changed.completed_frame[static_cast<std::size_t>(x)];
    }
    check(future_tile_changed,
          "fetcher BG n'a pas relu les bits de tuile SCX après une écriture mode 3");
}

void sprite_penalty_test() {
    InterruptController interrupts;
    Ppu ppu(interrupts, gb::HardwareMode::dmg);
    ppu.write_oam_direct(0, 16); // ligne 0
    ppu.write_oam_direct(1, 8);  // pixel écran 0
    ppu.write_oam_direct(2, 0);
    ppu.write_oam_direct(3, 0);
    ppu.write_lcdc(0x93); // LCD + BG + OBJ, sans redémarrage LCD

    check(enter_and_measure_mode3(ppu) == 179,
          "premier OBJ au bord gauche ne recouvre pas le fetch BG initial");
}

int measure_mode3_after_mode2(Ppu& ppu, int remaining_mode2_dots) {
    ppu.tick(remaining_mode2_dots);
    while (ppu.mode() != Ppu::mode_transfer) ppu.tick(1);
    check(ppu.mode() == Ppu::mode_transfer, "PPU absent du mode 3 après le scan OAM");
    const int start = ppu.line_dot();
    while (ppu.mode() == Ppu::mode_transfer) ppu.tick(1);
    return ppu.line_dot() - start;
}

void progressive_oam_scan_test() {
    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_oam_direct(0, 0);  // hors ligne avant son créneau de scan
        ppu.write_oam_direct(1, 8);
        ppu.write_lcdc(0x93);
        ppu.tick(1);
        ppu.write_oam_direct(0, 16); // DMA simulé avant le deuxième dot
        check(measure_mode3_after_mode2(ppu, 79) == 179,
              "modification OAM avant le créneau mode 2 non observée");
    }

    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.write_oam_direct(0, 0);
        ppu.write_oam_direct(1, 8);
        ppu.write_lcdc(0x93);
        ppu.tick(2);                 // l'entrée 0 est déjà rejetée
        ppu.write_oam_direct(0, 16); // DMA simulé après son créneau
        check(measure_mode3_after_mode2(ppu, 78) == 172,
              "modification OAM tardive a rétroactivement changé le scan mode 2");
    }
}

void mid_oam_scan_save_state_test() {
    InterruptController source_interrupts;
    Ppu source(source_interrupts, gb::HardwareMode::dmg);
    source.write_oam_direct(0, 16);
    source.write_oam_direct(1, 8);
    source.write_oam_direct(20 * 4, 16);
    source.write_oam_direct(20 * 4 + 1, 16);
    source.write_lcdc(0x93);
    source.tick(21); // dix entrées inspectées, entrée 20 encore à venir

    const auto saved = snapshot(source);
    InterruptController restored_interrupts;
    Ppu restored(restored_interrupts, gb::HardwareMode::dmg);
    detail::BinaryReader reader(saved);
    restored.load(reader);
    check(reader.exhausted(), "état PPU mode 2 laisse des données non consommées");

    source.tick(59);
    restored.tick(59);
    check_snapshot_equal(snapshot(source), snapshot(restored),
                         "restauration en milieu de scan OAM non déterministe");
}

void stat_line_edge_test() {
    InterruptController interrupts;
    interrupts.flags = 0;
    Ppu ppu(interrupts, gb::HardwareMode::cgb_native);
    ppu.write_stat(0x20); // IRQ mode 2
    ppu.write_lcdc(0x91);
    check((interrupts.flags & interrupt_mask(Interrupt::stat)) != 0,
          "front montant de la ligne STAT mode 2 absent");
    interrupts.flags = 0;
    ppu.write_stat(0x60); // ajoute LYC alors que la ligne STAT est déjà haute
    check((interrupts.flags & interrupt_mask(Interrupt::stat)) == 0,
          "IRQ STAT redemandée sans nouveau front de ligne");
}

void mode2_stat_lead_test() {
    {
        InterruptController interrupts;
        interrupts.flags = 0;
        Ppu ppu(interrupts, gb::HardwareMode::dmg);
        ppu.tick(252); // HBlank de la ligne 0.
        ppu.write_stat(0x20);
        interrupts.flags = 0;
        ppu.tick(199);
        check((interrupts.flags & interrupt_mask(Interrupt::stat)) == 0,
              "IRQ STAT mode 2 levee avant le dot 452");
        ppu.tick(1);
        check(ppu.mode() == Ppu::mode_hblank && ppu.line_dot() == 452 &&
              (interrupts.flags & interrupt_mask(Interrupt::stat)) != 0,
              "signal STAT mode 2 absent quatre dots avant la ligne suivante");
    }

    {
        InterruptController interrupts;
        Ppu ppu(interrupts, gb::HardwareMode::cgb_compatibility);
        ppu.write_stat(0x20);
        ppu.tick(143 * 456 + 451);
        interrupts.flags = 0;
        ppu.tick(1);
        check(ppu.ly() == 143 && ppu.line_dot() == 452 &&
              (interrupts.flags & interrupt_mask(Interrupt::stat)) != 0,
              "CGB n'anticipe pas le front STAT mode 2 avant VBlank");
    }
}

void lcd_off_lyc_latch_test() {
    InterruptController interrupts;
    Ppu ppu(interrupts, gb::HardwareMode::dmg);
    ppu.write_lcdc(0x11);
    ppu.write_lcdc(0x91);
    ppu.tick(456 * 144);
    ppu.write_lyc(144);
    check((ppu.read_stat() & 4) != 0, "précondition LY=LYC absente");
    ppu.write_lcdc(0x00);
    ppu.write_lyc(1);
    check((ppu.read_stat() & 4) != 0,
          "comparateur LY=LYC modifié alors que son horloge LCD est coupée");
    ppu.write_lcdc(0x80);
    check((ppu.read_stat() & 4) == 0,
          "comparateur LY=LYC non réévalué à l'activation LCD");
}

void ly_153_quirk_test() {
    InterruptController interrupts;
    Ppu ppu(interrupts, gb::HardwareMode::dmg);
    ppu.write_lcdc(0x11);
    ppu.write_lcdc(0x91);
    ppu.tick(452 + 456 * 152);
    check(ppu.ly() == 153 && ppu.line_dot() == 0,
          "PPU n'entre pas sur la ligne interne 153 au bon dot");
    ppu.write_lyc(0);
    ppu.write_stat(0x40);
    interrupts.flags = 0;
    ppu.tick(3);
    check(ppu.ly() == 153, "LY passe à zéro avant le dot 4 de la ligne 153");
    ppu.tick(1);
    check(ppu.ly() == 0, "LY ne présente pas zéro au dot 4 de la ligne 153");
    check((ppu.read_stat() & 4) != 0 &&
          (interrupts.flags & interrupt_mask(Interrupt::stat)) != 0,
          "coïncidence LYC/IRQ STAT absente lors du changement LY 153→0");
}

void dmg_stat_write_glitch_test() {
    InterruptController dmg_interrupts;
    Ppu dmg(dmg_interrupts, gb::HardwareMode::dmg);
    dmg.write_lcdc(0x11);
    dmg.write_lcdc(0x91); // mode 2, aucune source STAT activée
    dmg_interrupts.flags = 0;
    dmg.write_stat(0x00);
    check((dmg_interrupts.flags & interrupt_mask(Interrupt::stat)) != 0,
          "glitch IRQ d'écriture STAT absent sur DMG en mode 2");

    InterruptController cgb_interrupts;
    Ppu cgb(cgb_interrupts, gb::HardwareMode::cgb_native);
    cgb.write_lcdc(0x11);
    cgb.write_lcdc(0x91);
    cgb_interrupts.flags = 0;
    cgb.write_stat(0x00);
    check((cgb_interrupts.flags & interrupt_mask(Interrupt::stat)) == 0,
          "glitch d'écriture STAT DMG appliqué à tort au CGB");
}

std::int32_t overlapping_sprite_pixel(
    bool dmg_priority,
    bool first_transparent = false,
    gb::HardwareMode mode = gb::HardwareMode::cgb_native
) {
    InterruptController interrupts;
    Ppu ppu(interrupts, mode);
    ppu.write_lcdc(0x11);

    // OBJ palette 0: couleur 1 rouge, couleur 2 verte.
    ppu.write_ocps(2); ppu.write_ocpd(0x1f);
    ppu.write_ocps(3); ppu.write_ocpd(0x00);
    ppu.write_ocps(4); ppu.write_ocpd(0xe0);
    ppu.write_ocps(5); ppu.write_ocpd(0x03);
    ppu.set_obp0(0xe4); // mapping DMG identité pour le mode compatible

    // OAM 0 commence en X=2 et gagne en mode CGB; OAM 1 commence en X=0
    // et gagne par coordonnée X en mode DMG. Ils se recouvrent au pixel 2.
    // La première ligne après LCDC.7 saute le scan OAM : placer les objets
    // sur LY=1 isole ici la priorité OPRI du timing d'activation LCD.
    ppu.write_oam_direct(0, 17); ppu.write_oam_direct(1, 10);
    ppu.write_oam_direct(2, 0); ppu.write_oam_direct(3, 0);
    ppu.write_oam_direct(4, 17); ppu.write_oam_direct(5, 8);
    ppu.write_oam_direct(6, 1); ppu.write_oam_direct(7, 0);
    if (!first_transparent) ppu.vram[0] = 0x80; // tile 0, pixel 0 = couleur 1
    ppu.vram[16 + 1] = 0x20;                   // tile 1, pixel 2 = couleur 2

    ppu.write_opri(dmg_priority ? 1 : 0);
    ppu.write_lcdc(0x93);
    ppu.tick(456 * 144);
    return ppu.completed_frame[Ppu::width + 2];
}

void opri_render_priority_test() {
    check(overlapping_sprite_pixel(false) == static_cast<std::int32_t>(0xffff0000U),
          "OPRI=0 n'accorde pas la priorité à l'index OAM le plus bas");
    check(overlapping_sprite_pixel(true) == static_cast<std::int32_t>(0xff00ff00U),
          "OPRI=1 n'accorde pas la priorité à la plus petite coordonnée X");
    check(overlapping_sprite_pixel(false, true) == static_cast<std::int32_t>(0xff00ff00U),
          "OBJ transparent prioritaire masque à tort un OBJ opaque inférieur");
    check(overlapping_sprite_pixel(false, false, gb::HardwareMode::cgb_compatibility) ==
              static_cast<std::int32_t>(0xff00ff00U),
          "mode compatible CGB n'applique pas la priorité X programmée par la boot ROM");
}

std::vector<std::uint8_t> snapshot(const Ppu& ppu) {
    detail::BinaryWriter writer;
    ppu.save(writer);
    return std::move(writer).take();
}

void check_snapshot_equal(const std::vector<std::uint8_t>& expected,
                          const std::vector<std::uint8_t>& actual,
                          std::string_view message) {
    check(expected.size() == actual.size(), "taille d'état PPU différente après restauration");
    for (std::size_t index = 0; index < expected.size(); ++index) {
        if (expected[index] != actual[index]) {
            throw std::runtime_error(std::string{message} + " (octet " + std::to_string(index) + ")");
        }
    }
}

void mid_transfer_save_state_test() {
    InterruptController source_interrupts;
    Ppu source(source_interrupts, gb::HardwareMode::cgb_native);
    source.write_lcdc(0x11);
    source.set_scx(5);
    source.set_wy(0);
    source.set_wx(23);
    source.write_oam_direct(0, 16);
    source.write_oam_direct(1, 32);
    source.write_lcdc(0xb3);
    source.tick(111);
    check(source.mode() == Ppu::mode_transfer,
          "point de sauvegarde PPU absent du milieu du mode 3");

    const auto saved = snapshot(source);
    InterruptController restored_interrupts;
    Ppu restored(restored_interrupts, gb::HardwareMode::cgb_native);
    detail::BinaryReader reader(saved);
    restored.load(reader);
    check(reader.exhausted(), "état PPU laisse des données non consommées");
    check_snapshot_equal(saved, snapshot(restored),
                         "état PPU différent immédiatement après restauration");

    source.tick(1000);
    restored.tick(1000);
    check_snapshot_equal(snapshot(source), snapshot(restored),
                         "restauration en milieu de fetch/FIFO PPU non déterministe");
}

} // namespace ravenemu::cgb::testing

int main() {
    using namespace ravenemu::cgb::testing;
    fifo_baseline_and_scx_test();
    window_restart_and_line_counter_test();
    mid_scanline_wx_glitch_test();
    mid_scanline_scx_tile_fetch_test();
    sprite_penalty_test();
    progressive_oam_scan_test();
    mid_oam_scan_save_state_test();
    stat_line_edge_test();
    mode2_stat_lead_test();
    lcd_off_lyc_latch_test();
    ly_153_quirk_test();
    dmg_stat_write_glitch_test();
    opri_render_priority_test();
    mid_transfer_save_state_test();
    return 0;
}
