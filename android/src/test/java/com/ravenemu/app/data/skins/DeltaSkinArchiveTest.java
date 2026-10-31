package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkinException.Code;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric only for org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class DeltaSkinArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aCheckedSkinWaitsInStagingUnderFixedNames() throws Exception {
        File root = temporary.newFolder("skins"), work = temporary.newFolder("work");
        Map<String, byte[]> entries = DeltaSkinFixtures.skin(DeltaSkinFixtures.manifest("Test"));
        entries.put("__MACOSX/._skin.pdf", new byte[]{1});
        entries.put(".DS_Store", new byte[]{2});
        File archive = DeltaSkinFixtures.zip(work, "Mon skin.DELTASKIN", entries);
        DeltaSkinArchive.Prepared prepared = new DeltaSkinArchive(root, DeltaSkinFixtures.INSPECTOR, () -> 42L)
                .prepare(archive, archive.getName());

        assertEquals(new File(root, ".staging"), prepared.staging.getParentFile());
        assertTrue(new File(prepared.staging, "skin.deltaskin").isFile());
        assertTrue(new File(prepared.staging, "info.json").isFile());
        assertTrue(new File(prepared.staging, "assets/standard-portrait.pdf").isFile());
        assertFalse("Only the PDFs are extracted", new File(prepared.staging, "assets/.DS_Store").exists());
        DeltaSkinRepository.Metadata metadata = prepared.metadata;
        assertEquals(DeltaSkinArchive.storageKey(DeltaSkinFixtures.IDENTIFIER), metadata.storageKey);
        assertEquals(DeltaSkin.Console.GB_GBC, metadata.console);
        assertEquals("standard-portrait.pdf", metadata.standardPortrait);
        assertNull(metadata.edgePortrait);
        assertEquals(42L, metadata.importedAt);
        assertEquals(archive.length(), metadata.archiveSize);
        assertEquals(DeltaSkinArchive.sha256(archive), metadata.archiveSha256);
        JSONObject stored = new JSONObject(new String(Files.readAllBytes(
                new File(prepared.staging, "metadata.json").toPath()), StandardCharsets.UTF_8));
        assertEquals("x", stored.getJSONArray("ignoredInputs").getString(0));
    }

    @Test public void unsafeArchivesAreRefusedAndLeaveNothingBehind() throws Exception {
        File root = temporary.newFolder("skins"), work = temporary.newFolder("work");
        String manifest = DeltaSkinFixtures.manifest("Test");
        assertRefused(root, work, "skin.zip", DeltaSkinFixtures.skin(manifest), Code.INVALID_EXTENSION);

        Map<String, byte[]> traversal = DeltaSkinFixtures.skin(manifest);
        traversal.put("../evil.pdf", new byte[]{1});
        assertRefused(root, work, "a.deltaskin", traversal, Code.UNSAFE_ARCHIVE_PATH);

        Map<String, byte[]> folder = DeltaSkinFixtures.skin(manifest);
        folder.put("dossier/", null);
        assertRefused(root, work, "b.deltaskin", folder, Code.UNSAFE_ARCHIVE_PATH);

        Map<String, byte[]> nested = DeltaSkinFixtures.skin(manifest);
        nested.put("autre.zip", DeltaSkinFixtures.nestedZip());
        assertRefused(root, work, "c.deltaskin", nested, Code.NESTED_ARCHIVE);

        Map<String, byte[]> disguised = DeltaSkinFixtures.skin(manifest);
        disguised.put("donnees.bin", DeltaSkinFixtures.nestedZip());
        assertRefused(root, work, "d.deltaskin", disguised, Code.NESTED_ARCHIVE);

        Map<String, byte[]> twice = DeltaSkinFixtures.skin(manifest);
        twice.put("INFO.JSON", manifest.getBytes(StandardCharsets.UTF_8));
        assertRefused(root, work, "e.deltaskin", twice, Code.MANIFEST_DUPLICATE);

        Map<String, byte[]> none = new LinkedHashMap<>();
        none.put("skin.pdf", DeltaSkinFixtures.pdf(1, 800, 600));
        assertRefused(root, work, "f.deltaskin", none, Code.MANIFEST_MISSING);

        Map<String, byte[]> many = DeltaSkinFixtures.skin(manifest);
        for (int i = 0; i < 63; i++) many.put("extra" + i + ".txt", new byte[]{1});
        assertRefused(root, work, "g.deltaskin", many, Code.TOO_MANY_ENTRIES);

        Map<String, byte[]> bomb = DeltaSkinFixtures.skin(manifest);
        bomb.put("zeros.bin", DeltaSkinFixtures.zeros(2 * 1024 * 1024));
        assertRefused(root, work, "h.deltaskin", bomb, Code.DECOMPRESSED_TOO_LARGE);

        Map<String, byte[]> noPdf = new LinkedHashMap<>();
        noPdf.put("info.json", manifest.getBytes(StandardCharsets.UTF_8));
        assertRefused(root, work, "i.deltaskin", noPdf, Code.RESIZABLE_ASSET_MISSING);

        Map<String, byte[]> pages = DeltaSkinFixtures.skin(manifest);
        pages.put("skin.pdf", DeltaSkinFixtures.pdf(2, 800, 600));
        assertRefused(root, work, "j.deltaskin", pages, Code.PDF_MULTIPLE_PAGES);

        Map<String, byte[]> aspect = DeltaSkinFixtures.skin(manifest);
        aspect.put("skin.pdf", DeltaSkinFixtures.pdf(1, 800, 800));
        assertRefused(root, work, "k.deltaskin", aspect, Code.PDF_INVALID);

        Map<String, byte[]> unreadable = DeltaSkinFixtures.skin(manifest);
        unreadable.put("skin.pdf", new byte[]{9, 9});
        assertRefused(root, work, "l.deltaskin", unreadable, Code.PDF_INVALID);

        Map<String, byte[]> latin = DeltaSkinFixtures.skin(manifest);
        latin.put("info.json", new byte[]{'{', (byte) 0xe9, '}'});
        assertRefused(root, work, "m.deltaskin", latin, Code.MALFORMED_JSON);

        File link = DeltaSkinFixtures.zip(work, "n.deltaskin", DeltaSkinFixtures.skin(manifest));
        DeltaSkinFixtures.markSymbolicLink(link, "skin.pdf");
        assertRefused(root, link, Code.SYMBOLIC_LINK);

        File text = new File(work, "o.deltaskin");
        Files.write(text.toPath(), "pas une archive".getBytes(StandardCharsets.US_ASCII));
        assertRefused(root, text, Code.NOT_A_ZIP);

        File[] staged = new File(root, ".staging").listFiles();
        assertTrue("No staging folder left after a refusal", staged == null || staged.length == 0);
    }

    private static void assertRefused(File root, File work, String name, Map<String, byte[]> entries, Code expected)
            throws Exception {
        assertRefused(root, DeltaSkinFixtures.zip(work, name, entries), expected);
    }

    private static void assertRefused(File root, File archive, Code expected) {
        try {
            new DeltaSkinArchive(root, DeltaSkinFixtures.INSPECTOR).prepare(archive, archive.getName());
            fail("accepted: " + archive.getName());
        } catch (DeltaSkinException error) {
            assertEquals(archive.getName(), expected, error.code);
        }
    }
}
