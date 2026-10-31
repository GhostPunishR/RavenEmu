package com.ravenemu.app.ui.player;

import com.ravenemu.app.bridge.NativeBridge;
import com.ravenemu.app.data.skins.DeltaSkin;
import com.ravenemu.app.data.skins.DeltaSkinFixtures;
import com.ravenemu.app.ui.player.DeltaSkinLayout.Box;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** The beta's layout and touch rules on the fixtures' skin (400 × 300).
 * Robolectric only for org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class DeltaSkinInputTest {
    @Test public void thePanelSitsAtTheBottomAndThePictureAboveAtItsAspect() throws Exception {
        DeltaSkin.Representation skin = representation();
        DeltaSkinLayout layout = DeltaSkinLayout.calculate(800, 1400, skin.mapping, 160, 144);
        assertEquals(2.0, layout.scale, 0);
        assertEquals(new Box(0, 800, 800, 1400), layout.panel);
        assertEquals(new Box(0, 0, 800, 800), layout.gameArea);
        assertEquals("The picture centred in its area", new Box(0, 40, 800, 760), layout.gameScreen);
        assertNull("No room left for the picture", DeltaSkinLayout.calculate(800, 600, skin.mapping, 160, 144));
        assertEquals(new Box(40, 920, 280, 1160), DeltaSkinLayout.map(skin.items.get(0).frame, layout.panel, skin.mapping));
    }

    @Test public void aTallPhoneTakesTheEdgeToEdgeKindWhenTheSkinHasIt() {
        Set<DeltaSkin.Kind> both = EnumSet.allOf(DeltaSkin.Kind.class);
        assertEquals(DeltaSkin.Kind.STANDARD, DeltaSkinLayout.select(both, 1000, 1900));
        assertEquals(DeltaSkin.Kind.EDGE_TO_EDGE, DeltaSkinLayout.select(both, 1000, 2000));
        assertEquals(DeltaSkin.Kind.STANDARD, DeltaSkinLayout.select(EnumSet.of(DeltaSkin.Kind.STANDARD), 1000, 2400));
        assertEquals(DeltaSkin.Kind.EDGE_TO_EDGE, DeltaSkinLayout.select(EnumSet.of(DeltaSkin.Kind.EDGE_TO_EDGE), 1000, 1000));
        assertNull(DeltaSkinLayout.select(EnumSet.noneOf(DeltaSkin.Kind.class), 1000, 1000));
    }

    @Test public void touchesPressTheSkinsButtonsAsTheBetaDoes() throws Exception {
        DeltaSkin.Representation skin = representation();
        Box panel = new Box(0, 300, 400, 600);
        DeltaSkinInput input = new DeltaSkinInput(DeltaSkin.Console.GB_GBC, skin, panel);
        assertEquals("Outside the panel", 0, input.at(200, 100).mask);
        assertEquals(NativeBridge.A, input.at(350, 410).mask);
        assertEquals("A's wider left edge", NativeBridge.A, input.at(310, 410).mask);
        assertEquals(NativeBridge.START | NativeBridge.SELECT, input.at(200, 555).mask);
        assertEquals("D-pad up", NativeBridge.UP, input.at(80, 370).mask);
        assertEquals("D-pad diagonal", NativeBridge.DOWN | NativeBridge.RIGHT, input.at(130, 470).mask);
        DeltaSkinInput.Mapped hub = input.at(80, 420);
        assertEquals("The hub presses nothing", 0, hub.mask);
        assertTrue(hub.visuals.isEmpty());
        DeltaSkinInput.Mapped menu = input.at(200, 325);
        assertTrue(menu.menu);
        assertEquals(0, menu.mask);
        assertEquals("An input RavenCore lacks", 0, input.at(370, 320).mask);
        assertTrue(input.at(370, 320).empty());
        assertEquals("L is only a GBA button", 0, new DeltaSkinInput(DeltaSkin.Console.GB_GBC,
                representation("[\"l\"]"), panel).at(370, 320).mask);
        assertEquals(NativeBridge.L, new DeltaSkinInput(DeltaSkin.Console.GBA,
                representation("[\"l\"]"), panel).at(370, 320).mask);
    }

    @Test public void aButtonIsReleasedWithItsLastFingerAndTheMenuOpensOnce() throws Exception {
        List<Integer> masks = new ArrayList<>();
        int[] menus = {0};
        DeltaSkinInput.Fingers fingers = new DeltaSkinInput.Fingers(new DeltaSkinInput.Fingers.Listener() {
            @Override public void buttons(int mask) { masks.add(mask); }
            @Override public void menu() { menus[0]++; }
            @Override public void visuals(Set<DeltaSkinInput.Target> visuals) {}
        });
        DeltaSkinInput input = new DeltaSkinInput(DeltaSkin.Console.GB_GBC, representation(), new Box(0, 300, 400, 600));
        fingers.update(1, input.at(350, 410));
        fingers.update(2, input.at(345, 400));
        fingers.update(3, input.at(80, 370));
        assertEquals(NativeBridge.A | NativeBridge.UP, fingers.mask());
        fingers.release(1);
        assertEquals("Another finger still holds A", NativeBridge.A | NativeBridge.UP, fingers.mask());
        fingers.release(2);
        assertEquals(NativeBridge.UP, fingers.mask());
        fingers.update(3, input.at(130, 470));
        assertEquals("Sliding on the D-pad", NativeBridge.DOWN | NativeBridge.RIGHT, fingers.mask());
        fingers.update(4, input.at(200, 325));
        fingers.update(4, input.at(205, 330));
        assertEquals("Opened once while held", 1, menus[0]);
        fingers.releaseAll();
        assertEquals(0, fingers.mask());
        assertEquals(Integer.valueOf(0), masks.get(masks.size() - 1));
        fingers.update(5, input.at(200, 325));
        assertEquals(2, menus[0]);
    }

    private static DeltaSkin.Representation representation() throws Exception {
        return DeltaSkin.parse(DeltaSkinFixtures.manifest("Test")).portrait(DeltaSkin.Kind.STANDARD);
    }

    private static DeltaSkin.Representation representation(String lastItem) throws Exception {
        return DeltaSkin.parse(DeltaSkinFixtures.manifest("Test").replace("[\"x\"]", lastItem))
                .portrait(DeltaSkin.Kind.STANDARD);
    }
}
