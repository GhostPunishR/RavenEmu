package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeAchievements;
import com.ravenemu.app.ui.library.HomeActivity;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Date;
import java.util.List;

/** RetroAchievements in the game menu, after the beta's RetroAccountCard,
 * RetroAchievementsContent and RetroLeaderboardsContent: the account at a
 * glance, then the panel with the account, the game's achievements (filters,
 * search, sort, details) and its leaderboards. Views only: every action goes
 * to the listener. UI thread. */
final class AchievementsPanel {
    interface Actions {
        void back();
        /** Signed out: Settings → RetroAchievements. */
        void signIn();
        void retry();
        void signOut();
        void fetchScores(int board, boolean around);
        void openLink(String url);
    }

    static final int CARD = 0xff202230, ERROR = 0xffff928b;
    private static final String[] FILTERS = {"Tous", "À obtenir", "Obtenus"};
    private static final int FIRST_PAGE = 10, MORE = 15;

    private final Activity owner;
    private final BadgeCache badges;
    private final Actions actions;
    private final LinearLayout root, header, list, boards;
    private final Button[] filters = new Button[FILTERS.length];
    private final Button sortButton;
    private final LinearLayout controls;
    private AchievementsSnapshot snapshot = AchievementsSnapshot.SIGNED_OUT;
    private int filter, sort, visible = FIRST_PAGE, visibleBoards = FIRST_PAGE, selectedBoard;
    private String query = "";
    private AlertDialog details;

