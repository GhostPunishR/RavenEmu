package com.ravenemu.app.ui.library;

import com.ravenemu.app.data.library.RomLibrary;

import android.app.Activity;
import android.view.View;
import android.widget.GridView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** The grid's tiles of one row share its height, as the beta's lazy grid:
 * GridView places a row from one of its tiles, so tiles of different
 * heights shifted while scrolling, the more so going back up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class RavenLibraryAdapterTest {
    private static final String LONG = "Une aventure au titre vraiment très long pour une tuile de la grille";
    private static final int WIDTH = 320, HEIGHT = 400, SPACING = 6;

    /** As HomeActivity's grid (mdpi: a dp is a pixel). */
    private static GridView grid(List<RomLibrary.Entry> games) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        GridView grid = new GridView(activity);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(106);
        grid.setHorizontalSpacing(8);
        grid.setVerticalSpacing(SPACING);
        RavenLibraryAdapter adapter = new RavenLibraryAdapter(activity, activity.getFilesDir(), Runnable::run);
        grid.setAdapter(adapter);
        adapter.replace(games);
        layout(grid);
        return grid;
    }

    private static void layout(GridView grid) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        grid.layout(0, 0, WIDTH, HEIGHT);
    }

    /** One long title in every third tile: rows mix short and long ones, the
     * long one first, last, or alone. */
    private static List<RomLibrary.Entry> games() {
        List<RomLibrary.Entry> games = new ArrayList<>();
        for (int i = 0; i < 30; i++)
            games.add(new RomLibrary.Entry("k" + i, i % 3 == 0 ? LONG + " " + i : "Jeu " + i, "gb", 0x8000));
        return games;
    }

    /** Every visible row: its tiles at one top with one height, the next row
     * right under it. */
    private static void assertRowsAligned(GridView grid, String when) {
        int columns = grid.getNumColumns();
        assertTrue(when + ": several columns", columns > 1);
        Integer previousBottom = null;
        for (int i = 0; i < grid.getChildCount(); i++) {
            int position = grid.getFirstVisiblePosition() + i;
            View tile = grid.getChildAt(i);
            if (position % columns != 0) {
                View first = grid.getChildAt(i - position % columns);
                assertEquals(when + ": top of tile " + position, first.getTop(), tile.getTop());
                assertEquals(when + ": height of tile " + position, first.getHeight(), tile.getHeight());
                continue;
            }
            if (previousBottom != null)
                assertEquals(when + ": row of tile " + position, previousBottom + SPACING, tile.getTop());
            previousBottom = tile.getBottom();
        }
    }

    @Test public void theTilesOfARowStayAlignedWhileScrollingDownAndBackUp() {
        GridView grid = grid(games());
        assertRowsAligned(grid, "At first");
        for (int step = 0; step < 6; step++) {
            grid.scrollListBy(137);
            layout(grid);
            assertRowsAligned(grid, "Down " + step);
        }
        assertTrue("Scrolled past the first rows", grid.getFirstVisiblePosition() > 0);
        for (int step = 0; step < 6; step++) {
            grid.scrollListBy(-137);
            layout(grid);
            assertRowsAligned(grid, "Up " + step);
        }
    }

    @Test public void aRowTakesItsLongestTitleWholeAndNoMore() {
        GridView grid = grid(games());
        TextView longTitle = title(grid.getChildAt(0)), shortTitle = title(grid.getChildAt(1));
        assertTrue("The long title wraps", longTitle.getLineCount() > 1);
        assertEquals("Its row's short title keeps its room", longTitle.getHeight(), shortTitle.getHeight());
        // Tiles 2 and 3 hold "Jeu 2" and the long title 3; tiles 4 and 5, two short titles.
        assertEquals(grid.getChildAt(3).getHeight(), grid.getChildAt(2).getHeight());
        assertTrue("A row of short titles stays low, as in the beta",
                grid.getChildAt(4).getHeight() < grid.getChildAt(0).getHeight());
    }

    @Test public void aChangedSearchRegroupsTheRows() {
        GridView grid = grid(games());
        List<RomLibrary.Entry> shorter = new ArrayList<>();
        for (int i = 1; i < 30; i += 3) shorter.add(new RomLibrary.Entry("k" + i, "Jeu " + i, "gb", 0x8000));
        ((RavenLibraryAdapter) grid.getAdapter()).replace(shorter);
        layout(grid);
        assertRowsAligned(grid, "Short titles only");
        assertEquals("No long title left: one line", 1, title(grid.getChildAt(0)).getLineCount());
    }

    /** The tile's title: the first text under its artwork. */
    private static TextView title(View tile) {
        android.view.ViewGroup labels = (android.view.ViewGroup) ((android.view.ViewGroup) tile).getChildAt(1);
        return (TextView) labels.getChildAt(0);
    }
}
