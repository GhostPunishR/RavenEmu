#include <ravenemu/nds/core.hpp>

#include "system/machine.hpp"

#include <cstdint>
#include <optional>
#include <vector>

namespace ravenemu {
namespace nds {

namespace {

/**
 * Message unique du refus d'enregistrer un état.
 *
 * Nommé une fois : le cœur, ses tests et la documentation doivent dire
 * exactement la même chose, et un écart entre eux se lirait comme un doute sur
 * ce qui est réellement implémenté.
 */
constexpr const char* no_state_format =
    "Le moteur Nintendo DS n'a pas encore de format d'état : "
    "aucun instantané ne peut être enregistré ni relu";

class NintendoDsCore final : public Core {
public:
    [[nodiscard]] Console console() const noexcept override { return Console::nintendo_ds; }

    [[nodiscard]] VideoSpec video_spec() const noexcept override {
        return {screen_width, framebuffer_height, refresh_rate_hz};
    }

    /**
     * Le mélangeur de la console produit du 16 bits signé à 32 768 Hz sur deux
     * voies. La cadence est publiée dès maintenant parce qu'elle décrit le
     * matériel, pas l'avancement de son émulation.
     */
    [[nodiscard]] AudioSpec audio_spec() const noexcept override { return {32'768, 2}; }

    [[nodiscard]] FramebufferFormat framebuffer_format() const noexcept override {
        return FramebufferFormat::argb_8888;
    }

    void load_rom(std::span<const std::uint8_t> rom, std::span<const std::uint8_t>) override {
        // L'en-tête est décodé et contrôlé pour de bon : il doit refuser
        // franchement ce qu'il ne sait pas décrire.
        header_ = CartridgeHeader::parse(rom);
        // L'image est recopiée : l'amorçage la relit, et rien ne garantit que
        // l'appelant la garde en vie au-delà de cet appel.
        rom_.assign(rom.begin(), rom.end());
        reset();
    }

    /**
     * Prend l'image au lieu de la recopier.
     *
     * Ce cœur garde l'image telle quelle, et le chemin ordinaire en fait donc
     * exister deux exemplaires le temps de la copie. Une cartouche de deux cent
     * cinquante-six mégaoctets en demande alors un demi-gigaoctet pour rien.
     *
     * L'en-tête est contrôlé **avant** la prise : une image refusée doit
     * l'être sans que le cœur ait touché à son état, et un appelant qui reprend
     * la main après un refus doit retrouver son image intacte. Elle n'est donc
     * déplacée qu'une fois la cartouche acceptée.
     */
    void load_rom_owned(
        std::vector<std::uint8_t>&& rom,
        std::span<const std::uint8_t>
    ) override {
        header_ = CartridgeHeader::parse(rom);
        rom_ = std::move(rom);
        reset();
    }

    void reset() override {
        require_loaded();
        machine_.boot(*header_, rom_);
    }

    /**
     * Balaie une trame.
     *
     * Le saut d'image n'est pas honoré : la console est balayée de la même façon
     * dans les deux cas, et seul le dessin serait à éviter. L'économie serait
     * réelle, mais la sauter demanderait de dire au contrôleur d'affichage de ne
     * pas dessiner, ce qu'aucun appelant ne demande encore — et le cœur annonce
     * `supports_video_frame_skipping` faux, si bien que personne ne s'y attend.
     */
    void run_frame(std::span<std::int32_t> framebuffer, bool) override {
        require_loaded();
        machine_.run_frame(framebuffer);
    }

    void set_button(Button button, bool pressed) override {
        machine_.input().press(button, pressed);
    }

    /**
     * Ramène le contact dans l'écran, puis le confie à l'état partagé.
     *
     * Le convertisseur du port série le relira de là, sous la forme du
     * matériel : des mesures brutes, non des pixels.
     */
    void set_touch(bool down, int x, int y) noexcept override {
        machine_.input().set_touch(
            down,
            clamp_to_screen(x, screen_width),
            clamp_to_screen(y, screen_height)
        );
    }

    std::size_t read_audio(std::span<std::int16_t>) override { return 0; }

    [[nodiscard]] bool has_battery_ram() const noexcept override { return false; }
    [[nodiscard]] bool battery_ram_dirty() const noexcept override { return false; }
    std::optional<BatterySnapshot> snapshot_battery_ram() override { return std::nullopt; }
    void acknowledge_battery_ram_saved(std::uint64_t) noexcept override {}

    /**
     * Aucun format d'état n'est publié tant que la console n'est pas complète.
     * En figer un maintenant reviendrait à promettre une compatibilité sur un
     * contenu qui va encore changer à chaque organe ajouté.
     */
    [[nodiscard]] std::vector<std::uint8_t> save_state() const override {
        throw std::logic_error(no_state_format);
    }

    void load_state(std::span<const std::uint8_t>) override {
        throw std::logic_error(no_state_format);
    }

    /** En-tête de la cartouche chargée, pour les tests et l'outillage. */
    [[nodiscard]] const CartridgeHeader& header() const {
        require_loaded();
        return *header_;
    }

private:
    /** Ramène une coordonnée sur le bord le plus proche de l'écran. */
    [[nodiscard]] static std::uint8_t clamp_to_screen(int value, int size) noexcept {
        if (value < 0) return 0;
        if (value >= size) return static_cast<std::uint8_t>(size - 1);
        return static_cast<std::uint8_t>(value);
    }

    void require_loaded() const {
        if (!header_) throw std::logic_error("Aucune ROM Nintendo DS chargée");
    }

    std::optional<CartridgeHeader> header_;
    std::vector<std::uint8_t> rom_;
    Machine machine_{};
};

} // namespace

std::unique_ptr<Core> make_core() { return std::make_unique<NintendoDsCore>(); }

} // namespace nds

std::unique_ptr<Core> make_nds_core() { return nds::make_core(); }

} // namespace ravenemu
