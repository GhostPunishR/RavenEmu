package com.ravenemu.emulation.api

/**
 * Boutons logiques transmis au moteur. Chaque moteur mappe ces valeurs sur
 * son matériel ; les consoles futures pourront étendre ce vocabulaire via une
 * nouvelle énumération sans casser les moteurs existants.
 */
enum class EmulatorButton {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    A,
    B,
    START,
    SELECT,

    // Gâchettes d'épaule : présentes sur Game Boy Advance, ignorées par les
    // consoles qui n'en ont pas (Game Boy).
    L,
    R,

    // Touches de la Nintendo DS, ignorées par les consoles qui n'en ont pas.
    // L'ordre de cette énumération est celui que le pont natif transporte : une
    // valeur ajoutée se met à la fin, jamais au milieu.
    X,
    Y,
}
