package com.ravenemu.core.gba

import java.lang.management.ManagementFactory

/**
 * Mesure les octets alloués par le fil courant, via l'extension HotSpot
 * `com.sun.management.ThreadMXBean`. La mesure n'est **pas** disponible sur
 * toutes les JVM : [supported] permet aux tests de dégrader proprement plutôt
 * que d'échouer sur une machine d'intégration continue exotique.
 *
 * Ce fichier n'existe que dans les sources de test : le moteur reste indépendant
 * de `java.lang.management`.
 */
object AllocationProbe {

    private val bean: com.sun.management.ThreadMXBean? = runCatching {
        val candidate = ManagementFactory.getThreadMXBean()
        (candidate as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }
            ?.also { it.isThreadAllocatedMemoryEnabled = true }
    }.getOrNull()

    /** `true` si la JVM sait rapporter les allocations par fil. */
    val supported: Boolean get() = bean != null

    /** Octets alloués par le fil courant depuis son démarrage, ou -1. */
    fun allocatedBytes(): Long =
        bean?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L

    /**
     * Exécute [block] et retourne les octets alloués pendant son exécution.
     * Retourne -1 si la mesure n'est pas disponible.
     *
     * [warmups] itérations préalables laissent le compilateur juste-à-temps
     * remplacer les chemins interprétés, qui allouent des structures internes
     * étrangères au code mesuré.
     */
    fun measure(warmups: Int = 3, block: () -> Unit): Long {
        repeat(warmups) { block() }
        if (!supported) return -1L
        val before = allocatedBytes()
        block()
        return allocatedBytes() - before
    }
}
