package com.ravenemu.deltaskin

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

data class DeltaSkinValidation(
    val manifest: DeltaSkinManifest,
    val console: DeltaSkinConsole,
    val portraitKinds: Set<DeltaSkinRepresentationKind>,
    val ignoredInputs: Set<String>,
)

object DeltaSkinValidator {
    const val MAX_LOGICAL_VALUE = 100_000.0
    private const val MAX_TEXT_LENGTH = 512
    private const val FRAME_EPSILON = 0.000_1
    private const val MAX_FRAME_OVERSCAN_RATIO = 0.01

    fun validate(manifest: DeltaSkinManifest): DeltaSkinValidation {
        requireText(manifest.name, "name")
        requireText(manifest.identifier, "identifier")
        val console = DeltaSkinConsole.fromGameTypeIdentifier(manifest.gameTypeIdentifier)
            ?: throw DeltaSkinException(
                DeltaSkinErrorCode.UNSUPPORTED_CONSOLE,
                "Cette console n'est pas prise en charge",
            )
        val iphone = manifest.representations.iphone
            ?: throw DeltaSkinException(
                DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING,
                "Aucune représentation iPhone portrait n'est disponible",
            )
        val kinds = iphone.availablePortraitKinds()
        if (kinds.isEmpty()) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING,
                "Aucune représentation iPhone portrait n'est disponible",
            )
        }

        val ignoredInputs = linkedSetOf<String>()
        val knownRepresentations = listOfNotNull(
            iphone.standard?.portrait,
            iphone.standard?.landscape,
            iphone.edgeToEdge?.portrait,
            iphone.edgeToEdge?.landscape,
        )
        for (representation in knownRepresentations) {
            validateRepresentation(
                representation = representation,
                console = console,
                ignoredInputs = ignoredInputs,
            )
        }

        return DeltaSkinValidation(
            manifest = manifest,
            console = console,
            portraitKinds = kinds,
            ignoredInputs = ignoredInputs,
        )
    }

    fun isSafeRootAssetName(name: String): Boolean {
        if (name.isBlank() || name.length > MAX_TEXT_LENGTH) return false
        if (name.startsWith("/") || name.startsWith("\\") || WINDOWS_DRIVE.matches(name)) {
            return false
        }
        if (
            name.any {
                it == '/' ||
                    it == '\\' ||
                    it == '\u2215' ||
                    it == '\u2044' ||
                    it == '\u29f8' ||
                    it == '\uff0f' ||
                    it == '\uff3c' ||
                    it == '\ufe68' ||
                    it == '\u0000'
            }
        ) {
            return false
        }
        if (name.any { it.code < 0x20 }) return false
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        return normalized != "." && normalized != ".."
    }

    private fun validateRepresentation(
        representation: DeltaSkinRepresentation,
        console: DeltaSkinConsole,
        ignoredInputs: MutableSet<String>,
    ) {
        val mapping = representation.mappingSize
        requirePositiveNumber(
            mapping.width,
            DeltaSkinErrorCode.INVALID_MAPPING_SIZE,
            "La largeur mappingSize est invalide",
        )
        requirePositiveNumber(
            mapping.height,
            DeltaSkinErrorCode.INVALID_MAPPING_SIZE,
            "La hauteur mappingSize est invalide",
        )

        val asset = representation.assets.resizable
        if (
            asset.isNullOrBlank() ||
            !isSafeRootAssetName(asset) ||
            !asset.lowercase(Locale.ROOT).endsWith(".pdf")
        ) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING,
                "La représentation portrait ne référence pas un PDF resizable valide",
            )
        }

        validateEdges(representation.extendedEdges)
        for (item in representation.items) {
            validateFrame(item.frame, mapping, mustFitMapping = true)
            validateEdges(item.extendedEdges)
            validateInputs(item.inputs, console, ignoredInputs)
        }
        representation.gameScreenFrame?.let {
            validateFrame(it, mapping, mustFitMapping = true)
        }
        for (screen in representation.screens) {
            screen.inputFrame?.let { validateFrame(it, null, mustFitMapping = false) }
            validateFrame(screen.outputFrame, mapping, mustFitMapping = true)
        }
    }

    private fun validateInputs(
        inputs: DeltaSkinInputs,
        console: DeltaSkinConsole,
        ignoredInputs: MutableSet<String>,
    ) {
        val values = when (inputs) {
            is DeltaSkinInputs.Buttons -> {
                if (inputs.values.isEmpty()) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY,
                        "Un item doit contenir au moins une entrée",
                    )
                }
                inputs.values
            }
            is DeltaSkinInputs.Directional -> {
                val required = setOf("up", "down", "left", "right")
                if (!inputs.values.keys.containsAll(required)) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY,
                        "Un D-pad doit déclarer up, down, left et right",
                    )
                }
                inputs.values.values.toList()
            }
        }
        values.forEach { raw ->
            val value = raw.trim().lowercase(Locale.ROOT)
            if (value !in supportedInputs(console)) ignoredInputs += raw
        }
    }

    private fun supportedInputs(console: DeltaSkinConsole): Set<String> = when (console) {
        DeltaSkinConsole.GB_GBC -> GB_INPUTS
        DeltaSkinConsole.GBA -> GBA_INPUTS
    }

    private fun validateEdges(edges: DeltaSkinEdges) {
        listOf(edges.top, edges.bottom, edges.left, edges.right)
            .filterNotNull()
            .forEach { value ->
                requireNonNegativeNumber(
                    value,
                    DeltaSkinErrorCode.INVALID_NUMBER,
                    "Une valeur extendedEdges est invalide",
                )
            }
    }

    private fun validateFrame(
        frame: DeltaSkinFrame,
        mapping: DeltaSkinSize?,
        mustFitMapping: Boolean,
    ) {
        requireNonNegativeNumber(
            frame.x,
            DeltaSkinErrorCode.INVALID_FRAME,
            "La coordonnée x d'une frame est invalide",
        )
        requireNonNegativeNumber(
            frame.y,
            DeltaSkinErrorCode.INVALID_FRAME,
            "La coordonnée y d'une frame est invalide",
        )
        requirePositiveNumber(
            frame.width,
            DeltaSkinErrorCode.INVALID_FRAME,
            "La largeur d'une frame est invalide",
        )
        requirePositiveNumber(
            frame.height,
            DeltaSkinErrorCode.INVALID_FRAME,
            "La hauteur d'une frame est invalide",
        )
        if (
            mustFitMapping &&
            mapping != null &&
            (
                frame.x >= mapping.width ||
                    frame.y >= mapping.height ||
                    frame.x + frame.width > maximumFrameExtent(mapping.width) ||
                    frame.y + frame.height > maximumFrameExtent(mapping.height)
                )
        ) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_FRAME,
                "Une frame dépasse mappingSize",
            )
        }
    }

    /**
     * Certains skins Delta arrondissent une frame de bordure un peu au-delà
     * de mappingSize. La hitbox est ensuite bornée au panneau par l'input mapper.
     */
    private fun maximumFrameExtent(mappingExtent: Double): Double =
        mappingExtent + max(FRAME_EPSILON, mappingExtent * MAX_FRAME_OVERSCAN_RATIO)

    private fun requireText(value: String, property: String) {
        if (value.isBlank() || value.length > MAX_TEXT_LENGTH || '\u0000' in value) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY,
                "La propriété $property est vide ou excessive",
            )
        }
    }

    private fun requirePositiveNumber(
        value: Double,
        code: DeltaSkinErrorCode,
        message: String,
    ) {
        if (!value.isFinite() || value <= 0.0 || value > MAX_LOGICAL_VALUE) {
            throw DeltaSkinException(code, message)
        }
    }

    private fun requireNonNegativeNumber(
        value: Double,
        code: DeltaSkinErrorCode,
        message: String,
    ) {
        if (!value.isFinite() || value < 0.0 || value > MAX_LOGICAL_VALUE) {
            throw DeltaSkinException(code, message)
        }
    }

    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
    private val GB_INPUTS =
        setOf("up", "down", "left", "right", "a", "b", "start", "select", "menu")
    private val GBA_INPUTS = GB_INPUTS + setOf("l", "r")
}
