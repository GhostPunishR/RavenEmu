#include <ravenemu/nds/core.hpp>

#include <optional>
#include <vector>

namespace ravenemu {
namespace nds {

namespace {

/**
 * Message unique du refus d'exécution.
 *
 * Nommé une fois : le cœur, ses tests et la documentation doivent dire
 * exactement la même chose, et un écart entre eux se lirait comme un doute sur
 * ce qui est réellement implémenté.
 */
constexpr const char* not_implemented =
    "Le moteur Nintendo DS n'est pas encore implémenté : "
    "aucune trame ne peut être produite";

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
        // L'en-tête est décodé et contrôlé pour de bon : c'est la seule partie
        // du cœur qui fonctionne, et elle doit refuser franchement ce qu'elle
        // ne sait pas décrire.
        header_ = CartridgeHeader::parse(rom);
        rom_size_ = rom.size();
    }

    void reset() override { require_loaded(); }

    void run_frame(std::span<std::int32_t>, bool) override {
        require_loaded();
        throw std::logic_error(not_implemented);
    }

    void set_button(Button, bool) override {}

    std::size_t read_audio(std::span<std::int16_t>) override { return 0; }

    [[nodiscard]] bool has_battery_ram() const noexcept override { return false; }
    [[nodiscard]] bool battery_ram_dirty() const noexcept override { return false; }
    std::optional<BatterySnapshot> snapshot_battery_ram() override { return std::nullopt; }
    void acknowledge_battery_ram_saved(std::uint64_t) noexcept override {}

    /**
     * Aucun format d'état n'est publié tant qu'il n'y a pas de machine à
     * décrire. En figer un maintenant reviendrait à promettre une compatibilité
     * sur un contenu encore inconnu.
     */
    [[nodiscard]] std::vector<std::uint8_t> save_state() const override {
        throw std::logic_error(not_implemented);
    }

    void load_state(std::span<const std::uint8_t>) override {
        throw std::logic_error(not_implemented);
    }

    /** En-tête de la cartouche chargée, pour les tests et l'outillage. */
    [[nodiscard]] const CartridgeHeader& header() const {
        require_loaded();
        return *header_;
    }

private:
    void require_loaded() const {
        if (!header_) throw std::logic_error("Aucune ROM Nintendo DS chargée");
    }

    std::optional<CartridgeHeader> header_;
    std::size_t rom_size_{};
};

} // namespace

std::unique_ptr<Core> make_core() { return std::make_unique<NintendoDsCore>(); }

} // namespace nds

std::unique_ptr<Core> make_nds_core() { return nds::make_core(); }

} // namespace ravenemu
