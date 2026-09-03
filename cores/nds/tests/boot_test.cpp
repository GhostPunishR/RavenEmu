#include "crc16.hpp"
#include "system/machine.hpp"

#include <ravenemu/nds/core.hpp>

#include "check.hpp"

#include <cstdint>
#include <initializer_list>
#include <span>
#include <string_view>
#include <vector>

/**
 * Amorçage d'une cartouche.
 *
 * C'est le lot qui relie tout le reste : jusqu'ici la console savait tourner
 * mais rien ne mettait de programme dans sa mémoire, et `run_frame` refusait.
 *
 * La vérification centrale fait démarrer une cartouche synthétique dont les
 * **deux** binaires font quelque chose : le processeur secondaire dépose une
 * couleur dans la mémoire que les deux partagent, le principal l'attend et la
 * pose en fond d'écran. Une seule trame suffit alors à dire que les deux blocs
 * ont été chargés chacun à son adresse, que les deux points d'entrée ont été
 * suivis, que les deux processeurs tournent et se voient, et que le moteur
 * dessine ce qu'on lui a donné.
 *
 * Chaque binaire commence par une boucle sur place, avant son point d'entrée :
 * un amorçage qui ignorerait le point d'entrée et démarrerait à l'adresse de
 * chargement s'y prendrait au piège et n'afficherait jamais rien.
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
constexpr std::uint32_t io_base = 0x0400'0000;
constexpr std::uint32_t palette_base = 0x0500'0000;
/** Case de la mémoire partagée où le secondaire dépose sa couleur. */
constexpr std::uint32_t handover = 0x800;

constexpr std::uint32_t always = 0xeU;
constexpr std::uint32_t equal = 0x0U;

/** `MOV Rd, #value ROR rotation`. */
constexpr std::uint32_t mov_immediate(
    std::uint32_t rd,
    std::uint32_t value,
    std::uint32_t rotation = 0U
) noexcept {
    return (always << 28U) | (1U << 25U) | (0xdU << 21U) | (rd << 12U) |
        ((rotation / 2U) << 8U) | value;
}

/** `CMP Rn, #value`. */
constexpr std::uint32_t compare_immediate(std::uint32_t rn, std::uint32_t value) noexcept {
    return (always << 28U) | (1U << 25U) | (0xaU << 21U) | (1U << 20U) | (rn << 16U) | value;
}

