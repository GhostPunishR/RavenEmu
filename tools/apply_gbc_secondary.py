from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).parent.mkdir(parents=True, exist_ok=True)
    (ROOT / path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)

# Contrat natif : état rumble générique, sans dépendance Android.
path = "cores/common/include/ravenemu/core.hpp"
text = read(path)
text = replace_once(
    text,
    "    virtual std::size_t read_audio(std::span<std::int16_t> destination) = 0;\n\n    [[nodiscard]] virtual bool has_battery_ram() const noexcept = 0;",
    "    virtual std::size_t read_audio(std::span<std::int16_t> destination) = 0;\n    [[nodiscard]] virtual bool rumble_active() const noexcept { return false; }\n\n    [[nodiscard]] virtual bool has_battery_ram() const noexcept = 0;",
    "Core rumble",
)
write(path, text)

# Cartouche : capacité optionnelle.
path = "cores/gb/src/cartridge/cartridge.hpp"
text = read(path)
text = replace_once(
    text,
    "    virtual void tick(int) {}\n    virtual void save_state(BinaryWriter& out) const = 0;",
    "    virtual void tick(int) {}\n    [[nodiscard]] virtual bool rumble_active() const noexcept { return false; }\n    virtual void save_state(BinaryWriter& out) const = 0;",
    "Cartridge rumble",
)
write(path, text)

# MBC5 : bit 3 du registre 4000-5FFF commande le moteur sur les variantes rumble.
path = "cores/gb/src/cartridge/mbc5.hpp"
text = read(path)
text = replace_once(
    text,
    "        else if (address <= 0x3fff) rom_bank_high_ = value & 1;\n        else if (address <= 0x5fff) ram_bank_ = value & (has_rumble_ ? 7 : 0x0f);",
    "        else if (address <= 0x3fff) rom_bank_high_ = value & 1;\n        else if (address <= 0x5fff) {\n            if (has_rumble_) {\n                rumble_active_ = (value & 0x08) != 0;\n                ram_bank_ = value & 0x07;\n            } else {\n                ram_bank_ = value & 0x0f;\n            }\n        }",
    "MBC5 rumble write",
)
text = replace_once(
    text,
    "    int read_ram(int address) const override {",
    "    [[nodiscard]] bool rumble_active() const noexcept override { return has_rumble_ && rumble_active_; }\n    int read_ram(int address) const override {",
    "MBC5 rumble getter",
)
text = replace_once(
    text,
    "        out.i32(ram_bank_); out.raw(ram_);",
    "        out.i32(ram_bank_); out.boolean(rumble_active_); out.raw(ram_);",
    "MBC5 save rumble",
)
text = replace_once(
    text,
    "        ram_bank_ = in.i32(); in.raw(ram_);",
    "        ram_bank_ = in.i32(); rumble_active_ = in.boolean(); in.raw(ram_);",
    "MBC5 load rumble",
)
text = replace_once(
    text,
    "    bool has_rumble_{};",
    "    bool has_rumble_{};\n    bool rumble_active_{};",
    "MBC5 rumble field",
)
write(path, text)

# Le cœur GB expose l'état du moteur de vibration.
path = "cores/gb/src/core.cpp"
text = read(path)
text = replace_once(
    text,
    "    std::size_t read_audio(std::span<std::int16_t> destination) override {\n        return machine_ ? machine_->apu.read_samples(destination) : 0;\n    }\n    [[nodiscard]] bool has_battery_ram() const noexcept override {",
    "    std::size_t read_audio(std::span<std::int16_t> destination) override {\n        return machine_ ? machine_->apu.read_samples(destination) : 0;\n    }\n    [[nodiscard]] bool rumble_active() const noexcept override {\n        return machine_ != nullptr && machine_->cartridge->rumble_active();\n    }\n    [[nodiscard]] bool has_battery_ram() const noexcept override {",
    "GameBoyCore rumble",
)
write(path, text)

# cores/gbc devient une vraie bibliothèque compilée (factory hors header).
write(
    "cores/gbc/include/ravenemu/gbc/core.hpp",
    '''#pragma once\n#include <ravenemu/core.hpp>\n\nnamespace ravenemu::gbc {\n/** Cœur GBC public. L'identité persistée reste GAME_BOY tant que GB/GBC partagent\n * le contrat de bibliothèque, mais la cible et les composants CGB sont compilés\n * séparément afin de poursuivre l'extraction sans duplication. */\n[[nodiscard]] std::unique_ptr<Core> make_core();\n}\n''',
)
write(
    "cores/gbc/src/core.cpp",
    '''#include <ravenemu/gbc/core.hpp>\n\nnamespace ravenemu::gbc {\nstd::unique_ptr<Core> make_core() {\n    return make_game_boy_core();\n}\n} // namespace ravenemu::gbc\n''',
)
write(
    "cores/gbc/CMakeLists.txt",
    '''add_library(gbc_raven_core STATIC src/core.cpp)\ntarget_include_directories(gbc_raven_core PUBLIC include)\ntarget_link_libraries(gbc_raven_core PUBLIC gb_raven_core ravenemu_common)\nset_target_properties(gbc_raven_core PROPERTIES POSITION_INDEPENDENT_CODE ON)\n\nif(RAVENEMU_BUILD_TESTS)\n    add_executable(gbc_raven_core_tests tests/gbc_hardware_test.cpp)\n    target_include_directories(gbc_raven_core_tests PRIVATE\n        ${CMAKE_CURRENT_LIST_DIR}/../gb/src\n        ${CMAKE_CURRENT_LIST_DIR}/../common/tests/support\n    )\n    target_link_libraries(gbc_raven_core_tests PRIVATE gbc_raven_core)\n    add_test(NAME gbc_raven_core COMMAND gbc_raven_core_tests)\nendif()\n''',
)

# Renforcer les tests natifs avec PPU timing et rumble.
path = "cores/gbc/tests/gbc_hardware_test.cpp"
text = read(path)
insert = r'''
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

    ppu.tick(178);
    check((ppu.read_stat() & 3) == Ppu::mode_transfer, "mode 3 avec SCX fin trop court");
    ppu.tick(1);
    check((ppu.read_stat() & 3) == Ppu::mode_hblank, "PPU ne termine pas le transfert pixel au bon moment");
    ppu.write_lcdc(0x11);
    check(ppu.read_bcpd() == 0x12, "écriture CRAM pendant mode 3 n'a pas été bloquée");
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
'''
needle = "void oam_dma_timing_test() {"
if text.count(needle) != 1:
    raise RuntimeError("point d'insertion tests GBC introuvable")
text = text.replace(needle, insert + "\n" + needle, 1)
text = replace_once(
    text,
    "    serial_fast_clock_test();\n    oam_dma_timing_test();",
    "    serial_fast_clock_test();\n    ppu_dot_timing_test();\n    mbc5_rumble_test();\n    oam_dma_timing_test();",
    "appel tests secondaires",
)
write(path, text)

print("GBC secondary refactor applied")
