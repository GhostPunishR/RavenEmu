package com.ravenemu.deltaskin

object DeltaSkinRepresentationSelector {
    /**
     * Seuil volontairement isolé : un téléphone dont la zone portrait vaut au
     * moins deux fois sa largeur est considéré haut.
     */
    const val EDGE_TO_EDGE_ASPECT_RATIO = 2.0

    fun select(
        availableKinds: Set<DeltaSkinRepresentationKind>,
        preference: DeltaSkinRepresentationPreference,
        availableWidth: Double,
        availableHeight: Double,
    ): DeltaSkinRepresentationKind? {
        if (availableKinds.isEmpty()) return null
        val preferred = when (preference) {
            DeltaSkinRepresentationPreference.STANDARD ->
                DeltaSkinRepresentationKind.STANDARD
            DeltaSkinRepresentationPreference.EDGE_TO_EDGE ->
                DeltaSkinRepresentationKind.EDGE_TO_EDGE
            DeltaSkinRepresentationPreference.AUTOMATIC -> {
                val ratio = if (availableWidth > 0.0) {
                    availableHeight / availableWidth
                } else {
                    0.0
                }
                if (ratio >= EDGE_TO_EDGE_ASPECT_RATIO) {
                    DeltaSkinRepresentationKind.EDGE_TO_EDGE
                } else {
                    DeltaSkinRepresentationKind.STANDARD
                }
            }
        }
        if (preferred in availableKinds) return preferred
        return when (preferred) {
            DeltaSkinRepresentationKind.STANDARD ->
                DeltaSkinRepresentationKind.EDGE_TO_EDGE.takeIf { it in availableKinds }
            DeltaSkinRepresentationKind.EDGE_TO_EDGE ->
                DeltaSkinRepresentationKind.STANDARD.takeIf { it in availableKinds }
        }
    }
}
