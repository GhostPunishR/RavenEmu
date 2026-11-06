package com.ravenemu.app.ui.player;


import java.util.function.Consumer;

/** UI-thread reasons for stopping the single native session. Dismissing a
 * dialog must not resume a background Activity or an unfinished state load. */
final class PlayerPauseState {
    private final Consumer<Boolean> changed;
    private boolean foreground, focused, menu, operation, editing;
    private boolean paused = true;

    PlayerPauseState(Consumer<Boolean> changed) { this.changed = changed; }
    boolean isPaused() { return paused; }
    void foreground(boolean value) { foreground = value; update(); }
    void focused(boolean value) { focused = value; update(); }
    void menu(boolean value) { menu = value; update(); }
    void operation(boolean value) { operation = value; update(); }
    /** Moving the touch controls: the game waits. */
    void editing(boolean value) { editing = value; update(); }

    private void update() {
        boolean next = !foreground || !focused || menu || operation || editing;
        if (next != paused) { paused = next; changed.accept(next); }
    }
}
