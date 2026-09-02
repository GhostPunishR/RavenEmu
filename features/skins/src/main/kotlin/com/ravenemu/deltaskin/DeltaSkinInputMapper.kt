package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import java.util.Locale
import kotlin.math.floor

data class DeltaSkinVisualTarget(
    val itemIndex: Int,
    /** 0..8 pour une cellule de D-pad, null pour l'item complet. */
    val dpadCell: Int? = null,
    val rect: DeltaSkinRect,
)

/**
 * Point touché dans la zone tactile d'un skin, en fractions de cette zone.
 *
 * Des fractions plutôt que des pixels : ce module ne connaît la résolution
 * d'aucune console, et l'y faire entrer pour un seul cas rendrait le calcul faux
 * le jour où une console à écran tactile aurait une autre taille. La couche qui
 * pilote le moteur, elle, la connaît.
 */
data class DeltaSkinTouchPoint(
    val fractionX: Double,
    val fractionY: Double,
)

data class DeltaSkinMappedInput(
    val buttons: Set<EmulatorButton> = emptySet(),
    val menu: Boolean = false,
    val visuals: Set<DeltaSkinVisualTarget> = emptySet(),
    /** Position dans la zone tactile, ou `null` si le doigt est ailleurs. */
    val touch: DeltaSkinTouchPoint? = null,
)

/**
 * Applique exactement la même transformation au dessin et aux hitboxes.
 * L'extension tactile est bornée au panneau et ne modifie jamais la frame
 * visuelle utilisée pour découper le D-pad.
 */
class DeltaSkinInputMapper(
    private val console: DeltaSkinConsole,
    private val representation: DeltaSkinRepresentation,
    private val panel: DeltaSkinRect,
) {
    fun inputAt(x: Double, y: Double): DeltaSkinMappedInput {
        if (!panel.contains(x, y)) return DeltaSkinMappedInput()
        val buttons = linkedSetOf<EmulatorButton>()
        val visuals = linkedSetOf<DeltaSkinVisualTarget>()
        var menu = false
        var touch: DeltaSkinTouchPoint? = null

        val hitItems = representation.items.mapIndexedNotNull { index, item ->
            val visualFrame = DeltaSkinLayoutCalculator.mapFrame(
                item.frame,
                panel,
                representation.mappingSize,
            )
            val edges = item.extendedEdges.resolvedOver(representation.extendedEdges)
            val scaleX = panel.width / representation.mappingSize.width
            val scaleY = panel.height / representation.mappingSize.height
            val expanded = DeltaSkinRect(
                left = visualFrame.left - edges.left * scaleX,
                top = visualFrame.top - edges.top * scaleY,
                right = visualFrame.right + edges.right * scaleX,
                bottom = visualFrame.bottom + edges.bottom * scaleY,
            ).intersect(panel) ?: return@mapIndexedNotNull null
            if (!expanded.contains(x, y)) return@mapIndexedNotNull null
            HitItem(
                index = index,
                item = item,
                visualFrame = visualFrame,
                insideVisualFrame = visualFrame.contains(x, y),
            )
        }.let { hits ->
            // Une frame dessinée gagne sur l'extension d'une frame voisine.
            // Les extensions restent donc utiles dans les espaces vides sans
            // créer de combinaison accidentelle au-dessus d'un autre bouton.
            hits.filter(HitItem::insideVisualFrame).ifEmpty { hits }
        }

        hitItems.forEach { hit ->
            when (val inputs = hit.item.inputs) {
                is DeltaSkinInputs.Buttons -> {
                    val mapped = inputs.values.mapNotNull(::mapButton)
                    val itemHasMenu =
                        inputs.values.any { it.trim().equals("menu", true) }
                    buttons += mapped
                    menu = menu || itemHasMenu
                    if (mapped.isNotEmpty() || itemHasMenu) {
                        visuals += DeltaSkinVisualTarget(
                            hit.index,
                            rect = hit.visualFrame,
                        )
                    }
                }
                is DeltaSkinInputs.Directional -> {
                    // La zone tactile a la forme d'une croix sans en être une :
                    // la découper en neuf cases presserait des directions au
                    // toucher. Elle rend une position, jamais une direction.
                    if (inputs.isTouchScreen) {
                        // Une console sans écran tactile n'a rien pour recevoir
                        // ce contact : la zone reste inerte chez elle, comme les
                        // touches qu'elle n'a pas.
                        if (console.hasTouchScreen) {
                            touch = touchPointIn(x, y, hit.visualFrame)
                        }
                        return@forEach
                    }
                    val cell = dpadCell(x, y, hit.visualFrame)
                    val directions = directionsForCell(cell)
                    for (direction in directions) {
                        inputs.values[direction]?.let(::mapButton)?.let(buttons::add)
                    }
                    if (directions.any { inputs.values[it]?.trim()?.equals("menu", true) == true }) {
                        menu = true
                    }
                    if (directions.isNotEmpty()) {
                        visuals += DeltaSkinVisualTarget(
                            itemIndex = hit.index,
                            dpadCell = cell,
                            rect = dpadCellRect(hit.visualFrame, cell),
                        )
                    }
                }
            }
        }
        return DeltaSkinMappedInput(
            buttons = buttons,
            menu = menu,
            visuals = visuals,
            touch = touch,
        )
    }

    /**
     * Position du doigt dans la zone, ramenée entre 0 et 1 sur chaque axe.
     *
     * La frame **dessinée** sert de repère, non son extension tactile : un doigt
     * qui déborde de la dalle est retenu sur son bord, comme un stylet qui sort
     * de l'écran d'une console. Une zone de largeur ou de hauteur nulle est
     * refusée plutôt que divisée par zéro : un skin peut en déclarer une, et
     * elle ne désigne aucun point.
     */
    private fun touchPointIn(x: Double, y: Double, frame: DeltaSkinRect): DeltaSkinTouchPoint? {
        if (frame.width <= 0.0 || frame.height <= 0.0) return null
        return DeltaSkinTouchPoint(
            fractionX = ((x - frame.left) / frame.width).coerceIn(0.0, 1.0),
            fractionY = ((y - frame.top) / frame.height).coerceIn(0.0, 1.0),
        )
    }

    private data class HitItem(
        val index: Int,
        val item: DeltaSkinItem,
        val visualFrame: DeltaSkinRect,
        val insideVisualFrame: Boolean,
    )

    private fun dpadCell(x: Double, y: Double, frame: DeltaSkinRect): Int {
        val clampedX = x.coerceIn(frame.left, frame.right)
        val clampedY = y.coerceIn(frame.top, frame.bottom)
        val column = floor(
            ((clampedX - frame.left) / frame.width * 3.0).coerceIn(0.0, 2.999_999)
        ).toInt()
        val row = floor(
            ((clampedY - frame.top) / frame.height * 3.0).coerceIn(0.0, 2.999_999)
        ).toInt()
        return row * 3 + column
    }

    private fun directionsForCell(cell: Int): Set<String> = when (cell) {
        0 -> setOf("up", "left")
        1 -> setOf("up")
        2 -> setOf("up", "right")
        3 -> setOf("left")
        4 -> emptySet()
        5 -> setOf("right")
        6 -> setOf("down", "left")
        7 -> setOf("down")
        8 -> setOf("down", "right")
        else -> emptySet()
    }

    private fun dpadCellRect(frame: DeltaSkinRect, cell: Int): DeltaSkinRect {
        val column = cell % 3
        val row = cell / 3
        val cellWidth = frame.width / 3.0
        val cellHeight = frame.height / 3.0
        return DeltaSkinRect(
            left = frame.left + column * cellWidth,
            top = frame.top + row * cellHeight,
            right = frame.left + (column + 1) * cellWidth,
            bottom = frame.top + (row + 1) * cellHeight,
        )
    }

    /**
     * Touche désignée par un nom Delta, ou rien.
     *
     * Une touche que la console ne possède pas n'est pas pressée, même si le
     * skin la dessine : c'est le cas d'une gâchette sur Game Boy, ou des deux
     * touches supplémentaires ailleurs que sur Nintendo DS.
     */
    private fun mapButton(raw: String): EmulatorButton? {
        val button = DeltaSkinInputNames.BUTTONS[raw.trim().lowercase(Locale.ROOT)] ?: return null
        return button.takeIf { it in console.buttons }
    }
}