    AchievementsPanel(Activity owner, BadgeCache badges, Actions actions) {
        this.owner = owner;
        this.badges = badges;
        this.actions = actions;
        root = column();
        Button back = textButton("‹ Retour au menu du jeu", HomeActivity.ACCENT);
        back.setOnClickListener(view -> actions.back());
        root.addView(back, new LinearLayout.LayoutParams(-2, -2));
        header = column();
        add(root, header, 4);
        controls = column();
        TextView title = text("Succès du jeu", 15, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        controls.addView(title);
        LinearLayout row = new LinearLayout(owner);
        for (int i = 0; i < FILTERS.length; i++) {
            final int index = i;
            Button button = textButton(FILTERS[i], HomeActivity.MUTED);
            button.setTextSize(12);
            button.setOnClickListener(view -> {
                filter = index;
                visible = FIRST_PAGE;
                renderList();
            });
            row.addView(button, new LinearLayout.LayoutParams(-2, -2));
            filters[i] = button;
        }
        add(controls, row, 4);
        EditText search = new EditText(owner);
        search.setHint("Rechercher un succès");
        search.setContentDescription("Rechercher un succès");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        search.setTextColor(HomeActivity.TEXT);
        search.setHintTextColor(HomeActivity.MUTED);
        search.setTextSize(14);
        search.setMinHeight(dp(48));
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setSaveEnabled(false);
        GradientDrawable outline = shape(HomeActivity.SURFACE, 10);
        outline.setStroke(dp(1), HomeActivity.OUTLINE);
        search.setBackground(outline);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                query = text.toString();
                visible = FIRST_PAGE;
                renderList();
            }
        });
        add(controls, search, 6);
        sortButton = textButton("", HomeActivity.ACCENT);
        sortButton.setOnClickListener(view -> {
            sort = (sort + 1) % AchievementsSnapshot.SORT_LABELS.length;
            visible = FIRST_PAGE;
            renderList();
        });
        add(controls, sortButton, 2);
        add(root, controls, 14);
        list = column();
        add(root, list, 6);
        boards = column();
        add(root, boards, 14);
        add(root, text("Mode Softcore · SDK officiel " + sdk(), 11, HomeActivity.MUTED), 14);
    }

    View view() { return root; }

    /** A new snapshot: the same filters, search and page stay. */
    void show(AchievementsSnapshot snapshot) {
        boolean sameGame = snapshot.gameId == this.snapshot.gameId && snapshot.user.equals(this.snapshot.user);
        this.snapshot = snapshot;
        if (!sameGame) {
            visible = visibleBoards = FIRST_PAGE;
            selectedBoard = 0;
        }
        renderHeader();
        controls.setVisibility(snapshot.signedIn && snapshot.entries.length > 0 ? View.VISIBLE : View.GONE);
        renderList();
        renderBoards();
    }

    void dismissDetails() {
        if (details != null) details.dismiss();
        details = null;
    }

    /** The beta's RetroAccountCard, for the game menu. */
    View card(AchievementsSnapshot snapshot, Runnable open) {
        String[] lines = snapshot.cardLines();
        LinearLayout card = new LinearLayout(owner);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = shape(CARD, 14);
        background.setStroke(dp(1), HomeActivity.OUTLINE);
        card.setBackground(background);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setMinimumHeight(dp(68));
        FrameLayout avatar = new FrameLayout(owner);
        avatar.setBackground(shape(0xff292a38, 10));
        avatar.setClipToOutline(true);
        TextView initials = text("RA", 14, HomeActivity.ACCENT);
        initials.setTypeface(null, Typeface.BOLD);
        initials.setGravity(Gravity.CENTER);
        avatar.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        ImageView picture = new ImageView(owner);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        if (snapshot.signedIn) badges.load(snapshot.avatarUrl, image -> {
            picture.setImageBitmap(image);
            initials.setVisibility(View.GONE);
        }, () -> picture.isAttachedToWindow() || picture.getParent() != null);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout text = column();
        LinearLayout top = new LinearLayout(owner);
        top.addView(text("RetroAchievements", 11, HomeActivity.MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        if (!lines[3].isEmpty()) {
            TextView mode = text(lines[3], 11, HomeActivity.SECONDARY);
            mode.setTypeface(null, Typeface.BOLD);
            top.addView(mode);
        }
        text.addView(top);
        TextView name = line(lines[0], 15, HomeActivity.TEXT, 1);
        name.setTypeface(null, Typeface.BOLD);
        add(text, name, 2);
        add(text, line(lines[1], 12, HomeActivity.SECONDARY, 2), 2);
        if (!lines[2].isEmpty()) add(text, line(lines[2], 12, HomeActivity.MUTED, 2), 2);
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, -2, 1);
        middle.setMarginStart(dp(12));
        middle.setMarginEnd(dp(8));
        card.addView(text, middle);
        card.addView(text("›", 22, HomeActivity.MUTED));
        card.setContentDescription("RetroAchievements. " + lines[0] + ". " + lines[1]
                + (lines[2].isEmpty() ? "" : ". " + lines[2]) + ". Ouvrir RetroAchievements");
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(view -> open.run());
        return card;
    }

    private void renderHeader() {
        header.removeAllViews();
        TextView title = text("RetroAchievements", 18, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title);
        add(header, text(snapshot.status, 12, HomeActivity.MUTED), 4);
        if (!snapshot.signedIn) {
            add(header, text("Connectez votre compte pour obtenir les succès officiels pendant vos parties Game Boy, "
                    + "Game Boy Color et Game Boy Advance. Mode normal (Softcore).", 13, HomeActivity.MUTED), 10);
            Button signIn = button("Se connecter", HomeActivity.ACCENT, HomeActivity.BACKGROUND);
            signIn.setContentDescription("Se connecter à RetroAchievements dans les paramètres");
            signIn.setOnClickListener(view -> actions.signIn());
            add(header, signIn, 12);
            return;
        }
        LinearLayout summary = column();
        summary.setBackground(shape(HomeActivity.SURFACE, 14));
        summary.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout identity = new LinearLayout(owner);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(owner);
        avatar.setContentDescription("Avatar RetroAchievements");
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        avatar.setVisibility(View.GONE);
        badges.load(snapshot.avatarUrl, image -> {
            avatar.setImageBitmap(image);
            avatar.setVisibility(View.VISIBLE);
        }, () -> avatar.getParent() != null);
        LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(dp(58), dp(58));
        picture.setMarginEnd(dp(12));
        identity.addView(avatar, picture);
        LinearLayout names = column();
        TextView name = text(snapshot.name(), 17, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        names.addView(name);
        names.addView(text("@" + snapshot.user, 12, HomeActivity.MUTED));
        identity.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(identity);
        add(summary, text("Softcore : " + snapshot.softcorePoints + " points  ·  Hardcore : "
                + snapshot.hardcorePoints + " points", 12, HomeActivity.ACCENT), 6);
        if (!snapshot.gameTitle.isEmpty()) {
            View divider = new View(owner);
            divider.setBackgroundColor(HomeActivity.OUTLINE);
            LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, dp(1));
            rule.topMargin = dp(8);
            summary.addView(divider, rule);
            TextView game = text(snapshot.gameTitle, 15, HomeActivity.TEXT);
            game.setTypeface(null, Typeface.BOLD);
            add(summary, game, 8);
            if (!snapshot.richPresence.isEmpty()) add(summary, text(snapshot.richPresence, 12, HomeActivity.MUTED), 4);
            add(summary, text(snapshot.unlocked + "/" + snapshot.total + " succès  ·  " + snapshot.pointsUnlocked
                    + "/" + snapshot.pointsTotal + " points", 12, HomeActivity.MUTED), 4);
        } else {
            add(summary, text("Aucun jeu reconnu pour le moment.", 12, HomeActivity.MUTED), 6);
        }
        add(header, summary, 10);
        if (snapshot.pendingAwards > 0)
            add(header, text(snapshot.pendingAwards + " déblocage(s) en attente sur cet appareil. La confirmation "
                    + "du serveur est nécessaire.", 12, HomeActivity.ACCENT), 8);
        LinearLayout buttons = new LinearLayout(owner);
        Button retry = button("Réessayer", 0xff292a38, HomeActivity.TEXT);
        retry.setTextSize(12);
        retry.setContentDescription("Reconnecter RetroAchievements pour ce jeu");
        retry.setOnClickListener(view -> actions.retry());
        Button signOut = button("Déconnexion", 0xff292a38, HomeActivity.TEXT);
        signOut.setTextSize(12);
        signOut.setContentDescription("Se déconnecter de RetroAchievements");
        signOut.setOnClickListener(view -> actions.signOut());
        buttons.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(dp(8));
        buttons.addView(signOut, second);
        add(header, buttons, 10);
    }

    private void renderList() {
        for (int i = 0; i < filters.length; i++) {
            filters[i].setTextColor(filter == i ? HomeActivity.ACCENT : HomeActivity.MUTED);
            filters[i].setContentDescription("Afficher : " + FILTERS[i] + (filter == i ? ", choisi" : ""));
        }
        sortButton.setText("Tri : " + AchievementsSnapshot.SORT_LABELS[sort]);
        list.removeAllViews();
        if (!snapshot.signedIn || snapshot.entries.length == 0) return;
        List<Integer> items = snapshot.list(query, filter, sort);
        if (items.isEmpty()) add(list, text("Aucun succès ne correspond à cette recherche.", 13, HomeActivity.MUTED), 4);
        for (int k = 0; k < Math.min(visible, items.size()); k++) add(list, row(items.get(k)), k == 0 ? 0 : 8);
        if (items.size() > visible) {
            Button more = textButton("Afficher plus (" + (items.size() - visible) + " restants)", HomeActivity.ACCENT);
            more.setOnClickListener(view -> {
                visible += MORE;
                renderList();
            });
            add(list, more, 4);
        }
    }

    /** The beta's RetroAchievementRow. */
    private View row(int index) {
        NativeAchievements.Entry entry = snapshot.entries[index];
        LinearLayout row = column();
        row.setBackground(shape(0xa6191b28, 10));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout top = new LinearLayout(owner);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView badge = new ImageView(owner);
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badge.setBackground(shape(0x2eaaa2bc, 6));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        badges.into(badge, entry.badge);
        top.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = line(entry.title, 13, HomeActivity.TEXT, 2);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, -2, 1);
        grow.setMarginStart(dp(10));
        grow.setMarginEnd(dp(6));
        top.addView(title, grow);
        top.addView(text(entry.points + " pts", 11, HomeActivity.ACCENT));
        row.addView(top);
        String state = snapshot.stateLine(index);
        add(row, text(state, 11, HomeActivity.MUTED), 3);
        String date = date(entry.unlockedAt);
        if (!date.isEmpty()) add(row, text(date, 11, HomeActivity.MUTED), 2);
        row.setContentDescription(entry.title + ", " + entry.points + " points, " + state);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> showDetails(index));
        return row;
    }

    /** The beta's RetroAchievementDialog. */
    private void showDetails(int index) {
        NativeAchievements.Entry entry = snapshot.entries[index];
        dismissDetails();
        LinearLayout content = column();
        content.setPadding(dp(24), dp(8), dp(24), 0);
        ImageView badge = new ImageView(owner);
        badge.setContentDescription("Badge du succès");
        badge.setScaleType(ImageView.ScaleType.FIT_CENTER);
        badges.into(badge, entry.badge);
        content.addView(badge, new LinearLayout.LayoutParams(dp(88), dp(88)));
        int sync = index < snapshot.sync.length ? snapshot.sync[index] : AchievementsSnapshot.SYNC_NONE;
        String state = sync != AchievementsSnapshot.SYNC_NONE ? AchievementsSnapshot.SYNC_LABELS[sync]
                : entry.unlocked() ? "Débloqué" : "À obtenir";
        String date = date(entry.unlockedAt);
        add(content, text(entry.description + "\n\n" + entry.points + " points · " + state
                + (date.isEmpty() ? "" : "\n" + date), 14, HomeActivity.TEXT), 10);
        details = new AlertDialog.Builder(owner).setTitle(entry.title).setView(content)
                .setPositiveButton("Fermer", null)
                .setNegativeButton("Voir sur le site", (ignored, which) ->
                        actions.openLink("https://retroachievements.org/achievement/" + entry.id))
                .create();
        details.show();
        details.getWindow().setBackgroundDrawable(shape(HomeActivity.SURFACE, 18));
        details.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(HomeActivity.ACCENT);
        details.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(HomeActivity.ACCENT);
    }

    /** The beta's RetroLeaderboardsContent. */
    private void renderBoards() {
        boards.removeAllViews();
        if (!snapshot.signedIn || snapshot.gameId == 0) return;
        View divider = new View(owner);
        divider.setBackgroundColor(HomeActivity.OUTLINE);
        boards.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        TextView title = text("Classements", 15, HomeActivity.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        add(boards, title, 10);
        add(boards, text("Consultation des scores disponible. Les tentatives officielles nécessitent le mode "
                + "Hardcore.", 12, HomeActivity.MUTED), 4);
        if (snapshot.boards.length == 0) {
            add(boards, text("Aucun classement pour ce jeu.", 13, HomeActivity.MUTED), 6);
            return;
        }
        for (int i = 0; i < Math.min(visibleBoards, snapshot.boards.length); i++) {
            final NativeAchievements.Board board = snapshot.boards[i];
            Button open = textButton(board.title, HomeActivity.ACCENT);
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setContentDescription("Classement " + board.title);
            open.setOnClickListener(view -> {
                selectedBoard = board.id;
                actions.fetchScores(board.id, false);
            });
            add(boards, open, 2);
        }
        if (snapshot.boards.length > visibleBoards) {
            Button more = textButton("Afficher plus de classements", HomeActivity.ACCENT);
            more.setOnClickListener(view -> {
                visibleBoards += MORE;
                renderBoards();
            });
            add(boards, more, 2);
        }
        NativeAchievements.Board board = null;
        for (NativeAchievements.Board candidate : snapshot.boards) if (candidate.id == selectedBoard) board = candidate;
        if (board == null) return;
        final int id = board.id;
        TextView name = text(board.title, 15, HomeActivity.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        add(boards, name, 12);
        if (!board.description.isEmpty()) add(boards, text(board.description, 12, HomeActivity.MUTED), 4);
        if (!board.tracker.isEmpty()) add(boards, text("Tentative : " + board.tracker, 13, HomeActivity.ACCENT), 4);
        LinearLayout modes = new LinearLayout(owner);
        boolean loading = snapshot.scoresLoading && snapshot.scoresBoard == id;
        Button best = textButton("Meilleurs scores", HomeActivity.ACCENT);
        best.setEnabled(!loading);
        best.setOnClickListener(view -> actions.fetchScores(id, false));
        Button around = textButton("Autour de moi", HomeActivity.ACCENT);
        around.setEnabled(!loading);
        around.setOnClickListener(view -> actions.fetchScores(id, true));
        modes.addView(best, new LinearLayout.LayoutParams(-2, -2));
        modes.addView(around, new LinearLayout.LayoutParams(-2, -2));
        add(boards, modes, 4);
        if (snapshot.scoresBoard == id) {
            if (loading) {
                ProgressBar spinner = new ProgressBar(owner);
                spinner.setContentDescription("Chargement des scores");
                boards.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
            } else if (!snapshot.scoresError.isEmpty()) {
                add(boards, text(snapshot.scoresError, 13, HomeActivity.MUTED), 4);
                Button again = textButton("Réessayer les scores", HomeActivity.ACCENT);
                final boolean wasAround = snapshot.scoresAround;
                again.setOnClickListener(view -> actions.fetchScores(id, wasAround));
                add(boards, again, 2);
            } else if (snapshot.scores != null && snapshot.scores.board == id) {
                add(boards, text((snapshot.scores.around ? "Scores autour de moi · " : "Meilleurs scores · ")
                        + snapshot.scores.total + " participants", 12, HomeActivity.MUTED), 4);
                if (snapshot.scores.scores.length == 0)
                    add(boards, text("Aucun score enregistré.", 13, HomeActivity.MUTED), 4);
                for (NativeAchievements.Score score : snapshot.scores.scores) {
                    add(boards, text("#" + score.rank + " · " + score.user + (score.player ? " (vous)" : "")
                            + "\n" + score.score, 13, score.player ? HomeActivity.ACCENT : HomeActivity.TEXT), 6);
                    String date = date(score.submitted);
                    if (!date.isEmpty()) add(boards, text(date, 11, HomeActivity.MUTED), 1);
                }
            }
        }
        Button site = textButton("Classement complet sur le site", HomeActivity.ACCENT);
        site.setOnClickListener(view -> actions.openLink("https://retroachievements.org/leaderboardinfo.php?i=" + id));
        add(boards, site, 6);
    }

    private static String sdk() {
        try {
            return NativeAchievements.sdkVersion();
        } catch (LinkageError withoutNativeLibrary) {
            return "rcheevos";
        }
    }

    /** The host's date and time, "" for none. */
    private String date(long seconds) {
        if (seconds <= 0) return "";
        Date when = new Date(seconds * 1000L);
        return android.text.format.DateFormat.getMediumDateFormat(owner).format(when) + " · "
                + android.text.format.DateFormat.getTimeFormat(owner).format(when);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(owner);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private TextView line(String value, int size, int color, int lines) {
        TextView view = text(value, size, color);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private Button button(String label, int color, int textColor) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(shape(color, 12));
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
