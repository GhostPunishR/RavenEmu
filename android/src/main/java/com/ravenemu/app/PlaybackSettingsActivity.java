package com.ravenemu.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;
import androidx.core.graphics.PathParser;
import java.util.Locale;

/** Java Views counterpart of the beta's Audio/Video settings pages.
 * Only settings connected to the native drivers are presented here. */
public final class PlaybackSettingsActivity extends Activity {
    static final String EXTRA_PAGE = "playback.settings.page";
    static final String HOME = "home";
    static final String AUDIO = "audio";
    static final String VIDEO = "video";

    private PlaybackSettings settings;
    private LinearLayout content;
    private ScrollView scroll;
    private TextView title;
    private Button back;
    private SeekBar volume;
    private String page;
    private final int[] scrollPositions = new int[3];
    private int pageSerial;
    private boolean rendered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = PlaybackSettings.load(this);
        String initialPage = getIntent().getStringExtra(EXTRA_PAGE);
        if (state != null) {
            initialPage = state.getString("settings.page", initialPage);
            scrollPositions[0] = Math.max(0, state.getInt("settings.scroll.home"));
            scrollPositions[1] = Math.max(0, state.getInt("settings.scroll.audio"));
            scrollPositions[2] = Math.max(0, state.getInt("settings.scroll.video"));
        }

