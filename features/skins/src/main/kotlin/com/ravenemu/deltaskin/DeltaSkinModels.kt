package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Consoles Delta prises en charge.
 *
 * Chacune porte les dimensions de son image, parce qu'un skin encadre un écran
 * dont il ignore la taille : c'est la console qui la connaît, et la calculer
 * ailleurs obligerait chaque appelant à la redire.
 *
 * La Nintendo DS en a deux, empilés dans un seul tampon : sa hauteur est donc
 * le double de celle d'un écran, et c'est ce que le skin encadre.
 */
@Serializable
enum class DeltaSkinConsole(
    val gameTypeIdentifier: String,
    val screenWidth: Double,
    val screenHeight: Double,
    /** Touches que cette console possède réellement. */
    val buttons: Set<EmulatorButton>,
) {
    GB_GBC("com.rileytestut.delta.game.gbc", 160.0, 144.0, DeltaSkinInputNames.FACE_BUTTONS),
    GBA(
        "com.rileytestut.delta.game.gba",
        240.0,
        160.0,
        DeltaSkinInputNames.FACE_BUTTONS + DeltaSkinInputNames.SHOULDER_BUTTONS,
    ),
    DS(
        "com.rileytestut.delta.game.ds",
        256.0,
        384.0,
        DeltaSkinInputNames.FACE_BUTTONS +
            DeltaSkinInputNames.SHOULDER_BUTTONS +
            DeltaSkinInputNames.EXTRA_BUTTONS,
    ),
    ;

    /** Dimensions de l'image que ce skin encadre. */
    val screenSize: DeltaSkinSize get() = DeltaSkinSize(screenWidth, screenHeight)

    /** Noms Delta que cette console sait honorer, menu compris. */
    val supportedInputNames: Set<String>
        get() = DeltaSkinInputNames.BUTTONS
            .filterValues { it in buttons }
            .keys + DeltaSkinInputNames.MENU

    companion object {
        fun fromGameTypeIdentifier(identifier: String): DeltaSkinConsole? =
            entries.firstOrNull { it.gameTypeIdentifier == identifier }
    }
}

/**
 * Noms que Delta donne aux entrées, et ce qu'ils désignent.
 *
 * Une seule table, consultée par le contrôle des archives comme par la
 * traduction des appuis. Deux tables dériveraient : un skin serait accepté avec
 * une touche que rien ne presse ensuite, sans que rien ne le signale.
 */
object DeltaSkinInputNames {
    val BUTTONS: Map<String, EmulatorButton> = linkedMapOf(
        "up" to EmulatorButton.UP,
        "down" to EmulatorButton.DOWN,
        "left" to EmulatorButton.LEFT,
        "right" to EmulatorButton.RIGHT,
        "a" to EmulatorButton.A,
        "b" to EmulatorButton.B,
        "start" to EmulatorButton.START,
        "select" to EmulatorButton.SELECT,
        "l" to EmulatorButton.L,
        "r" to EmulatorButton.R,
        "x" to EmulatorButton.X,
        "y" to EmulatorButton.Y,
    )

    /** Entrée qui n'appartient à aucune console : elle ouvre le menu de l'application. */
    const val MENU = "menu"

    val FACE_BUTTONS: Set<EmulatorButton> = setOf(
        EmulatorButton.UP,
        EmulatorButton.DOWN,
        EmulatorButton.LEFT,
        EmulatorButton.RIGHT,
        EmulatorButton.A,
        EmulatorButton.B,
        EmulatorButton.START,
        EmulatorButton.SELECT,
    )
    val SHOULDER_BUTTONS: Set<EmulatorButton> = setOf(EmulatorButton.L, EmulatorButton.R)
    val EXTRA_BUTTONS: Set<EmulatorButton> = setOf(EmulatorButton.X, EmulatorButton.Y)

    /** Les deux axes que Delta emploie pour désigner la zone tactile. */
    val TOUCH_SCREEN_AXES: Set<String> = setOf("x", "y")
    val TOUCH_SCREEN_VALUES: Set<String> = setOf("touchscreenx", "touchscreeny")

    /** Les quatre directions qu'une croix doit déclarer. */
    val DIRECTIONS: Set<String> = setOf("up", "down", "left", "right")
}

@Serializable
enum class DeltaSkinRepresentationKind {
    STANDARD,
    EDGE_TO_EDGE,
}

@Serializable
enum class DeltaSkinRepresentationPreference {
    AUTOMATIC,
    STANDARD,
    EDGE_TO_EDGE,
}

@Serializable
data class DeltaSkinManifest(
    val name: String,
    val identifier: String,
    val gameTypeIdentifier: String,
    val debug: Boolean,
    val representations: DeltaSkinRepresentations,
)

@Serializable
data class DeltaSkinRepresentations(
    val iphone: DeltaSkinIphoneRepresentations? = null,
)

@Serializable
data class DeltaSkinIphoneRepresentations(
    val standard: DeltaSkinOrientations? = null,
    val edgeToEdge: DeltaSkinOrientations? = null,
) {
    fun portrait(kind: DeltaSkinRepresentationKind): DeltaSkinRepresentation? = when (kind) {
        DeltaSkinRepresentationKind.STANDARD -> standard?.portrait
        DeltaSkinRepresentationKind.EDGE_TO_EDGE -> edgeToEdge?.portrait
    }

    fun availablePortraitKinds(): Set<DeltaSkinRepresentationKind> =
        DeltaSkinRepresentationKind.entries.filterTo(linkedSetOf()) { portrait(it) != null }
}