/** Branchement exprimé en instructions depuis celle qui branche. */
constexpr std::uint32_t branch(std::int32_t words, std::uint32_t condition = always) noexcept {
    return (condition << 28U) | (0x5U << 25U) |
        (static_cast<std::uint32_t>(words - 2) & 0x00ff'ffffU);
}

/** `cond 01 I P U B W L Rn Rd offset`, pré-indexé sans réécriture. */
constexpr std::uint32_t transfer(
    bool load,
    std::uint32_t rn,
    std::uint32_t rd,
    std::uint32_t offset = 0U
) noexcept {
    return (always << 28U) | (1U << 26U) | (1U << 24U) | (1U << 23U) |
        (load ? (1U << 20U) : 0U) | (rn << 16U) | (rd << 12U) | offset;
}

/** Bleu pur, tel que la console range ses couleurs : cinq bits par composante. */
constexpr std::uint32_t blue_entry = 0x7c00;
constexpr std::int32_t blue = static_cast<std::int32_t>(0xff00'00ffU);
constexpr std::int32_t black = static_cast<std::int32_t>(0xff00'0000U);

/** Fabrique d'image de cartouche : en-tête scellé et deux blocs de programme. */
class Cartridge {
public:
    /**
     * @param size longueur de l'image. Elle se règle parce qu'un bloc de la
     *   taille de celui d'un jeu du commerce ne tient pas dans la taille par
     *   défaut, qui suffit à tous les autres cas.
     */
    explicit Cartridge(std::size_t size = rom_bytes) : image_(size, 0) {
        write_text(0x000, "RAVENBOOT");
        write_text(0x00c, "ARVE");
        write_text(0x010, "01");
        image_[0x012] = static_cast<std::uint8_t>(UnitCode::nintendo_ds);
        image_[0x014] = 0x09;
        write_u32(0x080, static_cast<std::uint32_t>(image_.size()));
        write_u32(0x084, 0x0000'4000);
        set_block(true, arm9_rom_offset, main_ram_base, main_ram_base, block_bytes);
        set_block(false, arm7_rom_offset, private_wram_base, private_wram_base, block_bytes);
    }

    /** Décrit un bloc de programme : où il est, où il va, où il commence. */
    void set_block(
        bool main,
        std::uint32_t rom_offset,
        std::uint32_t ram_address,
        std::uint32_t entry_address,
        std::uint32_t size
    ) {
        const std::size_t base = main ? 0x020U : 0x030U;
        write_u32(base + 0x0U, rom_offset);
        write_u32(base + 0x4U, entry_address);
        write_u32(base + 0x8U, ram_address);
        write_u32(base + 0xcU, size);
    }

    /** Dépose un programme dans l'image, à la place d'un bloc. */
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

    /** Scelle l'en-tête et rend l'image. */
    [[nodiscard]] const std::vector<std::uint8_t>& sealed() {
        const auto crc = detail::crc16(
            std::span<const std::uint8_t>{image_}.subspan(0, CartridgeHeader::crc_covered_bytes)
        );
        image_[CartridgeHeader::header_crc_offset] = static_cast<std::uint8_t>(crc & 0xffU);
        image_[CartridgeHeader::header_crc_offset + 1U] = static_cast<std::uint8_t>(crc >> 8U);
        return image_;
    }

private:
    std::vector<std::uint8_t> image_;
};

/**
 * Cartouche dont les deux processeurs se relaient pour peindre l'écran.
 *
 * Le secondaire dépose la couleur, le principal l'attend et la pose. Aucun des
 * deux ne suffit seul.
 */
Cartridge relay_cartridge() {
    Cartridge cartridge;

    // Le point d'entrée est décalé de deux instructions, précédées d'une boucle
    // sur place : démarrer à l'adresse de chargement ne mène nulle part.
    cartridge.set_block(true, arm9_rom_offset, main_ram_base, main_ram_base + 8U, block_bytes);
    cartridge.set_code(arm9_rom_offset, {
        branch(0),
        branch(0),
        mov_immediate(2U, io_base >> 24U, 8U),
        mov_immediate(3U, palette_base >> 24U, 8U),
        mov_immediate(5U, main_ram_base >> 24U, 8U),
        mov_immediate(1U, 1U, 16U),                              // moteur allumé, mode graphique
        transfer(false, 2U, 1U),                                 // commande d'affichage
        transfer(true, 5U, 1U, handover),                        // la couleur du secondaire
        compare_immediate(1U, 0U),
        branch(-2, equal),
        transfer(false, 3U, 1U),                                 // couleur de fond
        branch(0),
    });

    cartridge.set_block(
        false, arm7_rom_offset, private_wram_base, private_wram_base + 4U, block_bytes);
    cartridge.set_code(arm7_rom_offset, {
        branch(0),
        mov_immediate(1U, blue_entry >> 10U, 22U),               // 0x7c00 : bleu pur
        mov_immediate(5U, main_ram_base >> 24U, 8U),
        transfer(false, 5U, 1U, handover),
        branch(0),
    });
    return cartridge;
}

/** Tampon aux dimensions que le cœur publie. */
std::vector<std::int32_t> framebuffer() {
    return std::vector<std::int32_t>(
        static_cast<std::size_t>(screen_width) * static_cast<std::size_t>(framebuffer_height), 0);
}

// --------------------------------------------------------------------------

/**
 * Une cartouche synthétique démarre, ses deux processeurs se relaient, et
 * l'écran montre le résultat.
 */
void une_cartouche_demarre_et_dessine() {
    auto cartridge = relay_cartridge();
    auto core = make_core();
    auto pixels = framebuffer();

    core->load_rom(cartridge.sealed(), {});
    core->run_frame(pixels, true);

    check(pixels[0] == blue, "l'écran du haut porte la couleur déposée par le secondaire");
    check(pixels[191U * 256U + 255U] == blue, "jusqu'à son dernier pixel");
    check(pixels[192U * 256U] == black, "l'écran du bas reste noir, son moteur n'étant pas allumé");

    // Une seconde trame ne dégrade rien : les deux processeurs tournent toujours,
    // sur des boucles où ils se sont arrêtés.
    core->run_frame(pixels, true);
    check(pixels[0] == blue, "la trame suivante montre la même chose");

    // Et l'amorçage se rejoue : remettre la console à zéro la fait repartir de
    // la cartouche, non d'un état mort.
    core->reset();
    core->run_frame(pixels, true);
    check(pixels[0] == blue, "après remise à zéro la cartouche redémarre");
}

/**
 * Une image cédée démarre comme une image prêtée, et n'est prise qu'acceptée.
 *
 * Ce chemin existe pour les grosses cartouches : le cœur garde l'image, et la
 * recopier en fait exister deux exemplaires le temps de la copie. Deux choses
 * doivent tenir. La cartouche doit démarrer exactement pareil — sinon le
 * chemin économe serait un chemin différent. Et une image **refusée** doit
 * rester intacte chez l'appelant : la prendre avant de la contrôler laisserait
 * celui qui reprend la main devant un tableau vide, sans rien pour dire
 * pourquoi.
 */
void une_image_cedee_demarre_comme_une_image_pretee() {
    auto cartridge = relay_cartridge();
    auto pixels = framebuffer();

    {
        auto core = make_core();
        const auto& sealed = cartridge.sealed();
        std::vector<std::uint8_t> owned(sealed.begin(), sealed.end());
        core->load_rom_owned(std::move(owned), {});
        core->run_frame(pixels, true);
        check(pixels[0] == blue, "la cartouche cédée démarre et dessine");
        check(pixels[192U * 256U] == black, "et l'écran du bas reste noir, comme avant");
    }

    {
        // Une image qu'aucun en-tête ne décrit : le refus doit précéder la prise.
        auto core = make_core();
        std::vector<std::uint8_t> broken(0x200, 0);
        bool refused = false;
        try {
            core->load_rom_owned(std::move(broken), {});
        } catch (const RomLoadError&) {
            refused = true;
        }
        check(refused, "une image indescriptible est refusée");
        check(!broken.empty(), "et elle reste intacte chez l'appelant");
    }
}

/**
 * Le bloc secondaire d'un jeu du commerce arrive intact, à cheval sur deux
 * mémoires.
 *
 * Un jeu charge son bloc secondaire à `0x037F8000`, juste sous la mémoire
 * propre de ce processeur : les deux se suivent alors sans trou, et un bloc de
 * quatre-vingt-seize kilooctets tient dans la mémoire commune puis déborde
 * proprement dans la mémoire propre. Ce n'est vrai que si le processeur
 * secondaire **tient** la mémoire commune. Sans part, sa fenêtre se replie sur
 * sa mémoire propre, le bloc s'y enroule et écrase son propre début : le
 * processeur exécute alors la fin de son programme prise pour le commencement,
 * et la console reste noire sans que rien ne le signale.
 *
 * Chaque mot porte son propre rang, si bien qu'un mot déplacé se voit.
 */
void le_bloc_secondaire_arrive_intact_a_son_adresse_reelle() {
    constexpr std::uint32_t arm7_offset = 0x8000;
    constexpr std::uint32_t arm7_size = 0x18000;
    constexpr std::uint32_t arm7_address = 0x037f'8000;
    constexpr std::uint32_t shared_wram_end = 0x0380'0000;

    Cartridge cartridge{0x4'0000};
    cartridge.set_block(false, arm7_offset, arm7_address, arm7_address, arm7_size);
    for (std::uint32_t written = 0; written < arm7_size; written += 4U) {
        cartridge.write_u32(arm7_offset + written, 0xa500'0000U | (written / 4U));
    }
    const auto& image = cartridge.sealed();

    Machine machine;
    machine.boot(CartridgeHeader::parse(image), image);

    std::uint32_t wrong = 0;
    for (std::uint32_t written = 0; written < arm7_size; written += 4U) {
        const auto read = machine.secondary_memory().read32(arm7_address + written);
        if (read != (0xa500'0000U | (written / 4U))) ++wrong;
    }
    check(wrong == 0U, "chaque mot du bloc secondaire est à sa place");

    // Le bloc franchit bien la frontière entre les deux mémoires : sans cela, la
    // vérification ci-dessus tiendrait aussi pour un bloc qui n'en toucherait
    // qu'une seule, et ne dirait rien de ce qui compte.
    check(arm7_address + arm7_size > shared_wram_end, "le bloc franchit les deux mémoires");
    check(
        machine.secondary_memory().read32(shared_wram_end) ==
            (0xa500'0000U | ((shared_wram_end - arm7_address) / 4U)),
        "et le premier mot de la mémoire propre est celui qui suit"
    );
}

/** Chaque bloc va où l'en-tête le dit, et l'exécution commence où il le dit. */
void les_blocs_vont_ou_l_en_tete_les_envoie() {
    auto cartridge = relay_cartridge();
    const auto& image = cartridge.sealed();
    const auto header = CartridgeHeader::parse(image);

    Machine machine;
    machine.boot(header, image);

    check(
        machine.main_memory().read32(main_ram_base) == branch(0),
        "le bloc du principal est arrivé à son adresse de chargement"
    );
    check(
        machine.secondary_memory().read32(private_wram_base) == branch(0),
        "celui du secondaire dans sa mémoire propre, que l'autre ne voit pas"
    );
    check(
        machine.main_memory().read32(private_wram_base) == 0U,
        "et le principal ne voit pas cette mémoire"
    );

    check(
        machine.core(Processor::main).state().registers[15] == main_ram_base + 8U,
        "le principal démarre à son point d'entrée, non à son adresse de chargement"
    );
    check(
        machine.core(Processor::secondary).state().registers[15] == private_wram_base + 4U,
        "le secondaire aussi"
    );

    // L'amorçage remet la console à zéro : les deux processeurs repartent de
    // leur état de mise sous tension, interruptions masquées comprises.
    for (const auto side : {Processor::main, Processor::secondary}) {
        const auto& state = machine.core(side).state();
        check(state.mode() == CpuMode::supervisor, "le processeur est en mode superviseur");
        check(state.flag(psr::irq_disable), "et ses interruptions sont masquées");
        check(!machine.core(side).halted(), "aucun processeur n'est arrêté");
    }
    check(machine.display().line() == 0U, "le faisceau repart de la première ligne");
}

/** L'amorçage écrase ce qu'une partie précédente avait laissé. */
void amorcer_efface_la_partie_precedente() {
    auto cartridge = relay_cartridge();
    const auto& image = cartridge.sealed();
    const auto header = CartridgeHeader::parse(image);

    Machine machine;
    machine.boot(header, image);
    machine.main_memory().write32(main_ram_base + handover, 0xdead'beefU);
    machine.core(Processor::main).state().registers[0] = 0x1234U;

    machine.boot(header, image);

    check(
        machine.main_memory().read32(main_ram_base + handover) == 0U,
        "la mémoire de la partie précédente est effacée"
    );
    check(machine.core(Processor::main).state().registers[0] == 0U, "et les registres aussi");
}

/**
 * Un bloc dont la taille n'est pas un multiple de quatre.
 *
 * Le transfert de cartouche se fait par mots : le dernier déborde du bloc
 * annoncé, et c'est le comportement du matériel plutôt qu'une approximation.
 */
void un_bloc_de_taille_impaire_passe_par_mots() {
    Cartridge cartridge;
    cartridge.set_block(true, arm9_rom_offset, main_ram_base, main_ram_base, 5U);
    cartridge.set_code(arm9_rom_offset, {0x1122'3344U, 0x5566'7788U});
    const auto& image = cartridge.sealed();
    const auto header = CartridgeHeader::parse(image);

    Machine machine;
    machine.boot(header, image);

    check(machine.main_memory().read32(main_ram_base) == 0x1122'3344U, "le premier mot est passé");
    check(
        machine.main_memory().read32(main_ram_base + 4U) == 0x5566'7788U,
        "et le second entier, bien que le bloc s'arrête au milieu"
    );
    check(
        machine.main_memory().read32(main_ram_base + 8U) == 0U,
        "sans aller au-delà du mot qui contient le dernier octet"
    );
}

/**
 * Une image plus courte que ce que l'en-tête annonce.
 *
 * L'en-tête refuse déjà ce cas au décodage ; la borne éprouvée ici est celle du
 * tampon, pour l'appelant qui fournirait une autre image que celle décodée.
 *
 * **L'image tronquée est une copie de la taille exacte**, et non une vue sur
 * l'image entière. Sur une vue, lire au-delà de la borne retomberait dans un
 * tampon encore vivant : le débordement serait réel et pourtant invisible, pour
 * un contrôleur mémoire comme pour une comparaison de valeurs. Sur une copie
 * ajustée, il sort du tas et se voit.
 */
void une_image_tronquee_ne_deborde_pas() {
    Cartridge cartridge;
    cartridge.set_block(true, arm9_rom_offset, main_ram_base, main_ram_base, block_bytes);
    cartridge.set_code(arm9_rom_offset, {0x1122'3344U, 0x5566'7788U});
    const auto& image = cartridge.sealed();
    const auto header = CartridgeHeader::parse(image);

    const std::vector<std::uint8_t> truncated(
        image.begin(), image.begin() + static_cast<std::ptrdiff_t>(arm9_rom_offset) + 4);

    Machine machine;
    machine.boot(header, truncated);

    check(machine.main_memory().read32(main_ram_base) == 0x1122'3344U, "ce qui est là est chargé");
    check(machine.main_memory().read32(main_ram_base + 4U) == 0U, "et le reste vaut zéro");
}

/**
 * Chaque bloc va dans la mémoire de son processeur.
 *
 * La mémoire principale ne suffit pas à le dire : les deux processeurs la
 * voient, et un bloc chargé par la mauvaise carte y atterrit tout de même au bon
 * endroit. Il faut une région que l'un voit et l'autre pas.
 */
void chaque_bloc_passe_par_la_carte_de_son_processeur() {
    Cartridge cartridge;
    // La palette n'appartient qu'au processeur principal. Ce n'est pas une
    // destination qu'une vraie cartouche choisirait ; c'est la plus courte qui
    // distingue les deux cartes.
    cartridge.set_block(true, arm9_rom_offset, palette_base, palette_base, 4U);
    cartridge.set_code(arm9_rom_offset, {0x1234'5678U});
    const auto& image = cartridge.sealed();
    const auto header = CartridgeHeader::parse(image);

    Machine machine;
    machine.boot(header, image);

    check(
        machine.main_memory().read32(palette_base) == 0x1234'5678U,
        "le bloc du principal a suivi la carte du principal"
    );
    check(
        machine.secondary_memory().unmapped_count() == 0U,
        "et la carte du secondaire n'a rien vu passer"
    );
}

/**
 * Le relevé dit ce que la console a fait, et non ce qu'on espère.
 *
 * Un écran noir a plusieurs causes, et rien à l'écran ne les sépare. Le relevé
 * les sépare : le compte des instructions dit si les processeurs avancent, le
 * compte des pixels allumés dit si le moteur a produit une image. Deux pannes
 * sans rapport, deux mesures distinctes.
 */
void le_releve_dit_ce_que_la_console_a_fait() {
    auto cartridge = relay_cartridge();
    const auto& image = cartridge.sealed();

    Machine machine;
    machine.boot(CartridgeHeader::parse(image), image);

    // Avant toute trame, rien n'a tourné et rien n'a été dessiné.
    const auto avant = machine.report();
    check(avant.main_instructions == 0, "aucune instruction avant la première trame");
    check(avant.secondary_instructions == 0, "des deux côtés");
    check(avant.non_black_pixels == 0, "et aucun pixel allumé");

    auto pixels = framebuffer();
    machine.run_frame(pixels);
    const auto apres = machine.report();

    check(apres.main_instructions > 0, "le processeur principal a exécuté des instructions");
    check(apres.secondary_instructions > 0, "le secondaire aussi");
    check(!apres.main_halted, "et aucun des deux n'attend");
    check(!apres.secondary_halted, "le secondaire non plus");

    // L'écran du haut est entièrement bleu, celui du bas entièrement noir : le
    // compte vaut donc exactement une moitié de tampon, écrite en toutes
    // lettres plutôt que reprise à la constante qui la définit.
    check(apres.non_black_pixels == 192 * 256, "un écran plein d'une couleur, l'autre noir");

    // Les compteurs de manques restent muets : cette cartouche ne demande que
    // ce que le moteur sait faire, et un compteur qui monterait ici accuserait
    // à tort.
    check(apres.unimplemented_layers == 0, "aucun plan laissé de côté");
    check(apres.unimplemented_display == 0, "aucun mode de sortie laissé de côté");
    check(apres.main_undefined_count == 0, "aucune instruction indéfinie");
    check(apres.secondary_undefined_count == 0, "des deux côtés");

    // Le relevé du cœur est celui de la machine : c'est par lui que
    // l'application le lit, et un chemin qui ne rendrait rien laisserait
    // l'écran noir sans explication.
    auto core = make_core();
    core->load_rom(image, {});
    core->run_frame(pixels, true);
    const auto par_le_coeur = core->nds_debug_snapshot();
    check(par_le_coeur.has_value(), "le cœur rend son relevé");
    check(par_le_coeur->non_black_pixels == 192 * 256, "et il porte les mêmes mesures");
}

/**
 * Un processeur à l'arrêt ne compte pas d'instructions.
 *
 * Sans cette distinction, deux mille pas d'attente ressembleraient à deux mille
 * instructions exécutées : un programme figé passerait pour un programme qui
 * travaille, et le relevé accuserait l'affichage à la place de l'émulation.
 */
void un_processeur_arrete_ne_compte_pas() {
    auto cartridge = relay_cartridge();
    const auto& image = cartridge.sealed();

    Machine machine;
    machine.boot(CartridgeHeader::parse(image), image);
    machine.core(Processor::main).halt();

    auto pixels = framebuffer();
    machine.run_frame(pixels);
    const auto releve = machine.report();

    check(releve.main_halted, "le processeur principal est resté à l'arrêt");
    check(releve.main_instructions == 0, "et n'a compté aucune instruction");
    check(releve.secondary_instructions > 0, "quand l'autre en a compté");
    // Et l'écran est noir, faute du programme qui devait le peindre : c'est
    // exactement la panne que le relevé doit savoir nommer.
    check(releve.non_black_pixels == 0, "aucun pixel allumé");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    une_cartouche_demarre_et_dessine();
    une_image_cedee_demarre_comme_une_image_pretee();
    le_bloc_secondaire_arrive_intact_a_son_adresse_reelle();
    les_blocs_vont_ou_l_en_tete_les_envoie();
    amorcer_efface_la_partie_precedente();
    un_bloc_de_taille_impaire_passe_par_mots();
    une_image_tronquee_ne_deborde_pas();
    chaque_bloc_passe_par_la_carte_de_son_processeur();
    le_releve_dit_ce_que_la_console_a_fait();
    un_processeur_arrete_ne_compte_pas();
    return 0;
}
