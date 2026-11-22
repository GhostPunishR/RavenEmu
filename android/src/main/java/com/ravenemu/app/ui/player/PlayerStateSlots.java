package com.ravenemu.app.ui.player;

import android.graphics.Bitmap;
import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.data.saves.StatePreview;
import com.ravenemu.app.data.saves.StateSlotRepository;
import com.ravenemu.app.settings.HardcorePolicy;
import java.io.IOException;

/** The four save-state slots of the game menu: saved, loaded, deleted and
 * the last load undone, off the UI thread and on the GL one. */
final class PlayerStateSlots {
    private final PlayerActivity activity;

    PlayerStateSlots(PlayerActivity activity) {
        this.activity = activity;
    }

    void refreshSlots() {
        final String key = activity.activeSaveKey, compatibility = activity.stateCompatibility;
        if (key == null || compatibility == null) return;
        activity.submitIo(() -> {
            try {
                PlayerActivity.SlotBatch loaded = readSlotBatch(key, compatibility);
                activity.runOnUiThread(() -> {
                    if (activity.disposed.get() || !key.equals(activity.activeSaveKey)) return;
                    activity.stateSlots = loaded.slots;
                    activity.statePreviews = loaded.previews;
                    activity.menuPart.renderPlayerMenu();
                });
            } catch (IOException error) { finishSlotOperation("Emplacements indisponibles : " + error.getMessage(), null); }
        });
    }

    /** Capture/restore on GL, disk work on the single storage executor. */
    void operateSlot(int slot, int action) {
        if (activity.disposed.get() || activity.slotBusy || activity.stateSlots == null || activity.activeSaveKey == null ||
                activity.stateCompatibility == null || slot < 0 || slot >= StateSlotRepository.SLOT_COUNT) return;
        if (action == 1 && refuseStateLoad()) return;
        final String key = activity.activeSaveKey, compatibility = activity.stateCompatibility;
        final PlayerMenuSheet requestingMenu = activity.playerMenu;
        final int requestingGeneration = activity.menuGeneration;
        activity.selectedSlot = slot;
        activity.slotBusy = true;
        activity.pauseState.operation(true);
        activity.menuPart.renderPlayerMenu();
        if (action == 0) {
            activity.surface.queueEvent(() -> {
                if (!slotSessionAvailable(key, compatibility)) return;
                byte[] snapshot = NativeBridge.saveState();
                byte[] battery = NativeBridge.saveBattery();
                int[] preview = NativeBridge.capturePreview();
                if (snapshot == null) { finishSlotOperation("État indisponible", null); return; }
                activity.submitIo(() -> {
                    try {
                        byte[] thumbnail = null;
                        String message = "Emplacement " + (slot + 1) + " enregistré";
                        try { thumbnail = StatePreview.encode(preview); }
                        catch (IOException error) { message += " · aperçu indisponible"; }
                        StateSlotRepository.write(activity.getFilesDir(), key, slot, compatibility, snapshot, thumbnail);
                        try { SaveRepository.write(activity.getFilesDir(), key, "srm", battery); }
                        catch (IOException error) { message += " · SRAM non écrite : " + error.getMessage(); }
                        finishSlotOperation(message,
                                readSlotBatch(key, compatibility));
                    } catch (IOException error) { finishSlotOperation("Sauvegarde impossible : " + error.getMessage(), null); }
                });
            });
        } else activity.submitIo(() -> {
            try {
                if (action == 2) {
                    StateSlotRepository.delete(activity.getFilesDir(), key, slot);
                    finishSlotOperation("Emplacement " + (slot + 1) + " supprimé",
                            readSlotBatch(key, compatibility));
                } else {
                    byte[] snapshot = StateSlotRepository.read(activity.getFilesDir(), key, slot, compatibility);
                    if (snapshot == null) throw new IOException("Emplacement vide");
                    activity.surface.queueEvent(() -> applyState(key, compatibility, snapshot, false,
                            "Emplacement " + (slot + 1) + " chargé", requestingMenu, requestingGeneration));
                }
            } catch (IOException error) { finishSlotOperation("Opération impossible : " + error.getMessage(), null); }
        });
    }

