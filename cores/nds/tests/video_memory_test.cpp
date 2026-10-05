#include "video/video_memory.hpp"

#include "check.hpp"

#include <cstdint>
#include <string>

/**
 * Aiguillage des neuf banques vidéo.
 *
 * Une banque n'est pas une mémoire à une adresse fixe : c'est un bloc qu'on
 * branche. Ces vérifications portent sur le branchement lui-même, parce que
 * c'est là qu'une erreur ne se voit pas — une banque branchée seize kilooctets
 * trop loin donne un décor faux, et rien dans le code ne le dit.
 *
 * Les places sont écrites en toutes lettres, telles qu'un programme de la
 * console les calculerait, et non reprises des constantes du cœur : une
 * constante fausse rendrait le test faux de la même façon, et il passerait.
 */
namespace ravenemu::nds::testing {

using ravenemu::testing::check;

namespace {

/** Bit d'allumage, écrit littéralement comme le ferait un programme. */
constexpr std::uint8_t on = 0x80;

/** Commande d'une banque : allumage, destination, écart. */
[[nodiscard]] constexpr std::uint8_t command(std::uint32_t mode, std::uint32_t selector = 0) {
    return static_cast<std::uint8_t>(0x80U | mode | (selector << 3U));
}

/** Marque un octet d'une banque pour le reconnaître à l'autre bout. */
void mark(VideoMemory& video, std::size_t bank, std::uint32_t offset, std::uint8_t value) {
    video.bank(bank)[offset] = value;
}

// --------------------------------------------------------------------------

void les_banques_ont_les_tailles_du_materiel() {
    VideoMemory video;
    video.reset();

    // Décodées depuis les banques elles-mêmes, et non comparées à la constante
    // qui les définit.
    const std::array<std::uint32_t, 9> expected{
        128U * 1024U, 128U * 1024U, 128U * 1024U, 128U * 1024U,
        64U * 1024U, 16U * 1024U, 16U * 1024U, 32U * 1024U, 16U * 1024U,
    };

    std::uint32_t total = 0;
    for (std::size_t index = 0; index < 9U; ++index) {
        const auto size = static_cast<std::uint32_t>(video.bank(index).size());
        check(size == expected[index], "banque " + std::to_string(index) + " à sa taille");
        total += size;
    }
    check(total == 656U * 1024U, "six cent cinquante-six kilooctets en tout");
    check(video.bank(9).empty(), "il n'y a pas de dixième banque");
}

void la_fenetre_de_transfert_place_chaque_banque() {
    VideoMemory video;
    video.reset();

    // Places écrites littéralement : chaque banque suit la précédente.
    const std::array<std::uint32_t, 9> bases{
        0x0680'0000U, 0x0682'0000U, 0x0684'0000U, 0x0686'0000U,
        0x0688'0000U, 0x0689'0000U, 0x0689'4000U, 0x0689'8000U, 0x068a'0000U,
    };

    for (std::size_t index = 0; index < 9U; ++index) {
        video.set_control(index, on);
        mark(video, index, 0, static_cast<std::uint8_t>(0x10U + index));
    }

    for (std::size_t index = 0; index < 9U; ++index) {
        auto* byte = video.transfer(bases[index]);
        check(byte != nullptr, "la banque " + std::to_string(index) + " répond à sa place");
        check(*byte == 0x10U + index, "et c'est bien la sienne");
    }
}

void une_banque_eteinte_ou_branchee_ailleurs_quitte_la_fenetre() {
    {   // Éteinte, elle ne répond nulle part.
        VideoMemory video;
        video.reset();
        mark(video, 0, 0, 0x42);
        check(video.transfer(0x0680'0000U) == nullptr, "une banque éteinte ne répond pas");
    }
    {   // Allumée mais laissée sur la fenêtre de transfert, elle répond.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(0));
        mark(video, 0, 0, 0x42);
        const auto* byte = video.transfer(0x0680'0000U);
        check(byte != nullptr && *byte == 0x42U, "allumée, elle répond");
    }
    {   // Branchée sur un moteur, elle disparaît de la fenêtre de transfert :
        // c'est ce qui rend le remplissage et l'affichage exclusifs.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(1));
        check(video.transfer(0x0680'0000U) == nullptr, "branchée ailleurs, elle quitte la fenêtre");
    }
}

void chaque_banque_se_branche_ou_le_materiel_le_dit() {
    {   // La banque A se déplace de cent vingt-huit kilooctets par cran.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(1, 2));
        mark(video, 0, 0, 0xa1);
        check(video.read_background(Engine::main, 0x4'0000U) == 0xa1U, "A au troisième cran");
        check(video.read_background(Engine::main, 0) == 0U, "et plus au début");
    }
    {   // La banque A en sprites n'a qu'un bit d'écart : le second est ignoré.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(2, 3));
        mark(video, 0, 0, 0xa2);
        check(video.read_object(Engine::main, 0x2'0000U) == 0xa2U, "A en sprites, un seul bit d'écart");
    }
    {   // La banque C sert le moteur secondaire, la D ses sprites.
        VideoMemory video;
        video.reset();
        video.set_control(2, command(4));
        video.set_control(3, command(4));
        mark(video, 2, 0, 0xc1);
        mark(video, 3, 0, 0xd1);
        check(video.read_background(Engine::secondary, 0) == 0xc1U, "C en décor du secondaire");
        check(video.read_object(Engine::secondary, 0) == 0xd1U, "D en sprites du secondaire");
    }
    {   // La banque E ne se déplace pas : elle n'a pas de champ d'écart.
        VideoMemory video;
        video.reset();
        video.set_control(4, command(1, 3));
        mark(video, 4, 0, 0xe1);
        check(video.read_background(Engine::main, 0) == 0xe1U, "E reste au début");
    }
    {   // Les deux petites banques combinent deux bits qui ne se suivent pas :
        // seize kilooctets pour l'un, cent vingt-huit pour l'autre.
        VideoMemory video;
        video.reset();
        video.set_control(5, command(1, 3));
        mark(video, 5, 0, 0xf1);
        check(
            video.read_background(Engine::main, 0x1'4000U) == 0xf1U,
            "F combine seize et cent vingt-huit kilooctets"
        );

        video.set_control(6, command(1, 1));
        mark(video, 6, 0, 0x61);
        check(video.read_background(Engine::main, 0x4000U) == 0x61U, "G au premier cran seul");
    }
    {   // La banque I est la seule qui ne commence pas au début de sa fenêtre :
        // elle complète celle qui la précède au lieu de la recouvrir.
        VideoMemory video;
        video.reset();
        video.set_control(7, command(1));
        video.set_control(8, command(1));
        mark(video, 7, 0, 0x81);
        mark(video, 8, 0, 0x91);
        check(video.read_background(Engine::secondary, 0) == 0x81U, "H au début");
        check(video.read_background(Engine::secondary, 0x8000U) == 0x91U, "I juste après");
        check(video.overlap_count() == 0U, "et elles ne se recouvrent pas");
    }
}

void une_banque_branchee_ailleurs_ne_sert_aucun_moteur() {
    // Textures et palettes étendues sont décodées sans être servies : ce qui
    // compte ici est qu'une banque qui y va disparaisse bien des fenêtres de
    // décor et de sprites, sans quoi elle y afficherait n'importe quoi.
    const std::array<std::pair<std::size_t, std::uint32_t>, 4> elsewhere{{
        {0, 3},   // A en texture
        {4, 3},   // E en palette de textures
        {4, 4},   // E en palette étendue de décor
        {5, 5},   // F en palette étendue de sprites
    }};

    for (const auto& [bank, mode] : elsewhere) {
        VideoMemory video;
        video.reset();
        video.set_control(bank, command(mode));
        mark(video, bank, 0, 0x77);
        check(
            video.read_background(Engine::main, 0) == 0U,
            "banque " + std::to_string(bank) + " destination " + std::to_string(mode) +
                " : absente du décor"
        );
        check(video.read_object(Engine::main, 0) == 0U, "et des sprites");
    }

    {   // Prêtée au processeur secondaire, elle quitte aussi les moteurs.
        VideoMemory video;
        video.reset();
        video.set_control(2, command(2));
        mark(video, 2, 0, 0x77);
        check(video.read_background(Engine::main, 0) == 0U, "C prêtée ne sert plus le décor");
    }
}

void les_places_disputees_et_les_places_vides_sont_comptees() {
    {   // Deux banques au même endroit : le matériel ne tranche pas, et le taire
        // laisserait une faute de configuration se voir bien plus loin.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(1));
        video.set_control(4, command(1));
        mark(video, 0, 0, 0xaa);
        mark(video, 4, 0, 0xee);

        check(video.read_background(Engine::main, 0) == 0xaaU, "la première dans l'ordre répond");
        check(video.overlap_count() == 1U, "et le conflit est compté");
        check(video.unbacked_count() == 0U, "sans être pris pour une place vide");
    }
    {   // Une place qu'aucune banque ne sert rend zéro, et le dit.
        VideoMemory video;
        video.reset();
        check(video.read_background(Engine::main, 0) == 0U, "rien n'y répond");
        check(video.unbacked_count() == 1U, "et c'est compté");

        video.set_control(0, command(1));
        check(video.read_background(Engine::main, 0x2'0000U) == 0U, "au-delà de la banque non plus");
        check(video.unbacked_count() == 2U, "compté aussi");
    }
    {   // Les deux moteurs ont des fenêtres séparées.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(1));
        mark(video, 0, 0, 0xaa);
        check(video.read_background(Engine::secondary, 0) == 0U, "le secondaire ne voit pas A");
    }
}

void le_champ_de_destination_n_a_pas_la_meme_largeur_partout() {
    {   // Les deux premières banques n'ont que deux bits de destination : le
        // troisième ne compte pas, et une destination 5 revient à la 1.
        VideoMemory video;
        video.reset();
        video.set_control(0, command(5));
        mark(video, 0, 0, 0xa5);
        check(video.read_background(Engine::main, 0) == 0xa5U, "5 revient à 1 sur la banque A");
    }
    {   // Les banques de milieu de série en ont trois : une destination 4 y est
        // une destination à part entière, et non un repli sur la 0.
        VideoMemory video;
        video.reset();
        video.set_control(2, command(4));
        mark(video, 2, 0, 0xc4);
        check(video.transfer(0x0684'0000U) == nullptr, "4 n'est pas 0 sur la banque C");
        check(video.read_background(Engine::secondary, 0) == 0xc4U, "c'est le décor du secondaire");
    }
    {   // La dernière banque en a deux, et sa destination 3 est une palette.
        VideoMemory video;
        video.reset();
        video.set_control(8, command(2));
        mark(video, 8, 0, 0x92);
        check(video.read_object(Engine::secondary, 0) == 0x92U, "I en sprites du secondaire");

        video.set_control(8, command(3));
        check(video.read_object(Engine::secondary, 0) == 0U, "et sa destination 3 est ailleurs");
    }
}

void une_banque_eteinte_ne_sert_aucun_moteur() {
    // Le branchement est écrit, mais le bit d'allumage manque : la banque ne doit
    // répondre nulle part, pas même là où elle irait une fois allumée.
    VideoMemory video;
    video.reset();
    video.set_control(0, 0x01U);
    mark(video, 0, 0, 0x5a);
    check(video.read_background(Engine::main, 0) == 0U, "éteinte, elle ne sert pas le décor");
    check(video.unbacked_count() == 1U, "et la place compte pour vide");

    video.set_control(0, 0x81U);
    check(video.read_background(Engine::main, 0) == 0x5aU, "allumée, elle sert");
}

void une_lecture_de_seize_bits_assemble_deux_octets() {
    VideoMemory video;
    video.reset();
    video.set_control(0, command(1));
    mark(video, 0, 0, 0x34);
    mark(video, 0, 1, 0x12);
    check(video.read_background16(Engine::main, 0) == 0x1234U, "octet bas d'abord");
}

void la_remise_a_zero_efface_le_contenu_et_le_branchement() {
    VideoMemory video;
    video.reset();
    video.set_control(0, command(1));
    mark(video, 0, 0, 0x5a);
    static_cast<void>(video.read_background(Engine::main, 0x7f'0000U));

    video.reset();
    check(video.control(0) == 0U, "les branchements sont défaits");
    check(video.bank(0)[0] == 0U, "le contenu est effacé");
    check(video.overlap_count() == 0U, "et les comptes repartent de zéro");
    check(video.unbacked_count() == 0U, "les deux");
}

void le_branchement_se_relit_tel_qu_il_a_ete_ecrit() {
    VideoMemory video;
    video.reset();
    video.set_control(3, 0x8bU);
    check(video.control(3) == 0x8bU, "la commande se relit entière");

    // Une banque éteinte garde sa destination : c'est le bit d'allumage qui la
    // coupe, et lui seul. Le reste de la commande continue de dire où elle irait.
    const auto placement = video.assignment(3);
    check(placement.enabled, "le bit de poids fort l'allume");
    check(placement.target == VramTarget::texture, "destination 3 : les textures");
    check(placement.offset == 0x2'0000U, "écart 1 : un bloc de cent vingt-huit kilooctets");

    video.set_control(3, 0x0bU);
    const auto extinguished = video.assignment(3);
    check(!extinguished.enabled, "sans ce bit, elle est éteinte");
    check(extinguished.target == placement.target, "mais branchée au même endroit");
    check(extinguished.offset == placement.offset, "et à la même place");
}

} // namespace

} // namespace ravenemu::nds::testing

int main() {
    using namespace ravenemu::nds::testing;
    les_banques_ont_les_tailles_du_materiel();
    la_fenetre_de_transfert_place_chaque_banque();
    une_banque_eteinte_ou_branchee_ailleurs_quitte_la_fenetre();
    chaque_banque_se_branche_ou_le_materiel_le_dit();
    une_banque_branchee_ailleurs_ne_sert_aucun_moteur();
    les_places_disputees_et_les_places_vides_sont_comptees();
    le_champ_de_destination_n_a_pas_la_meme_largeur_partout();
    une_banque_eteinte_ne_sert_aucun_moteur();
    une_lecture_de_seize_bits_assemble_deux_octets();
    la_remise_a_zero_efface_le_contenu_et_le_branchement();
    le_branchement_se_relit_tel_qu_il_a_ete_ecrit();
    return 0;
}