data class DeltaSkinButtonChanges(
    val pressed: Set<EmulatorButton>,
    val released: Set<EmulatorButton>,
)

/**
 * Références de boutons par pointeur. Un bouton n'est relâché que lorsque le
 * dernier doigt qui le maintient quitte sa zone.
 */
class DeltaSkinInputState(
    private val onButtonsChanged: (DeltaSkinButtonChanges) -> Unit,
    private val onMenu: () -> Unit,
    private val onVisualsChanged: (Set<DeltaSkinVisualTarget>) -> Unit = {},
) {
    private val pointers = linkedMapOf<Int, DeltaSkinMappedInput>()
    private var buttonCounts = emptyMap<EmulatorButton, Int>()
    private var menuCount = 0

    fun updatePointer(pointerId: Int, input: DeltaSkinMappedInput) {
        val previous = pointers[pointerId] ?: DeltaSkinMappedInput()
        if (previous == input) return
        if (input.buttons.isEmpty() && !input.menu && input.visuals.isEmpty()) {
            pointers.remove(pointerId)
        } else {
            pointers[pointerId] = input
        }
        publishState()
    }

    fun releasePointer(pointerId: Int) {
        if (pointers.remove(pointerId) != null) publishState()
    }

    fun releaseAll() {
        if (pointers.isEmpty() && buttonCounts.isEmpty() && menuCount == 0) return
        pointers.clear()
        publishState()
    }

    fun activeVisuals(): Set<DeltaSkinVisualTarget> =
        pointers.values.flatMapTo(linkedSetOf()) { it.visuals }

    private fun publishState() {
        val newCounts = mutableMapOf<EmulatorButton, Int>()
        var newMenuCount = 0
        for (input in pointers.values) {
            input.buttons.forEach { button ->
                newCounts[button] = (newCounts[button] ?: 0) + 1
            }
            if (input.menu) newMenuCount++
        }
        val pressed = newCounts.keys.filterTo(linkedSetOf()) { (buttonCounts[it] ?: 0) == 0 }
        val released = buttonCounts.keys.filterTo(linkedSetOf()) { (newCounts[it] ?: 0) == 0 }
        buttonCounts = newCounts
        if (pressed.isNotEmpty() || released.isNotEmpty()) {
            onButtonsChanged(DeltaSkinButtonChanges(pressed = pressed, released = released))
        }
        if (menuCount == 0 && newMenuCount > 0) onMenu()
        menuCount = newMenuCount
        onVisualsChanged(activeVisuals())
    }
}
