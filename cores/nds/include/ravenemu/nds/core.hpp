#pragma once

#include <ravenemu/core.hpp>
#include <ravenemu/nds/cartridge_header.hpp>

namespace ravenemu::nds {

/** Largeur d'un écran Nintendo DS, en pixels. */
inline constexpr int screen_width = 256;
/** Hauteur d'un écran Nintendo DS, en pixels. */
inline constexpr int screen_height = 192;

/**
 * Hauteur du tampon vidéo présenté à l'hôte.
 *
 * La console a deux écrans ; le contrat vidéo de RavenEmu n'en décrit qu'un.
 * Plutôt que d'élargir ce contrat pour une seule console, les deux écrans sont
 * empilés dans un tampon unique : l'écran haut occupe les 192 premières lignes,
 * l'écran bas les 192 suivantes.
 *
 * Ce choix garde le contrat existant intact et laisse l'agencement réel — côte
 * à côte, un seul écran, proportions libres — à la couche qui affiche, qui est
 * la seule à connaître la taille et l'orientation de l'appareil.
 */
inline constexpr int framebuffer_height = screen_height * 2;

/** Première ligne de l'écran bas dans le tampon empilé. */
inline constexpr int bottom_screen_first_row = screen_height;

/**
 * Fréquence de rafraîchissement, en hertz.
 *
 * Horloge maître de 33,513982 MHz pour 560 190 cycles par trame, soit
 * 355 points sur 263 lignes à six cycles par point.
 */
inline constexpr double refresh_rate_hz = 33'513'982.0 / 560'190.0;

/**
 * Cœur Nintendo DS.
 *
 * **Ce cœur n'émule encore rien.** Il porte l'identité de la console, décode et
 * contrôle l'en-tête de cartouche, et publie les caractéristiques vidéo et
 * audio. Toute demande d'exécution est refusée par une erreur explicite plutôt
 * que servie par un écran noir, qui laisserait croire à une émulation muette.
 */
[[nodiscard]] std::unique_ptr<Core> make_core();

} // namespace ravenemu::nds
