package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkin.Console;
import com.ravenemu.app.data.skins.DeltaSkin.Kind;
import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric only for org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class DeltaSkinTest {
    @Test public void theManifestReadsLikeTheBetasAndKeepsUnknownInputsAside() throws Exception {
        DeltaSkin.Manifest manifest = DeltaSkin.parse((char) 0xfeff + DeltaSkinFixtures.manifest("Test"));
        assertEquals("Test", manifest.name);
        assertEquals(DeltaSkinFixtures.IDENTIFIER, manifest.identifier);
        assertNull(manifest.edgePortrait);
        DeltaSkin.Representation portrait = manifest.portrait(Kind.STANDARD);
        assertEquals("skin.pdf", portrait.resizable);
        assertEquals(400, portrait.mapping.width, 0);
        assertEquals(6, portrait.items.size());
        assertTrue(portrait.items.get(0).directional());
        assertEquals("left", portrait.items.get(0).directions.get("left"));
        assertEquals(Arrays.asList("start", "select"), portrait.items.get(4).buttons);
        assertEquals(15, portrait.items.get(1).extendedEdges.left(portrait.extendedEdges), 0);
        assertEquals("An item's missing side is its representation's", 5,
                portrait.items.get(1).extendedEdges.top(portrait.extendedEdges), 0);

        DeltaSkinValidator.Validation validation = DeltaSkinValidator.validate(manifest);
        assertEquals(Console.GB_GBC, validation.console);
        assertEquals(java.util.EnumSet.of(Kind.STANDARD), validation.portraitKinds);
        assertEquals(Arrays.asList("x"), validation.ignoredInputs);
    }

    @Test public void aMissingPropertyOrAWrongTypeRefusesTheSkin() {
        String good = DeltaSkinFixtures.manifest("Test");
        assertCode(Code.MISSING_REQUIRED_PROPERTY, good.replace("\"debug\":false,", ""));
        assertCode(Code.MISSING_REQUIRED_PROPERTY, good.replace("\"identifier\":\"" + DeltaSkinFixtures.IDENTIFIER + "\",", ""));
        assertCode(Code.MALFORMED_JSON, good.replace("\"debug\":false", "\"debug\":\"non\""));
        assertCode(Code.MALFORMED_JSON, good.replace("\"width\":400", "\"width\":\"400\""));
        assertCode(Code.MALFORMED_JSON, good.replace("[\"a\"]", "[1]"));
        assertCode(Code.MALFORMED_JSON, good.replace("[\"a\"]", "12"));
        assertCode(Code.MALFORMED_JSON, "{pas du json");
        assertCode(Code.MISSING_REQUIRED_PROPERTY, good.replace("\"frame\":{\"x\":320", "\"cadre\":{\"x\":320"));
    }

    @Test public void theValidatorKeepsTheBetasLimits() throws Exception {
        String good = DeltaSkinFixtures.manifest("Test");
        assertInvalid(Code.UNSUPPORTED_CONSOLE, DeltaSkinFixtures.manifest("Test", "com.rileytestut.delta.game.nes"));
        assertInvalid(Code.PORTRAIT_REPRESENTATION_MISSING, good.replace("\"portrait\":", "\"landscape\":"));
        assertInvalid(Code.PORTRAIT_REPRESENTATION_MISSING, good.replace("\"iphone\":", "\"ipad\":"));
        assertInvalid(Code.RESIZABLE_ASSET_MISSING, good.replace("skin.pdf", "../skin.pdf"));
        assertInvalid(Code.RESIZABLE_ASSET_MISSING, good.replace("skin.pdf", "dossier/skin.pdf"));
        assertInvalid(Code.RESIZABLE_ASSET_MISSING, good.replace("skin.pdf", "skin.png"));
        assertInvalid(Code.INVALID_MAPPING_SIZE, good.replace("\"width\":400,\"height\":300", "\"width\":0,\"height\":300"));
        assertInvalid(Code.INVALID_FRAME, good.replace("\"x\":320,", "\"x\":350,"));
        assertInvalid(Code.INVALID_FRAME, good.replace("\"x\":20,\"y\":60", "\"x\":-1,\"y\":60"));
        assertInvalid(Code.INVALID_NUMBER, good.replace("\"left\":15", "\"left\":-2"));
        assertInvalid(Code.MISSING_REQUIRED_PROPERTY, good.replace(",\"right\":\"right\"}", "}"));
        assertInvalid(Code.MISSING_REQUIRED_PROPERTY, good.replace("[\"b\"]", "[]"));
        assertInvalid(Code.MISSING_REQUIRED_PROPERTY, good.replace("\"name\":\"Test\"", "\"name\":\"  \""));
        // A frame rounded a little past the mapping size (1 %) is kept, as in the beta.
        DeltaSkinValidator.validate(DeltaSkin.parse(good.replace("\"x\":320,\"y\":80,\"width\":60",
                "\"x\":320,\"y\":80,\"width\":83")));
        DeltaSkinValidator.Validation gba = DeltaSkinValidator.validate(DeltaSkin.parse(
                DeltaSkinFixtures.manifest("GBA", Console.GBA.gameType).replace("[\"x\"]", "[\"l\"]")));
        assertEquals(Console.GBA, gba.console);
        assertTrue("L is a GBA button", gba.ignoredInputs.isEmpty());
    }

    @Test public void onlyANameAtTheArchivesRootIsSafe() {
        for (String name : new String[]{"skin.pdf", "Skin Été.pdf", "a..b.pdf"})
            assertTrue(name, DeltaSkinValidator.isSafeRootAssetName(name));
        for (String name : new String[]{"", " ", "..", ".", "/skin.pdf", "\\skin.pdf", "C:skin.pdf", "a/b.pdf",
                "a\\b.pdf", "a∕b.pdf", "a／b.pdf", "a\u0000.pdf", "a\n.pdf"})
            assertFalse(name, DeltaSkinValidator.isSafeRootAssetName(name));
    }

    private static void assertCode(Code expected, String json) {
        try {
            DeltaSkin.parse(json);
            fail("accepted: " + json);
        } catch (DeltaSkinException error) {
            assertEquals(json, expected, error.code);
        }
    }

    private static void assertInvalid(Code expected, String json) throws DeltaSkinException {
        DeltaSkin.Manifest manifest = DeltaSkin.parse(json);
        try {
            DeltaSkinValidator.validate(manifest);
            fail("accepted: " + json);
        } catch (DeltaSkinException error) {
            assertEquals(json, expected, error.code);
        }
    }
}
