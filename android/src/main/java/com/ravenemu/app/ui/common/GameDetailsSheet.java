package com.ravenemu.app.ui.common;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/** Java Views sheet matching the beta's fully expanded game-details panel. */
public final class GameDetailsSheet extends BottomSheetDialog {
    public GameDetailsSheet(Context context) {
        // The sheet's Android style follows the theme of the Activity it opens on.
        super(context, RavenTheme.of(context).sheetStyle);
        BottomSheetBehavior<FrameLayout> behavior = getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setHideable(true);
        behavior.setFitToContents(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(View sheet, int state) {
                // While opening/settling, let the first tap reach Play. Once
                // settled, native nested scrolling and downward drag resume.
                behavior.setDraggable(state != BottomSheetBehavior.STATE_SETTLING);
            }
            @Override public void onSlide(View sheet, float offset) {}
        });
    }
}
