#pragma once

#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace ravenemu {

/**
 * Lit exactement [count] octets depuis un descripteur de fichier ouvert.
 *
 * ### Pourquoi lire ainsi plutôt que par la mémoire de l'hôte
 *
 * Une cartouche Nintendo DS pèse jusqu'à un demi-gigaoctet. La faire transiter
 * par un tableau de la machine virtuelle Java en demanderait deux exemplaires,
 * dans un tas dont Android plafonne la taille bien en dessous de ce que
 * l'appareil permettrait. Lue ici, l'image n'existe qu'une fois, en mémoire
 * native, et le cœur peut la reprendre sans la recopier.
 *
 * ### Les trois cas que cette fonction distingue
 *
 * Une lecture **interrompue par un signal** n'est pas une erreur : elle se
 * reprend là où elle s'est arrêtée. Une lecture **partielle** n'en est pas une
 * non plus : un flux a le droit de rendre moins que demandé, et la boucle
 * continue. Une **fin de fichier prématurée**, en revanche, en est une : le
 * fichier est plus court que sa taille annoncée, et compléter le reste par des
 * zéros donnerait une cartouche tronquée qui démarre puis part n'importe où.
 *
 * Le descripteur reste la propriété de l'appelant : rien n'est fermé ici.
 *
 * @throws std::runtime_error sur erreur de lecture ou fin prématurée.
 */
[[nodiscard]] inline std::vector<std::uint8_t> read_exactly(int descriptor, std::size_t count) {
    std::vector<std::uint8_t> data(count);
    std::size_t total = 0;
    while (total < count) {
        const auto got = ::read(descriptor, data.data() + total, count - total);
        if (got < 0) {
            if (errno == EINTR) continue;
            throw std::runtime_error("Lecture impossible depuis le descripteur");
        }
        if (got == 0) throw std::runtime_error("Fichier plus court que sa taille annoncée");
        total += static_cast<std::size_t>(got);
    }
    return data;
}

} // namespace ravenemu
