package com.ravenemu.app.ui.player;

import com.ravenemu.app.data.saves.CheatList;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.library.HomeActivity;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;

/** The running game's cheats in the game menu, after the beta's CheatsPanel:
 * each code turned on or off or deleted, and a form to add one, which the
 * core checks before it is kept. In Hardcore the list stays visible and
 * nothing runs or is added. Views only: every action goes to the listener.
 * UI thread. */
final class CheatsPanel {
    interface Actions {
        void back();
        /** Checked by the core, then answered by added() or refused(). */
        void add(String name, String code);
        void toggle(int index);
        void delete(int index);
    }

    static final String HARDCORE = "Mode Hardcore : les codes de triche sont désactivés.";
    static final String HELP = "Game Boy et Game Boy Color : Game Genie (ABC-DEF-GHI) ou GameShark (010238CD). "
            + "Game Boy Advance : Codebreaker (XAAAAAAA YYYY) ou GameShark et Action Replay (XXXXXXXX YYYYYYYY). "
            + "Un code de plusieurs lignes s'écrit d'un bloc.";

    private final Activity owner;
    private final Actions actions;
    private final LinearLayout root, list;
    private final TextView notice, empty, refusal;
    private final EditText name, code;
    private final Button add;
    private List<CheatList.Cheat> cheats = Collections.emptyList();
    private boolean allowed = true, checking;

    CheatsPanel(Activity owner, Actions actions) {
        this.owner = owner;
        this.actions = actions;
        root = column();
        Button back = textButton("Retour au menu du jeu", HomeActivity.ACCENT);
        back.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new RavenIcon(RavenIcon.Glyph.BACK, HomeActivity.ACCENT, dp(18)), null, null, null);
        back.setCompoundDrawablePadding(dp(6));
        back.setOnClickListener(view -> actions.back());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("Codes de triche", 20, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        add(root, title, 4);
        notice = text(HARDCORE, 13, AchievementsPanel.ERROR);
        add(root, notice, 6);
        empty = text("Aucun code pour ce jeu.", 13, HomeActivity.MUTED);
        add(root, empty, 6);
        list = column();
        add(root, list, 4);
        TextView heading = text("Ajouter un code", 15, HomeActivity.TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        add(root, heading, 16);
        TextView help = text(HELP, 12, HomeActivity.MUTED);
        add(root, help, 4);
        name = field("Nom (facultatif)", 60, true);
        add(root, name, 8);
        code = field("Code", 4000, false);
        add(root, code, 8);
        refusal = text("", 12, AchievementsPanel.ERROR);
        refusal.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(root, refusal, 6);
        add = button("Ajouter");
        add.setOnClickListener(view -> {
            if (!add.isEnabled()) return;
            checking = true;
            refusal.setVisibility(View.GONE);
            update();
            actions.add(name.getText().toString(), code.getText().toString());
        });
        add(root, add, 8);
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refusal.setVisibility(View.GONE);
                update();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        show(cheats, true);
    }

    View view() { return root; }

    /** The game's list, and whether cheats may run now (not in Hardcore). */
    void show(List<CheatList.Cheat> cheats, boolean allowed) {
        this.cheats = cheats == null ? Collections.emptyList() : cheats;
        this.allowed = allowed;
        notice.setVisibility(allowed ? View.GONE : View.VISIBLE);
        empty.setVisibility(this.cheats.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        for (int i = 0; i < this.cheats.size(); i++) add(list, row(i), 4);
        update();
    }

    /** The code was kept: the form starts again. */
    void added() {
        checking = false;
        name.setText("");
        code.setText("");
        refusal.setVisibility(View.GONE);
        update();
    }

    /** Why the core, or the form, refuses the code; the form keeps it to fix. */
    void refused(String reason) {
        checking = false;
        refusal.setText(reason);
        refusal.setVisibility(View.VISIBLE);
        update();
    }

    private void update() {
        boolean ready = allowed && !checking && code.getText().toString().trim().length() > 0;
        add.setEnabled(ready);
        add.setAlpha(ready ? 1f : .45f);
        name.setEnabled(allowed);
        code.setEnabled(allowed);
    }

    private View row(int index) {
        CheatList.Cheat cheat = cheats.get(index);
        LinearLayout row = new LinearLayout(owner);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = column();
        TextView label = text(cheat.name, 15, HomeActivity.TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label);
        TextView line = text(cheat.code, 12, HomeActivity.MUTED);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(line);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        Button delete = textButton("Supprimer", HomeActivity.MUTED);
        delete.setTextSize(12);
        delete.setContentDescription("Supprimer le code " + cheat.name);
        delete.setOnClickListener(view -> actions.delete(index));
        row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
        Switch on = new Switch(owner);
        on.setChecked(cheat.enabled);
        on.setEnabled(allowed);
        on.setContentDescription(cheat.name + (cheat.enabled ? ", actif" : ", inactif"));
        on.setOnCheckedChangeListener((view, checked) -> {
            if (checked != cheat.enabled) actions.toggle(index);
        });
        row.addView(on, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private EditText field(String hint, int max, boolean single) {
        EditText field = new EditText(owner);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setSingleLine(single);
        if (!single) {
            field.setMinLines(2);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        }
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setTextColor(HomeActivity.TEXT);
        field.setHintTextColor(HomeActivity.MUTED);
        field.setTextSize(14);
        field.setMinHeight(dp(48));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setSaveEnabled(false);
        GradientDrawable outline = shape(HomeActivity.SURFACE, 10);
        outline.setStroke(Math.max(1, dp(1)), HomeActivity.OUTLINE);
        field.setBackground(outline);
        return field;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(owner);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextColor(HomeActivity.BACKGROUND);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(shape(HomeActivity.ACCENT, 12));
        return button;
    }

    /** The beta's TextButton: no background, the label in its color. */
    private Button textButton(String label, int color) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
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

    private int dp(int size) { return Math.round(size * owner.getResources().getDisplayMetrics().density); }
}
