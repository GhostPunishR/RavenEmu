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
}
