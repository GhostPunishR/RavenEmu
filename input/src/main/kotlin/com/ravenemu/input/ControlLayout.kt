package com.ravenemu.input

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Éléments de l'interface tactile. */
enum class ControlId { DPAD, BUTTON_A, BUTTON_B, START, SELECT, MENU }

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
         * Disposition par défaut en portrait. L'écran de jeu étant ancré en
         * haut, les commandes occupent le tiers médian-bas, à portée de
         * pouce, en laissant la bordure basse aux gestes système.
         */
        fun defaultPortrait(): ControlLayout = ControlLayout(
            elements = listOf(
                ControlElement(ControlId.DPAD, centerX = 0.20f, centerY = 0.70f),
                ControlElement(ControlId.BUTTON_A, centerX = 0.87f, centerY = 0.63f),
                ControlElement(ControlId.BUTTON_B, centerX = 0.71f, centerY = 0.72f),
                ControlElement(ControlId.SELECT, centerX = 0.38f, centerY = 0.88f),
                ControlElement(ControlId.START, centerX = 0.62f, centerY = 0.88f),
                ControlElement(ControlId.MENU, centerX = 0.94f, centerY = 0.04f),
            )
        )

        /** Disposition par défaut en paysage : commandes de part et d'autre. */
        fun defaultLandscape(): ControlLayout = ControlLayout(
            elements = listOf(
                ControlElement(ControlId.DPAD, centerX = 0.12f, centerY = 0.70f),
                ControlElement(ControlId.BUTTON_A, centerX = 0.93f, centerY = 0.60f),
                ControlElement(ControlId.BUTTON_B, centerX = 0.84f, centerY = 0.74f),
                ControlElement(ControlId.SELECT, centerX = 0.80f, centerY = 0.93f),
                ControlElement(ControlId.START, centerX = 0.90f, centerY = 0.93f),
                ControlElement(ControlId.MENU, centerX = 0.96f, centerY = 0.06f),
            )
        )
    }
}
