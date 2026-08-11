package com.ravenemu.app.emulation

import com.ravenemu.emulation.cheats.CheatSupport

internal enum class EmulatorMenuAction {
    RESUME,
    SAVE_STATE,
    LOAD_STATE,
    CHEATS,
    RESET,
    EDIT_CONTROLS,
    TOGGLE_PER_GAME_PROFILE,
    QUIT,
}

/** Politique pure : l'Activity ne déduit jamais les cheats de ConsoleType. */
internal object EmulatorMenuPolicy {
    fun actions(cheatSupport: CheatSupport?): List<EmulatorMenuAction> = buildList {
        add(EmulatorMenuAction.RESUME)
        add(EmulatorMenuAction.SAVE_STATE)
        add(EmulatorMenuAction.LOAD_STATE)
        if (cheatSupport?.formats?.isNotEmpty() == true) {
            add(EmulatorMenuAction.CHEATS)
        }
        add(EmulatorMenuAction.RESET)
        add(EmulatorMenuAction.EDIT_CONTROLS)
        add(EmulatorMenuAction.TOGGLE_PER_GAME_PROFILE)
        add(EmulatorMenuAction.QUIT)
    }
}