@Serializable
data class DeltaSkinOrientations(
    val portrait: DeltaSkinRepresentation? = null,
    /** Conservée pour une future prise en charge, jamais rendue dans cette version. */
    val landscape: DeltaSkinRepresentation? = null,
)

@Serializable
data class DeltaSkinRepresentation(
    val assets: DeltaSkinAssets,
    val mappingSize: DeltaSkinSize,
    val items: List<DeltaSkinItem>,
    val extendedEdges: DeltaSkinEdges = DeltaSkinEdges(),
    val translucent: Boolean = false,
    val gameScreenFrame: DeltaSkinFrame? = null,
    val screens: List<DeltaSkinScreen> = emptyList(),
)

@Serializable
data class DeltaSkinAssets(
    val resizable: String? = null,
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
data class DeltaSkinSize(
    val width: Double,
    val height: Double,
)

@Serializable
data class DeltaSkinFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Valeurs optionnelles : l'héritage se fait côté par côté entre l'item et la
 * représentation, pas dictionnaire par dictionnaire.
 */
@Serializable
data class DeltaSkinEdges(
    val top: Double? = null,
    val bottom: Double? = null,
    val left: Double? = null,
    val right: Double? = null,
) {
    fun resolvedOver(parent: DeltaSkinEdges): DeltaSkinResolvedEdges = DeltaSkinResolvedEdges(
        top = top ?: parent.top ?: 0.0,
        bottom = bottom ?: parent.bottom ?: 0.0,
        left = left ?: parent.left ?: 0.0,
        right = right ?: parent.right ?: 0.0,
    )
}

data class DeltaSkinResolvedEdges(
    val top: Double,
    val bottom: Double,
    val left: Double,
    val right: Double,
)

@Serializable
data class DeltaSkinItem(
    val inputs: DeltaSkinInputs,
    val frame: DeltaSkinFrame,
    val extendedEdges: DeltaSkinEdges = DeltaSkinEdges(),
)

@Serializable(with = DeltaSkinInputsSerializer::class)
sealed interface DeltaSkinInputs {
    data class Buttons(val values: List<String>) : DeltaSkinInputs

    data class Directional(val values: Map<String, String>) : DeltaSkinInputs {
        /**
         * Vrai quand ce dictionnaire décrit la zone tactile, non une croix.
         *
         * Delta se sert de la même forme pour les deux, et seules les clés les
         * distinguent : une croix nomme quatre directions, la zone tactile deux
         * axes. Les confondre découperait l'écran tactile en neuf cases de
         * direction, ce qui presserait des touches au toucher.
         */
        val isTouchScreen: Boolean
            get() {
                val keys = values.keys.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
                if (keys != DeltaSkinInputNames.TOUCH_SCREEN_AXES) return false
                val named = values.values.mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }
                return named == DeltaSkinInputNames.TOUCH_SCREEN_VALUES
            }
    }
}

/**
 * Delta encode un bouton simple ou combiné comme tableau, mais un D-pad comme
 * dictionnaire. Le sérialiseur conserve exactement cette distinction.
 */
object DeltaSkinInputsSerializer : KSerializer<DeltaSkinInputs> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): DeltaSkinInputs {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("DeltaSkinInputs ne peut être décodé qu'en JSON")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonArray -> DeltaSkinInputs.Buttons(
                element.map { value ->
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                        ?: throw IllegalArgumentException("Une entrée de bouton doit être une chaîne")
                }
            )
            is JsonObject -> DeltaSkinInputs.Directional(
                element.mapValues { (_, value) ->
                    (value as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                        ?: throw IllegalArgumentException(
                            "Une direction de D-pad doit référencer une chaîne"
                        )
                }
            )
            else -> throw IllegalArgumentException(
                "inputs doit être un tableau de boutons ou un dictionnaire de directions"
            )
        }
    }

    override fun serialize(encoder: Encoder, value: DeltaSkinInputs) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("DeltaSkinInputs ne peut être encodé qu'en JSON")
        val element = when (value) {
            is DeltaSkinInputs.Buttons ->
                JsonArray(value.values.map(::JsonPrimitive))
            is DeltaSkinInputs.Directional ->
                JsonObject(value.values.mapValues { JsonPrimitive(it.value) })
        }
        jsonEncoder.encodeJsonElement(element)
    }
}

@Serializable
data class DeltaSkinScreen(
    val inputFrame: DeltaSkinFrame? = null,
    val outputFrame: DeltaSkinFrame,
)

@Serializable
data class DeltaSkinInstalledMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val storageKey: String,
    val manifest: DeltaSkinManifest,
    val console: DeltaSkinConsole,
    val archiveSha256: String,
    val importedAtEpochMillis: Long,
    val archiveSizeBytes: Long,
    val standardPortraitAsset: String? = null,
    val edgeToEdgePortraitAsset: String? = null,
    val ignoredInputs: List<String> = emptyList(),
) {
    fun storedAssetName(kind: DeltaSkinRepresentationKind): String? = when (kind) {
        DeltaSkinRepresentationKind.STANDARD -> standardPortraitAsset
        DeltaSkinRepresentationKind.EDGE_TO_EDGE -> edgeToEdgePortraitAsset
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
