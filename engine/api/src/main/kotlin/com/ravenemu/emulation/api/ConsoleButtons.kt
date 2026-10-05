package com.ravenemu.emulation.api

/**
 * Les groupes de touches dont les consoles de la gamme se composent.
 *
 * Ils sont ici et non dans le compagnon de [ConsoleType] parce que les entrées
 * d'une énumération se construisent **avant** son compagnon : une entrée qui
 * s'y référerait lirait un objet encore vide. Les tenir à part est aussi plus
 * juste, un groupe de touches n'étant pas une propriété d'une console
 * particulière mais un morceau commun à plusieurs.
 */
object ConsoleButtons {
    /** Les huit touches que toute console de la gamme possède. */
    val FACE: Set<EmulatorButton> = setOf(
        EmulatorButton.UP,
        EmulatorButton.DOWN,
        EmulatorButton.LEFT,
        EmulatorButton.RIGHT,
        EmulatorButton.A,
        EmulatorButton.B,
        EmulatorButton.START,
        EmulatorButton.SELECT,
    )

    /** Les deux gâchettes d'épaule, à partir de la Game Boy Advance. */
    val SHOULDERS: Set<EmulatorButton> = setOf(EmulatorButton.L, EmulatorButton.R)

    /** Les deux touches que seule la Nintendo DS ajoute. */
    val X_AND_Y: Set<EmulatorButton> = setOf(EmulatorButton.X, EmulatorButton.Y)
}