    PlayerActivity.SlotBatch readSlotBatch(String key, String compatibility) throws IOException {
        StateSlotRepository.Slot[] slots = StateSlotRepository.list(activity.getFilesDir(), key, compatibility);
        Bitmap[] previews = new Bitmap[StateSlotRepository.SLOT_COUNT];
        for (StateSlotRepository.Slot slot : slots) {
            previews[slot.index] = StatePreview.decode(slot.thumbnail);
        }
        return new PlayerActivity.SlotBatch(slots, previews);
    }

    /** Hardcore: no save state is loaded, the undo of a load included. */
    boolean refuseStateLoad() {
        if (!activity.achievements.stateLoadLocked()) return false;
        activity.menuPart.renderPlayerMenu();
        if (activity.playerMenu != null && activity.playerMenu.isShowing()) activity.playerMenu.showMessage(HardcorePolicy.STATE_LOAD_LOCKED);
        return true;
    }

    boolean canUndo() {
        PlayerActivity.UndoState undo = activity.undoState;
        return undo != null && undo.key.equals(activity.activeSaveKey) &&
                undo.compatibility.equals(activity.stateCompatibility);
    }

    void undoLastLoad() {
        if (activity.disposed.get() || activity.slotBusy || !canUndo()) { activity.menuPart.renderPlayerMenu(); return; }
        if (refuseStateLoad()) return;
        PlayerActivity.UndoState undo = activity.undoState;
        final PlayerMenuSheet requestingMenu = activity.playerMenu;
        final int generation = activity.menuGeneration;
        activity.slotBusy = true;
        activity.pauseState.operation(true);
        activity.menuPart.renderPlayerMenu();
        activity.surface.queueEvent(() -> applyState(undo.key, undo.compatibility, undo.snapshot,
                true, "Chargement annulé", requestingMenu, generation));
    }

    /** Both load and undo keep a rollback snapshot until the core accepts
     * the requested state. Only a successful load replaces the undo point. */
    void applyState(String key, String compatibility, byte[] snapshot,
                            boolean undoing, String message,
                            PlayerMenuSheet requestingMenu, int generation) {
        if (!slotSessionAvailable(key, compatibility)) return;
        byte[] previous = NativeBridge.saveState();
        if (previous == null) {
            finishSlotOperation((undoing ? "Annulation impossible" : "Chargement annulé")
                    + " : état courant non récupérable", null);
            return;
        }
        boolean restored = NativeBridge.restoreState(snapshot);
        if (!restored && !NativeBridge.restoreState(previous)) {
            activity.achievements.stop();
            NativeBridge.closeRom();
            activity.undoState = null;
            activity.activeSaveKey = activity.stateCompatibility = null;
            activity.activeCore = null;
            finishSlotOperation("Jeu arrêté après échec de restauration ; SRAM conservée", null);
            activity.runOnUiThread(() -> {
                if (activity.disposed.get()) return;
                if (activity.playerMenu != null) activity.playerMenu.dismiss();
                android.widget.Toast.makeText(activity,
                        "Jeu arrêté après échec de restauration ; SRAM conservée",
                        android.widget.Toast.LENGTH_LONG).show();
                activity.finish();
            });
            return;
        }
        if (restored) activity.undoState = undoing ? null : new PlayerActivity.UndoState(key, compatibility, previous);
        finishSlotOperation(restored ? message : undoing
                ? "Annulation impossible : partie courante conservée"
                : "État incompatible : fichier conservé", null);
        if (restored) activity.runOnUiThread(() -> {
            if (!activity.disposed.get() && activity.playerMenu == requestingMenu &&
                    activity.menuGeneration == generation) requestingMenu.dismiss();
        });
    }

    boolean slotSessionAvailable(String key, String compatibility) {
        if (activity.disposed.get() || !activity.foreground || !key.equals(activity.activeSaveKey) ||
                !compatibility.equals(activity.stateCompatibility)) {
            finishSlotOperation("Opération interrompue : jeu en arrière-plan ou fermé", null);
            return false;
        }
        return true;
    }

    void finishSlotOperation(String message, PlayerActivity.SlotBatch updated) {
        activity.runOnUiThread(() -> {
            if (activity.disposed.get()) return;
            activity.slotBusy = false;
            activity.pauseState.operation(false);
            if (updated != null) { activity.stateSlots = updated.slots; activity.statePreviews = updated.previews; }
            if (activity.playerMenu != null && activity.playerMenu.isShowing()) {
                activity.menuPart.renderPlayerMenu();
                activity.playerMenu.showMessage(message);
            } else {
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
