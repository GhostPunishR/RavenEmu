#include "apu/apu.hpp"
#include "check.hpp"

using ravenemu::testing::check;

namespace ravenemu::cgb::testing {

void hle_post_boot_register_test() {
    Apu apu(gb::HardwareMode::cgb_native);
    apu.initialize_hle_post_boot();
    check(apu.read(0xff10) == 0x80 && apu.read(0xff11) == 0xbf &&
          apu.read(0xff12) == 0xf3 && apu.read(0xff24) == 0x77 &&
          apu.read(0xff25) == 0xf3 && apu.read(0xff26) == 0xf1,
          "registres APU HLE post-boot différents de l'état observable à $0100");
}

void wave_ram_access_test() {
    Apu dmg(gb::HardwareMode::dmg);
    dmg.write(0xff30, 0x12);
    dmg.write(0xff1a, 0x80);
    dmg.write(0xff1d, 0xfe);
    dmg.write(0xff1e, 0x87); // période 2046, trigger
    check(dmg.read(0xff30) == 0xff,
          "wave RAM DMG accessible hors de la fenêtre de lecture du canal");
    dmg.tick(4);
    check(dmg.read(0xff3f) == 0x12,
          "accès wave RAM DMG actif non redirigé vers l'octet courant");
    dmg.write(0xff3f, 0x34);
    dmg.tick(2);
    check(dmg.read(0xff30) == 0xff,
          "fenêtre d'accès wave RAM DMG reste ouverte plus de deux cycles");
    dmg.write(0xff1a, 0x00);
    check(dmg.read(0xff30) == 0x34,
          "écriture redirigée dans l'octet wave RAM courant absente");

    Apu cgb(gb::HardwareMode::cgb_native);
    cgb.write(0xff30, 0x12);
    cgb.write(0xff1a, 0x80);
    cgb.write(0xff1d, 0xfe);
    cgb.write(0xff1e, 0x87);
    cgb.write(0xff3f, 0x56);
    check(cgb.read(0xff30) == 0x56 && cgb.read(0xff3f) == 0x56,
          "accès wave RAM CGB actif non redirigé vers l'octet courant");
    cgb.write(0xff1a, 0x00);
    check(cgb.read(0xff30) == 0x56 && cgb.read(0xff3f) == 0x00,
          "accès wave RAM CGB non redevenu direct après arrêt du canal");
}

void sweep_negate_clear_test() {
    Apu apu(gb::HardwareMode::dmg);
    apu.write(0xff10, 0x09); // calcul négatif, shift 1
    apu.write(0xff11, 0x80);
    apu.write(0xff12, 0xf0);
    apu.write(0xff13, 0xe8); // fréquence 1000
    apu.write(0xff14, 0x83);
    check((apu.read(0xff26) & 1) != 0, "canal 1 non déclenché pour le test sweep");
    apu.write(0xff10, 0x01); // retire negate après un calcul négatif
    check((apu.read(0xff26) & 1) == 0,
          "retrait du bit negate après calcul sweep n'a pas coupé le canal 1");
}

void powered_off_length_counter_test() {
    Apu dmg(gb::HardwareMode::dmg);
    dmg.write(0xff26, 0x00);
    dmg.write(0xff11, 0x3f); // compteur longueur = 1, encore câblé sur DMG
    dmg.write(0xff26, 0x80);
    dmg.write(0xff12, 0xf0);
    dmg.write(0xff14, 0xc0);
    check((dmg.read(0xff26) & 1) != 0, "canal DMG non déclenché après écriture longueur NR52 off");
    dmg.tick(8192);
    check((dmg.read(0xff26) & 1) == 0,
          "compteur longueur écrit NR52 off sur DMG n'a pas expiré au premier clock");

    Apu cgb(gb::HardwareMode::cgb_native);
    cgb.write(0xff26, 0x00);
    cgb.write(0xff11, 0x3f); // doit être ignoré sur CGB
    cgb.write(0xff26, 0x80);
    cgb.write(0xff12, 0xf0);
    cgb.write(0xff14, 0xc0);
    cgb.tick(8192);
    check((cgb.read(0xff26) & 1) != 0,
          "CGB a accepté une écriture de longueur alors que NR52 était coupé");
}

void apu_state_mid_wave_access_test() {
    Apu source(gb::HardwareMode::dmg);
    source.write(0xff30, 0xab);
    source.write(0xff1a, 0x80);
    source.write(0xff1d, 0xfe);
    source.write(0xff1e, 0x87);
    source.tick(4);

    detail::BinaryWriter writer;
    source.save(writer);
    const auto state = std::move(writer).take();
    Apu restored(gb::HardwareMode::dmg);
    detail::BinaryReader reader(state);
    restored.load(reader);
    check(reader.exhausted() && restored.read(0xff3f) == 0xab,
          "fenêtre wave RAM APU non restaurée par save state");
    detail::BinaryWriter restored_writer;
    restored.save(restored_writer);
    check(std::move(restored_writer).take() == state,
          "accumulateurs audio APU perdus immédiatement après restauration");
    source.tick(2);
    restored.tick(2);
    check(source.read(0xff30) == restored.read(0xff30),
          "timing APU divergent après restauration en lecture wave RAM");
}

} // namespace ravenemu::cgb::testing

int main() {
    using namespace ravenemu::cgb::testing;
    hle_post_boot_register_test();
    wave_ram_access_test();
    sweep_negate_clear_test();
    powered_off_length_counter_test();
    apu_state_mid_wave_access_test();
    return 0;
}
