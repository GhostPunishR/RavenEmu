package com.ravenemu.emulation.api

/**
 * Ensemble des consoles disponibles dans une exécution donnée.
 *
 * C'est le seul objet que l'application consulte pour savoir quelles consoles
 * existent, quels fichiers elles savent lire et comment construire leur moteur.
 * Ajouter une console revient à passer un [ConsoleProvider] de plus à la racine
 * de composition ; aucun `when` sur [ConsoleType] n'a plus à être retrouvé
 * ailleurs dans le code.
 *
 * Le registre est immuable et se valide à la construction : deux fournisseurs
 * pour la même console sont une erreur de câblage, pas un cas à départager au
 * hasard à l'exécution.
 */
class ConsoleRegistry(providers: List<ConsoleProvider>) : EmulatorCoreFactory {

    private val byConsole: Map<ConsoleType, ConsoleProvider> = buildMap {
        for (provider in providers) {
            val existing = put(provider.console, provider)
            require(existing == null) {
                "Deux fournisseurs déclarent la console ${provider.console.name} : " +
                    "${existing!!::class.simpleName} et ${provider::class.simpleName}"
            }
        }
    }

    /** Fournisseurs enregistrés, dans l'ordre de déclaration. */
    val providers: List<ConsoleProvider> = byConsole.values.toList()

    override val supportedConsoles: Set<ConsoleType> = byConsole.keys

    /** Fournisseur de [console], ou `null` si elle n'est pas prise en charge. */
    fun providerFor(console: ConsoleType): ConsoleProvider? = byConsole[console]

    /**
     * Fournisseur susceptible de lire [fileName], d'après son extension.
     *
     * Les extensions ne sont pas exclusives d'une console à l'autre : le
     * premier fournisseur déclaré l'emporte, ce qui rend l'arbitrage
     * explicite et reproductible plutôt que dépendant d'un ordre de parcours.
     */
    fun providerForFile(fileName: String): ConsoleProvider? =
        providers.firstOrNull { it.handles(fileName) }

    override fun create(console: ConsoleType): EmulatorCore {
        val provider = byConsole[console]
        requireNotNull(provider) { "Console non prise en charge : ${console.name}" }
        return provider.createCore()
    }
}
