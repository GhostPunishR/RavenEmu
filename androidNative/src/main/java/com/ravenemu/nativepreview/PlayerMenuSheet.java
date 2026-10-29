package com.ravenemu.nativepreview;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import java.util.Date;
import java.util.Locale;

/** Java Views counterpart of the beta's pause menu and four state slots.
 * Rendering and confirmations only: all core and file operations belong to
 * the listener. State thumbnails are not ported yet; the preview stays empty. */
final class PlayerMenuSheet {
    interface Listener {
        void onSave(int slot);
        void onLoad(int slot);
        void onDelete(int slot);
        void onDismiss();
    }

    private static final int CARD = 0xff202230;
    private static final int SECONDARY = 0xffcbc4dc;
    private final Activity owner;
    private final Listener listener;
    private final GameDetailsSheet dialog;
    private final Button[] choices = new Button[StateSlotRepository.SLOT_COUNT];
    private final TextView slotTitle, savedAt, warning, operationMessage;
    private final Button save, load, delete;
    private StateSlotRepository.Slot[] slots;
    private AlertDialog confirmation;
    private int selectedSlot;
    private boolean busy;

    PlayerMenuSheet(Activity owner, String title, int selectedSlot, Listener listener) {
        this.owner = owner;
        this.listener = listener;
        this.selectedSlot = selectedSlot >= 0 && selectedSlot < StateSlotRepository.SLOT_COUNT
                ? selectedSlot : 0;
        dialog = new GameDetailsSheet(owner);
        LinearLayout content = column();
        content.setPadding(dp(18), dp(12), dp(18), dp(28));
        View handle = new View(owner);
        handle.setBackground(shape(NativeHomeActivity.MUTED, 2));
        LinearLayout.LayoutParams handleSize = new LinearLayout.LayoutParams(dp(32), dp(4));
        handleSize.gravity = Gravity.CENTER_HORIZONTAL;
        handleSize.bottomMargin = dp(20);
        content.addView(handle, handleSize);
        TextView heading = text("Menu du jeu", 20, NativeHomeActivity.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        content.addView(heading);
        add(content, text(title, 13, SECONDARY), 12);
        add(content, text("Partie en pause · 4 emplacements de sauvegarde", 12,
                NativeHomeActivity.MUTED), 12);

        LinearLayout switcher = new LinearLayout(owner);
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            Button choice = button(String.valueOf(i + 1), 15);
            choice.setTypeface(null, Typeface.BOLD);
            choice.setOnClickListener(view -> {
                if (busy) return;
                dismissConfirmation();
                this.selectedSlot = index;
                refresh();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) size.leftMargin = dp(8);
            switcher.addView(choice, size);
            choices[i] = choice;
        }
        add(content, switcher, 12);

        LinearLayout card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(shape(CARD, 14));
        LinearLayout summary = new LinearLayout(owner);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        String system = RomLibrary.detectSystem(title);
        TextView preview = text(system == null ? "—" : system.toUpperCase(Locale.ROOT),
                16, NativeHomeActivity.MUTED);
        preview.setGravity(Gravity.CENTER);
        preview.setContentDescription("Aperçu de sauvegarde indisponible");
        GradientDrawable frame = shape(0xff10111b, 9);
        frame.setStroke(dp(1), NativeHomeActivity.OUTLINE);
        preview.setBackground(frame);
        summary.addView(preview, new LinearLayout.LayoutParams(dp(94), dp(84)));
        LinearLayout description = column();
        description.setPadding(dp(12), 0, 0, 0);
        slotTitle = text("", 15, NativeHomeActivity.TEXT);
        slotTitle.setTypeface(null, Typeface.BOLD);
        description.addView(slotTitle);
        savedAt = text("", 12, NativeHomeActivity.MUTED);
        add(description, savedAt, 5);
        summary.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(summary);
        warning = text("", 12, NativeHomeActivity.MUTED);
        add(card, warning, 10);
        LinearLayout actions = new LinearLayout(owner);
        save = button("Sauver", 12);
        load = button("Charger", 12);
        delete = button("Supprimer", 11);
        Button[] actionButtons = {save, load, delete};
        for (int i = 0; i < actionButtons.length; i++) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) size.leftMargin = dp(6);
            actions.addView(actionButtons[i], size);
        }
        add(card, actions, 10);
        add(content, card, 12);
        operationMessage = text("", 12, NativeHomeActivity.MUTED);
        operationMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        operationMessage.setVisibility(View.GONE);
        add(content, operationMessage, 8);
        save.setOnClickListener(view -> requestSave());
        load.setOnClickListener(view -> requestLoad());
        delete.setOnClickListener(view -> requestDelete());
        Button resume = button("Reprendre le jeu", 14);
        resume.setTextColor(NativeHomeActivity.BACKGROUND);
        resume.setBackground(buttonBackground(NativeHomeActivity.ACCENT));
        resume.setOnClickListener(view -> dismiss());
        add(content, resume, 12);
        NestedScrollView scroll = new NestedScrollView(owner);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.setOnDismissListener(ignored -> {
            dismissConfirmation();
            listener.onDismiss();
        });
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            if (key != KeyEvent.KEYCODE_BUTTON_A) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                View focused = dialog.getCurrentFocus();
                if (focused != null && focused.isEnabled() && focused.isClickable()) focused.performClick();
            }
            return true;
        });
        refresh();
    }

    void show() { dialog.show(); }
    void dismiss() { dialog.dismiss(); }
    boolean isShowing() { return dialog.isShowing(); }
    int getSelectedSlot() { return selectedSlot; }

    void showMessage(String message) {
        operationMessage.setText(message);
        operationMessage.setVisibility(View.VISIBLE);
    }

    void render(StateSlotRepository.Slot[] slots, boolean busy) {
        dismissConfirmation();
        this.slots = slots == null ? null : slots.clone();
        this.busy = busy;
        if (busy) operationMessage.setVisibility(View.GONE);
        refresh();
    }

    private StateSlotRepository.Slot slot(int index) {
        if (slots != null) for (StateSlotRepository.Slot entry : slots)
            if (entry != null && entry.index == index) return entry;
        return null;
    }

    private void refresh() {
        for (int i = 0; i < choices.length; i++) {
            StateSlotRepository.Slot entry = slot(i);
            boolean occupied = entry != null && entry.occupied;
            boolean selected = selectedSlot == i;
            choices[i].setText(String.valueOf(i + 1) + (occupied ? "  •" : ""));
            choices[i].setContentDescription("Emplacement " + (i + 1) + (occupied ? ", occupé" : ", vide"));
            choices[i].setSelected(selected);
            choices[i].setTextColor(selected ? NativeHomeActivity.BACKGROUND : NativeHomeActivity.TEXT);
            choices[i].setBackground(buttonBackground(selected ? NativeHomeActivity.ACCENT : 0xff292a38));
            choices[i].setEnabled(!busy);
            choices[i].setAlpha(busy ? .5f : 1f);
        }
        StateSlotRepository.Slot entry = slot(selectedSlot);
        slotTitle.setText("Emplacement " + (selectedSlot + 1));
        savedAt.setText(entry == null ? "Chargement des emplacements…"
                : !entry.occupied ? "Vide" : entry.savedAt <= 0 ? "Date inconnue"
                : android.text.format.DateFormat.getMediumDateFormat(owner).format(new Date(entry.savedAt))
                + " · " + android.text.format.DateFormat.getTimeFormat(owner).format(new Date(entry.savedAt)));
        String note = busy ? "Opération en cours…" : entry == null || !entry.occupied ? ""
                : entry.legacy ? "Ancien état · compatibilité à confirmer"
                : !entry.compatible ? "État incompatible ou endommagé · fichier conservé" : "";
        warning.setText(note);
        warning.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        enabled(save, !busy && entry != null);
        enabled(load, !busy && entry != null && entry.occupied && (entry.compatible || entry.legacy));
        enabled(delete, !busy && entry != null && entry.occupied);
    }

    private boolean ready() { return !busy && dialog.isShowing() && slot(selectedSlot) != null; }

    private void requestSave() {
        if (!ready()) return;
        StateSlotRepository.Slot entry = slot(selectedSlot);
        int slot = selectedSlot;
        if (entry.occupied) confirm("Écraser la sauvegarde ?",
                "Remplacer l'état de l'emplacement " + (slot + 1) + " par la partie actuelle ? Cette action est irréversible.",
                "Écraser", entry, () -> listener.onSave(slot));
        else begin(() -> listener.onSave(slot));
    }

    private void requestLoad() {
        if (!ready()) return;
        StateSlotRepository.Slot entry = slot(selectedSlot);
        if (!entry.occupied || (!entry.compatible && !entry.legacy)) return;
        int slot = selectedSlot;
        if (entry.legacy) confirm("Charger cet ancien état ?",
                "La compatibilité de cet ancien état n'est pas connue. Tenter de le charger peut remplacer la partie en cours. Le fichier d'origine sera conservé.",
                "Charger", entry, () -> listener.onLoad(slot));
        else begin(() -> listener.onLoad(slot));
    }

    private void requestDelete() {
        if (!ready()) return;
        StateSlotRepository.Slot entry = slot(selectedSlot);
        if (!entry.occupied) return;
        int slot = selectedSlot;
        confirm("Supprimer la sauvegarde ?", "Supprimer définitivement l'emplacement " + (slot + 1)
                + " ? La sauvegarde classique du jeu restera intacte.", "Supprimer", entry,
                () -> listener.onDelete(slot));
    }

    private void confirm(String title, String message, String action,
                         StateSlotRepository.Slot expected, Runnable callback) {
        dismissConfirmation();
        confirmation = new AlertDialog.Builder(owner).setTitle(title).setMessage(message)
                .setNegativeButton("Annuler", null)
                .setPositiveButton(action, (ignored, which) -> {
                    if (ready() && slot(selectedSlot) == expected) begin(callback);
                }).create();
        confirmation.setOnKeyListener((ignored, key, event) -> {
            if (key == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    View focused = ((AlertDialog) ignored).getCurrentFocus();
                    if (focused != null && focused.isEnabled() && focused.isClickable()) focused.performClick();
                }
                return true;
            }
            if (key != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) dismissConfirmation();
            return true;
        });
        confirmation.show();
        confirmation.getWindow().setBackgroundDrawable(shape(NativeHomeActivity.SURFACE, 18));
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(NativeHomeActivity.ACCENT);
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(NativeHomeActivity.ACCENT);
    }

    private void dismissConfirmation() {
        if (confirmation == null) return;
        AlertDialog current = confirmation;
        confirmation = null;
        current.dismiss();
    }

    private void begin(Runnable action) {
        busy = true;
        refresh();
        action.run();
    }

    private void enabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .45f);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(owner);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private Button button(String label, int size) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextColor(NativeHomeActivity.TEXT);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        button.setBackground(buttonBackground(0xff292a38));
        return button;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(owner);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void add(LinearLayout column, View child, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        column.addView(child, size);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Drawable buttonBackground(int color) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                shape(color == NativeHomeActivity.ACCENT ? 0xffbca6ff : 0xff3c3454, 12));
        GradientDrawable focused = shape(color, 12);
        focused.setStroke(dp(2), color == NativeHomeActivity.ACCENT
                ? NativeHomeActivity.TEXT : NativeHomeActivity.ACCENT);
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, shape(color, 12));
        return states;
    }

    private int dp(int size) { return Math.round(size * owner.getResources().getDisplayMetrics().density); }
}
