package com.ravenemu.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Éléments de l'interface tactile. */
enum class ControlId { DPAD, BUTTON_A, BUTTON_B, START, SELECT, MENU, BUTTON_L, BUTTON_R }

/**
 * Position et apparence d'un élément. Les coordonnées sont **relatives**
 * (0..1) à la zone d'affichage, jamais en pixels : la disposition reste
 * valable sur toute taille et densité d'écran.
 */
@Serializable
data class ControlElement(
    val id: ControlId,
    val centerX: Float,
    val centerY: Float,
    /** Facteur de taille appliqué à la dimension de base de l'élément. */
    val scale: Float = 1f,
    /** Opacité de dessin 0..1. */
    val opacity: Float = 0.55f,
    val visible: Boolean = true,
    /** Extension de la zone tactile au-delà du dessin (1 = zone dessinée). */
    val touchScale: Float = 1.15f,
) {
    fun clamped(): ControlElement = copy(
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f),
        scale = scale.coerceIn(0.5f, 2.5f),
        opacity = opacity.coerceIn(0.1f, 1f),
        touchScale = touchScale.coerceIn(0.8f, 2f),
    )
}

/**
 * Disposition complète des commandes tactiles, sérialisable pour les profils
 * (portrait/paysage, par jeu).
 */
@Serializable
data class ControlLayout(
    val elements: List<ControlElement>,
    val hapticFeedback: Boolean = true,
    /** Verrouillage : l'éditeur refuse toute modification. */
    val locked: Boolean = false,
) {
    fun element(id: ControlId): ControlElement? = elements.firstOrNull { it.id == id }

    fun with(element: ControlElement): ControlLayout = copy(
        elements = elements.map { if (it.id == element.id) element.clamped() else it }
    )

    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(json: String): ControlLayout? = try {
            codec.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            null
        }

        /**
         * Skin portrait RavenEmu GB/GBC. Le menu ouvre la première rangée de
         * commandes, centré sous l'écran ; les commandes de jeu restent plus
         * bas, à portée des deux pouces.
         *
         * Les gâchettes font toujours partie du modèle afin qu'un profil
         * sérialisé reste compatible entre versions, mais elles sont masquées.
         */
        fun ravenGbPortrait(
            screenBottomFraction: Float = GB_REFERENCE_SCREEN_BOTTOM,
        ): ControlLayout {
            val menuY = menuRowY(
                screenBottomFraction = screenBottomFraction,
                gap = GB_MENU_GAP,
                minimum = GB_MENU_MIN_Y,
                maximum = GB_MENU_MAX_Y,
            )
            return ControlLayout(
                elements = listOf(
                    ControlElement(ControlId.DPAD, centerX = 0.20f, centerY = 0.69f, opacity = 0.92f),
                    ControlElement(ControlId.BUTTON_A, centerX = 0.84f, centerY = 0.63f, opacity = 0.94f),
                    ControlElement(ControlId.BUTTON_B, centerX = 0.69f, centerY = 0.70f, opacity = 0.90f),
                    ControlElement(ControlId.SELECT, centerX = 0.39f, centerY = 0.88f, opacity = 0.86f),
                    ControlElement(ControlId.START, centerX = 0.61f, centerY = 0.88f, opacity = 0.86f),
                    ControlElement(ControlId.MENU, centerX = 0.50f, centerY = menuY, opacity = 0.90f),
                    ControlElement(
                        ControlId.BUTTON_L,
                        centerX = 0.16f,
                        centerY = menuY,
                        opacity = 0.88f,
                        visible = false,
                    ),
                    ControlElement(
                        ControlId.BUTTON_R,
                        centerX = 0.84f,
                        centerY = menuY,
                        opacity = 0.88f,
                        visible = false,
                    ),
                )
            )
        }

        /**
         * Skin portrait RavenEmu GBA. L, MENU et R forment une rangée sous
         * l'écran large ; les autres commandes reprennent l'ergonomie GB/GBC.
         */
        fun ravenGbaPortrait(
            screenBottomFraction: Float = GBA_REFERENCE_SCREEN_BOTTOM,
        ): ControlLayout {
            val shoulderRowY = menuRowY(
                screenBottomFraction = screenBottomFraction,
                gap = GBA_MENU_GAP,
                minimum = GBA_MENU_MIN_Y,
                maximum = GBA_MENU_MAX_Y,
            )
            return ControlLayout(
                elements = listOf(
                    ControlElement(ControlId.DPAD, centerX = 0.20f, centerY = 0.68f, opacity = 0.92f),
                    ControlElement(ControlId.BUTTON_A, centerX = 0.84f, centerY = 0.62f, opacity = 0.94f),
                    ControlElement(ControlId.BUTTON_B, centerX = 0.69f, centerY = 0.69f, opacity = 0.90f),
                    ControlElement(ControlId.SELECT, centerX = 0.39f, centerY = 0.88f, opacity = 0.86f),
                    ControlElement(ControlId.START, centerX = 0.61f, centerY = 0.88f, opacity = 0.86f),
                    ControlElement(
                        ControlId.MENU,
                        centerX = 0.50f,
                        centerY = shoulderRowY,
                        opacity = 0.90f,
                    ),
                    ControlElement(
                        ControlId.BUTTON_L,
                        centerX = 0.17f,
                        centerY = shoulderRowY,
                        opacity = 0.88f,
                    ),
                    ControlElement(
                        ControlId.BUTTON_R,
                        centerX = 0.83f,
                        centerY = shoulderRowY,
                        opacity = 0.88f,
                    ),
                )
            )
        }

        /**
         * Point d'entrée historique conservé pour les appelants et profils
         * existants. Chaque console reçoit désormais son portrait dédié.
         */
        fun defaultPortrait(withShoulders: Boolean = false): ControlLayout =
            if (withShoulders) ravenGbaPortrait() else ravenGbPortrait()

        /** Disposition par défaut en paysage : commandes de part et d'autre. */
        fun defaultLandscape(withShoulders: Boolean = false): ControlLayout = ControlLayout(
            elements = listOf(
                ControlElement(ControlId.DPAD, centerX = 0.12f, centerY = 0.70f),
                ControlElement(ControlId.BUTTON_A, centerX = 0.93f, centerY = 0.60f),
                ControlElement(ControlId.BUTTON_B, centerX = 0.84f, centerY = 0.74f),
                ControlElement(ControlId.SELECT, centerX = 0.80f, centerY = 0.93f),
                ControlElement(ControlId.START, centerX = 0.90f, centerY = 0.93f),
                ControlElement(ControlId.MENU, centerX = 0.96f, centerY = 0.06f),
                ControlElement(ControlId.BUTTON_L, centerX = 0.08f, centerY = 0.12f, visible = withShoulders),
                ControlElement(ControlId.BUTTON_R, centerX = 0.92f, centerY = 0.12f, visible = withShoulders),
            )
        )

        private fun menuRowY(
            screenBottomFraction: Float,
            gap: Float,
            minimum: Float,
            maximum: Float,
        ): Float = (screenBottomFraction.coerceIn(0f, 1f) + gap).coerceIn(minimum, maximum)

        private const val GB_REFERENCE_SCREEN_BOTTOM = 0.42f
        private const val GBA_REFERENCE_SCREEN_BOTTOM = 0.32f
        private const val GB_MENU_GAP = 0.055f
        private const val GBA_MENU_GAP = 0.05f
        private const val GB_MENU_MIN_Y = 0.47f
        private const val GB_MENU_MAX_Y = 0.56f
        private const val GBA_MENU_MIN_Y = 0.37f
        private const val GBA_MENU_MAX_Y = 0.44f
    }
}
