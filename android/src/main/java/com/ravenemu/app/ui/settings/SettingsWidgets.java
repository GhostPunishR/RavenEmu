package com.ravenemu.app.ui.settings;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import com.ravenemu.app.ui.common.RavenIcon;
import com.ravenemu.app.ui.common.RavenTheme;
import java.util.Locale;

/** The rows of the settings pages, after the beta's settings components:
 * categories, descriptions, choice lists, switches, sliders, groups, fields
 * and buttons, added to the column of the page shown. UI thread only. */
final class SettingsWidgets {
    /** The page shown, and how to show another. */
    interface Navigator {
        String page();
        void showPage(String page);
    }

    private final Activity activity;
    private final RavenTheme theme;
    private final Navigator navigator;
    /** The column of the page shown: every row goes there. */
    LinearLayout content;

    SettingsWidgets(Activity activity, RavenTheme theme, Navigator navigator) {
        this.activity = activity;
        this.theme = theme;
        this.navigator = navigator;
    }

    void description(String label, String summary) {
        LinearLayout block = column();
        block.addView(text(label, 15, theme.text));
        add(block, text(summary, 13, theme.muted), 4);
        add(block, 14);
    }

    /** The beta's ChoiceRow: a radio mark then the label, the chosen row on the surface color. */
    LinearLayout choices(String[] labels, String[] values, String selected, StringChange change) {
        LinearLayout list = column();
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            final boolean chosen = value.equals(selected);
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(48));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(chosen ? theme.selected : theme.background);
            row.addView(RavenIcon.image(activity, chosen ? RavenIcon.Glyph.RADIO_ON : RavenIcon.Glyph.RADIO_OFF,
                    theme.accent, 20), new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView name = text(labels[i], 14, theme.text);
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(0, -2, 1);
            gap.setMarginStart(dp(12));
            row.addView(name, gap);
            row.setSelected(chosen);
            row.setContentDescription(labels[i] + (chosen ? ", choisi" : ""));
            row.setOnClickListener(view -> {
                if (chosen || !view.isEnabled()) return;
                change.accept(value);
                navigator.showPage(navigator.page());
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        add(list, 6);
        return list;
    }

    /** Like the beta's ChoiceRow, a disabled row looks the same and ignores taps. */
    static void setChoicesEnabled(LinearLayout list, boolean enabled) {
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setEnabled(enabled);
    }

    Switch settingSwitch(String label, String summary, boolean checked, BooleanChange change) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        LinearLayout description = column();
        TextView name = text(label, 15, theme.text);
        description.addView(name);
        TextView detail = text(summary, 13, theme.muted);
        add(description, detail, 4);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(activity);
        control.setSaveEnabled(false);
        control.setId(View.generateViewId());
        control.setContentDescription(label + ". " + summary);
        control.setMinimumHeight(dp(48));
        control.setMinWidth(dp(48));
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states,
                new int[]{theme.switchOn, theme.switchOff}));
        control.setTrackTintList(new ColorStateList(states,
                new int[]{theme.switchOnTrack, theme.switchOffTrack}));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> change.accept(value));
        name.setLabelFor(control.getId());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.leftMargin = dp(12);
        row.addView(control, size);
        // A switch greyed out by another setting stays as it is.
        row.setOnClickListener(view -> { if (control.isEnabled()) control.performClick(); });
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(row, 14);
        return control;
    }

    SeekBar slider(String label, int value, int minimum, int maximum,
                           boolean percent, IntChange change) {
        return slider(label, value, minimum, maximum, percent ? " %" : "", change);
    }

    SeekBar slider(String label, int value, int minimum, int maximum,
                           String unit, IntChange change) {
        return slider(label, value, minimum, maximum, 1, unit, change);
    }

    /** Steps of `step` from minimum: Boktai's sun moves by tens. */
    SeekBar slider(String label, int value, int minimum, int maximum, int step,
                           String unit, IntChange change) {
        LinearLayout block = column();
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(label, 15, theme.text), new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueText = text(value + unit, 14, theme.accent);
        heading.addView(valueText);
        block.addView(heading);
        SeekBar control = new SeekBar(activity);
        control.setSaveEnabled(false);
        control.setMax((maximum - minimum) / step);
        control.setProgress((value - minimum) / step);
        control.setContentDescription(label + ", " + value + unit);
        control.setProgressTintList(ColorStateList.valueOf(theme.accent));
        control.setThumbTintList(ColorStateList.valueOf(theme.accent));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Android view-state restoration must never save a stale value.
                if (!fromUser) return;
                int current = minimum + progress * step;
                valueText.setText(current + unit);
                control.setContentDescription(label + ", " + current + unit);
                change.accept(current);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(control, new LinearLayout.LayoutParams(-1, dp(48)));
        add(block, 14);
        return control;
    }

    void group(String label) {
        TextView heading = text(label, 14, theme.accent);
        heading.setTypeface(null, Typeface.BOLD);
        add(heading, content.getChildCount() == 0 ? 0 : 14);
        View divider = new View(activity);
        divider.setBackgroundColor(theme.outline);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.topMargin = dp(6);
        content.addView(divider, line);
    }

    Button button(String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(theme.text);
        button.setBackground(shape(theme.surface));
        return button;
    }

    /** A TextButton for a row, not added to the page. */
    Button rowButton(String label, int color) {
        Button button = button(label);
        button.setTextColor(color);
        button.setTextSize(13);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    /** The beta's TextButton: no background, the label in its color. */
    Button textButton(String label, int color) {
        Button button = button(label);
        button.setTextColor(color);
        button.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.topMargin = dp(4);
        content.addView(button, size);
        return button;
    }

    void category(String label, String summary, String destination, RavenIcon.Glyph icon) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));
        row.setMinimumHeight(dp(72));
        GradientDrawable surface = shape(theme.surface);
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), RavenTheme.alpha(theme.outline, 0xb3));
        row.setBackground(surface);
        row.setContentDescription("Ouvrir les réglages " + label.toLowerCase(Locale.ROOT));
        row.setOnClickListener(view -> navigator.showPage(destination));

        // The categories carry the beta's icons; their sub-pages, text alone.
        if (icon != null) {
            FrameLayout iconBox = new FrameLayout(activity);
            iconBox.setBackground(shape(RavenTheme.alpha(theme.accent, 0x21)));
            iconBox.addView(RavenIcon.image(activity, icon, theme.accent, 24),
                    new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            row.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        LinearLayout description = column();
        TextView name = text(label, 16, theme.text);
        name.setTypeface(null, Typeface.BOLD);
        description.addView(name);
        add(description, text(summary, 12, theme.muted), 4);
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(0, -2, 1);
        details.setMarginStart(icon != null ? dp(13) : dp(4));
        details.setMarginEnd(dp(13));
        row.addView(description, details);
        row.addView(RavenIcon.image(activity, RavenIcon.Glyph.CHEVRON, theme.accent, 20),
                new LinearLayout.LayoutParams(dp(24), dp(24)));
        add(row, content.getChildCount() == 0 ? 0 : 14);
    }

    EditText field(String hint, int type) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setTextSize(15);
        field.setTextColor(theme.text);
        field.setHintTextColor(theme.muted);
        field.setMinHeight(dp(52));
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setSaveEnabled(false);
        GradientDrawable outline = shape(theme.surface);
        outline.setStroke(dp(1), theme.outline);
        field.setBackground(outline);
        return field;
    }

    /** The beta's OutlinedButton. */
    Button outlined(String label) {
        Button button = button(label);
        button.setTextSize(12);
        GradientDrawable outline = shape(android.graphics.Color.TRANSPARENT);
        outline.setStroke(dp(1), theme.outline);
        button.setBackground(outline);
        return button;
    }

    GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    /** Nothing above a page's first block. */
    void add(View view, int top) { add(content, view, content.getChildCount() == 0 ? 0 : top); }

    void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        parent.addView(view, size);
    }

    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    interface BooleanChange { void accept(boolean value); }
    interface IntChange { void accept(int value); }
    interface StringChange { void accept(String value); }
}