        LinearLayout root = column();
        root.setTag("playback.settings.root");
        root.setBackgroundColor(HomeActivity.BACKGROUND);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(HomeActivity.SURFACE);
        back = button("‹");
        back.setTextSize(30);
        back.setContentDescription("Retour aux paramètres");
        back.setOnClickListener(view -> navigateBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), -1));
        title = text("", 20, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(54)));

        scroll = new ScrollView(this);
        scroll.setTag("playback.settings.scroll");
        scroll.setSaveEnabled(false);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(18), dp(12), dp(18), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        SystemBarInsets.install(this, root);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        showPage(initialPage);
    }

    private void showPage(String requestedPage) {
        if (rendered) scrollPositions[index(page)] = scroll.getScrollY();
        page = VIDEO.equals(requestedPage) ? VIDEO : AUDIO.equals(requestedPage) ? AUDIO : HOME;
        rendered = true;
        final int request = ++pageSerial;
        title.setText(VIDEO.equals(page) ? "Vidéo" : AUDIO.equals(page) ? "Audio" : "Paramètres");
        back.setContentDescription(HOME.equals(page) ? "Fermer les paramètres" : "Retour aux paramètres");
        content.removeAllViews();
        if (AUDIO.equals(page)) audioPage(); else if (VIDEO.equals(page)) videoPage(); else homePage();
        final int restoreScroll = scrollPositions[index(page)];
        scroll.post(() -> {
            if (!isFinishing() && !isDestroyed() && request == pageSerial) scroll.scrollTo(0, restoreScroll);
        });
    }

    private void homePage() {
        category("Audio", "Son du jeu et volume", AUDIO,
                "M3,9H7L12,4V20L7,15H3ZM15,8V16C17,15 17,9 15,8ZM15,3V5C21,7 21,17 15,19V21C24,18 24,6 15,3Z");
        category("Vidéo", "Taille de l'image, luminosité et contraste", VIDEO,
                "M3,3H21V17H13V19H17V21H7V19H11V17H3ZM5,5V15H19V5Z");
    }

    private void category(String label, String summary, String destination, String iconPath) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(15), dp(14), dp(15));
        row.setMinimumHeight(dp(72));
        GradientDrawable surface = shape(HomeActivity.SURFACE);
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), 0xb33c4052);
        row.setBackground(surface);
        row.setContentDescription("Ouvrir les réglages " + label.toLowerCase(Locale.ROOT));
        row.setOnClickListener(view -> showPage(destination));

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(shape(0x21a78bfa));
        iconBox.addView(new SettingsIcon(this, iconPath),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout description = column();
        TextView name = text(label, 16, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        description.addView(name);
        add(description, text(summary, 12, HomeActivity.MUTED), 4);
        LinearLayout.LayoutParams details = new LinearLayout.LayoutParams(0, -2, 1);
        details.setMarginStart(dp(13));
        details.setMarginEnd(dp(13));
        row.addView(description, details);
        row.addView(text("›", 23, HomeActivity.ACCENT));
        add(row, content.getChildCount() == 0 ? 0 : 14);
    }

    private void navigateBack() {
        if (HOME.equals(page)) finish(); else showPage(HOME);
    }

    @Override public void onBackPressed() { navigateBack(); }

    private void audioPage() {
        group("Son du jeu");
        settingSwitch("Activer le son",
                "Conserve l'horloge d'émulation et coupe uniquement la sortie audio.",
                settings.audioEnabled, enabled -> {
                    update(new PlaybackSettings(enabled, settings.audioVolume, settings.integerScale,
                            settings.brightness, settings.contrast));
                    volume.setEnabled(enabled);
                });
        volume = slider("Volume", settings.audioVolume, 0, 100, true, value ->
                update(new PlaybackSettings(settings.audioEnabled, value, settings.integerScale,
                        settings.brightness, settings.contrast)));
        volume.setEnabled(settings.audioEnabled);
    }

    private void videoPage() {
        group("Taille de l'image");
        settingSwitch("Pixels entiers",
                "L'image prend un multiple entier de sa taille : tous les pixels sont identiques, avec des bords noirs autour.",
                settings.integerScale, enabled -> update(new PlaybackSettings(settings.audioEnabled,
                        settings.audioVolume, enabled, settings.brightness, settings.contrast)));
        group("Ajustements");
        slider("Luminosité", settings.brightness, -50, 50, false, value ->
                update(new PlaybackSettings(settings.audioEnabled, settings.audioVolume,
                        settings.integerScale, value, settings.contrast)));
        slider("Contraste", settings.contrast, 50, 150, true, value ->
                update(new PlaybackSettings(settings.audioEnabled, settings.audioVolume,
                        settings.integerScale, settings.brightness, value)));
        Button reset = button("Rétablir l'affichage d'origine");
        reset.setTextColor(HomeActivity.ACCENT);
        reset.setOnClickListener(view -> {
            update(new PlaybackSettings(settings.audioEnabled, settings.audioVolume, false, 0, 100));
            showPage(VIDEO);
        });
        add(reset, 14);
    }

    private void update(PlaybackSettings updated) {
        settings = updated;
        settings.save(this);
    }

    private void settingSwitch(String label, String summary, boolean checked, BooleanChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        LinearLayout description = column();
        TextView name = text(label, 15, HomeActivity.TEXT);
        description.addView(name);
        TextView detail = text(summary, 13, HomeActivity.MUTED);
        add(description, detail, 4);
        row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setSaveEnabled(false);
        control.setId(View.generateViewId());
        control.setContentDescription(label + ". " + summary);
        control.setMinimumHeight(dp(48));
        control.setMinWidth(dp(48));
        int[][] states = {{android.R.attr.state_checked}, {}};
        control.setThumbTintList(new ColorStateList(states,
                new int[]{HomeActivity.ACCENT, HomeActivity.SECONDARY}));
        control.setTrackTintList(new ColorStateList(states,
                new int[]{0x66a78bfa, HomeActivity.OUTLINE}));
        control.setChecked(checked);
        control.setOnCheckedChangeListener((button, value) -> change.accept(value));
        name.setLabelFor(control.getId());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.leftMargin = dp(12);
        row.addView(control, size);
        row.setOnClickListener(view -> control.performClick());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        add(row, 14);
    }

    private SeekBar slider(String label, int value, int minimum, int maximum,
                           boolean percent, IntChange change) {
        LinearLayout block = column();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(label, 15, HomeActivity.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueText = text(valueText(value, percent), 14, HomeActivity.ACCENT);
        heading.addView(valueText);
        block.addView(heading);
        SeekBar control = new SeekBar(this);
        control.setSaveEnabled(false);
        control.setMax(maximum - minimum);
        control.setProgress(value - minimum);
        control.setContentDescription(label + ", " + valueText(value, percent));
        control.setProgressTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setThumbTintList(ColorStateList.valueOf(HomeActivity.ACCENT));
        control.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Android view-state restoration must never save a stale value.
                if (!fromUser) return;
                int current = progress + minimum;
                valueText.setText(valueText(current, percent));
                control.setContentDescription(label + ", " + valueText(current, percent));
                change.accept(current);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(control, new LinearLayout.LayoutParams(-1, dp(48)));
        add(block, 14);
        return control;
    }

    private static String valueText(int value, boolean percent) {
        return percent ? value + " %" : String.valueOf(value);
    }

    private void group(String label) {
        TextView heading = text(label, 14, HomeActivity.ACCENT);
        heading.setTypeface(null, Typeface.BOLD);
        add(heading, content.getChildCount() == 0 ? 0 : 14);
        View divider = new View(this);
        divider.setBackgroundColor(HomeActivity.OUTLINE);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(1));
        line.topMargin = dp(6);
        content.addView(divider, line);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextColor(HomeActivity.TEXT);
        button.setBackground(shape(HomeActivity.SURFACE));
        return button;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        return drawable;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(View view, int top) { add(content, view, top); }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(top);
        parent.addView(view, size);
    }

    private static int index(String page) { return VIDEO.equals(page) ? 2 : AUDIO.equals(page) ? 1 : 0; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface BooleanChange { void accept(boolean value); }
    private interface IntChange { void accept(int value); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        scrollPositions[index(page)] = scroll.getScrollY();
        state.putString("settings.page", page);
        state.putInt("settings.scroll.home", scrollPositions[0]);
        state.putInt("settings.scroll.audio", scrollPositions[1]);
        state.putInt("settings.scroll.video", scrollPositions[2]);
    }

    /** Exact 24x24 paths from the beta's theme/RavenIcons.kt. */
    private static final class SettingsIcon extends View {
        private final Path path;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SettingsIcon(Context context, String pathData) {
            super(context);
            path = PathParser.createPathFromPathData(pathData);
            paint.setColor(HomeActivity.ACCENT);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }
}
