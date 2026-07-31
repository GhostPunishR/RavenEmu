package com.ravenemu.emulation.api

/**
 * Fabrique de moteurs d'émulation : à partir d'un [ConsoleType], produit le
 * [EmulatorCore] correspondant.
 *
 * L'application ne doit plus instancier directement un moteur concret (ni
 * `GameBoyCore`, ni `GbaCore`) : elle passe par cette fabrique, ce qui permet
 * d'ajouter une console sans modifier le code d'appel. L'implémentation vit
 * dans le module qui connaît tous les moteurs (racine de composition) ; ce
 * contrat, lui, reste dans `emulation-api`.
 */
interface EmulatorCoreFactory {
    /**
     * Crée un moteur neuf pour [console].
     *
     * @throws IllegalArgumentException si aucune console de ce type n'est prise
     *   en charge par cette fabrique.
     */
    fun create(console: ConsoleType): EmulatorCore

    /** Consoles pour lesquelles [create] retourne un moteur. */
    val supportedConsoles: Set<ConsoleType>
}
