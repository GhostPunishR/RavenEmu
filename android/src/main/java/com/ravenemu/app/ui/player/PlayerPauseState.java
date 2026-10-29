package com.ravenemu.app.ui.player;


import java.util.function.Consumer;

/** UI-thread reasons for stopping the single native session. Dismissing a
 * dialog must not resume a background Activity or an unfinished state load. */
final class PlayerPauseState {
    private final Consumer<Boolean> changed;
    private boolean foreground, focused, menu, operation;
    private boolean paused = true;

    PlayerPauseState(Consumer<Boolean> changed) { this.changed = changed; }
    boolean isPaused() { return paused; }
    void foreground(boolean value) { foreground = value; update(); }
    void focused(boolean value) { focused = value; update(); }
    void menu(boolean value) { menu = value; update(); }
    void operation(boolean value) { operation = value; update(); }

    private void update() {
        boolean next = !foreground || !focused || menu || operation;
        if (next != paused) { paused = next; changed.accept(next); }
    }
}
