#include <ravenemu/read_exactly.hpp>

#include "check.hpp"

#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

/**
 * La lecture d'un descripteur, exactement.
 *
 * C'est par ce chemin qu'une cartouche entre sans passer par la mémoire de
 * l'hôte, et deux fautes y sont silencieuses. Une lecture partielle prise pour
 * la fin donne une image tronquée que rien ne signale. Une fin prématurée
 * complétée par des zéros donne une cartouche qui démarre puis part ailleurs.
 */
namespace ravenemu::testing {

using ravenemu::testing::check;

namespace {

/** Un fichier temporaire portant [content], et son chemin. */
std::string write_temporary(const std::vector<std::uint8_t>& content) {
    std::string path = "/tmp/ravenemu_read_exactly_XXXXXX";
    const int descriptor = ::mkstemp(path.data());
    check(descriptor >= 0, "le fichier temporaire s'ouvre");
    if (!content.empty()) {
        const auto written = ::write(descriptor, content.data(), content.size());
        check(written == static_cast<ssize_t>(content.size()), "et s'écrit en entier");
    }
    ::close(descriptor);
    return path;
}

void tout_le_contenu_est_lu() {
    std::vector<std::uint8_t> content(100'000);
    for (std::size_t index = 0; index < content.size(); ++index) {
        content[index] = static_cast<std::uint8_t>(index * 7U);
    }
    const auto path = write_temporary(content);

    const int descriptor = ::open(path.c_str(), O_RDONLY);
    check(descriptor >= 0, "le fichier se rouvre");
    const auto read = ravenemu::read_exactly(descriptor, content.size());
    ::close(descriptor);
    ::unlink(path.c_str());

    check(read.size() == content.size(), "la longueur lue est celle demandée");
    check(read == content, "et chaque octet est à sa place");
}

void une_fin_prematuree_est_refusee() {
    // Cent octets dans le fichier, deux cents demandés : compléter par des
    // zéros donnerait une cartouche tronquée qui démarre puis part ailleurs.
    const auto path = write_temporary(std::vector<std::uint8_t>(100, 0x5a));

    const int descriptor = ::open(path.c_str(), O_RDONLY);
    check(descriptor >= 0, "le fichier se rouvre");
    bool refused = false;
    try {
        static_cast<void>(ravenemu::read_exactly(descriptor, 200));
    } catch (const std::runtime_error&) {
        refused = true;
    }
    ::close(descriptor);
    ::unlink(path.c_str());
    check(refused, "une fin prématurée est refusée plutôt que complétée");
}

void un_descripteur_ferme_est_refuse() {
    const auto path = write_temporary(std::vector<std::uint8_t>(16, 1));
    const int descriptor = ::open(path.c_str(), O_RDONLY);
    check(descriptor >= 0, "le fichier se rouvre");
    ::close(descriptor);
    ::unlink(path.c_str());

    bool refused = false;
    try {
        static_cast<void>(ravenemu::read_exactly(descriptor, 16));
    } catch (const std::runtime_error&) {
        refused = true;
    }
    check(refused, "un descripteur fermé est refusé");
}

void une_lecture_vide_ne_touche_pas_au_descripteur() {
    // Zéro octet demandé : la boucle ne s'exécute pas, et un descripteur
    // invalide n'a donc aucune occasion d'être lu.
    const auto empty = ravenemu::read_exactly(-1, 0);
    check(empty.empty(), "rien n'est demandé, rien n'est lu");
}

} // namespace

} // namespace ravenemu::testing

int main() {
    using namespace ravenemu::testing;
    tout_le_contenu_est_lu();
    une_fin_prematuree_est_refusee();
    un_descripteur_ferme_est_refuse();
    une_lecture_vide_ne_touche_pas_au_descripteur();
    return 0;
}
