package com.ravenemu.app.data.saves;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric only for org.json, as AwardPolicyTest. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class SaveTransferTest {
    private static final byte[] ROM = new byte[0x180];
    private static final String ROM_SHA = SaveTransfer.sha256(ROM);
    private static final byte[] BATTERY = {1, 2, 3, 4, 5, 6, 7, 8};

    @Test public void aRawSavOrSrmIsTheGamesSaveWhateverItsCase() throws Exception {
        for (String name : new String[]{"Jeu.sav", "Jeu.SRM", "dossier/Jeu.srm"}) {
            SaveTransfer.Payload payload = read(BATTERY, name, null);
            assertTrue(payload.raw);
            assertArrayEquals(BATTERY, payload.battery);
            assertEquals(0, payload.skippedSlots.length);
        }
    }

    @Test public void theNameAndTheContentMustAgreeAndTheSizeStayBounded() {
        refused(BATTERY, "Jeu.txt", null, "Sélectionnez une sauvegarde .sav ou .srm, ou une archive .zip RavenEmu.");
        refused(new byte[0], "Jeu.sav", null, "Le fichier est vide.");
        refused(new byte[]{'P', 'K', 3, 4, 0}, "Jeu.sav", null,
                "Le contenu du fichier ne correspond pas à son extension.");
        refused(BATTERY, "Jeu.zip", ROM_SHA, "Le contenu du fichier ne correspond pas à son extension.");
        refused(new byte[SaveTransfer.MAX_BATTERY + 1], "Jeu.sav", null,
                "Le fichier de sauvegarde est trop volumineux.");
    }

    @Test public void aGamesPackageGivesItsSaveAndNamesTheStatesLeftOut() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("battery.sav", BATTERY);
        files.put("slot2.state", new byte[]{9});
        files.put("slot2.png", new byte[]{8});
        files.put("slot0.state", new byte[]{7});
        SaveTransfer.Payload payload = read(zip(pkg("", ROM_SHA, files, SaveTransfer.FORMAT)), "Jeu.zip", ROM_SHA);
        assertFalse(payload.raw);
        assertArrayEquals(BATTERY, payload.battery);
        assertArrayEquals(new int[]{1, 3}, payload.skippedSlots);
        // The exact historical generation of the beta stays readable.
        assertArrayEquals(BATTERY, read(zip(pkg("", ROM_SHA, files, "RavenEmu-V3-GB-Saves")), "a.zip", ROM_SHA).battery);
    }

    @Test public void aLibraryArchiveGivesOnlyTheTargetGamesFolder() throws Exception {
        String other = SaveTransfer.sha256(new byte[]{1});
        Map<String, byte[]> theirs = new LinkedHashMap<>(), ours = new LinkedHashMap<>();
        theirs.put("battery.sav", new byte[]{42, 42});
        ours.put("battery.sav", BATTERY);
        Map<String, byte[]> archive = new LinkedHashMap<>(pkg("Autre (abcd1234)/", other, theirs, SaveTransfer.FORMAT));
        archive.putAll(pkg("Jeu (" + ROM_SHA.substring(0, 8) + ")/", ROM_SHA, ours, SaveTransfer.FORMAT));
        assertArrayEquals(BATTERY, read(zip(archive), "RavenEmu-sauvegardes.zip", ROM_SHA).battery);
        refused(zip(pkg("Autre/", other, theirs, SaveTransfer.FORMAT)), "x.zip", ROM_SHA,
                "Cette archive ne contient aucune sauvegarde de ce jeu.");
    }

    @Test public void anotherRomATamperedFileOrAnUnexpectedPathIsRefused() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("battery.sav", BATTERY);
        refused(zip(pkg("", SaveTransfer.sha256(new byte[]{1}), files, SaveTransfer.FORMAT)), "x.zip", ROM_SHA,
                "Cette sauvegarde appartient à une autre ROM : import refusé.");
        refused(zip(pkg("", ROM_SHA, files, "Autre-Format")), "x.zip", ROM_SHA, "Format de sauvegarde non pris en charge.");

        Map<String, byte[]> tampered = pkg("", ROM_SHA, files, SaveTransfer.FORMAT);
        tampered.put("battery.sav", new byte[]{1, 2, 3});
        refused(zip(tampered), "x.zip", ROM_SHA, "Contrôle d'intégrité échoué pour battery.sav.");

        Map<String, byte[]> extra = pkg("", ROM_SHA, files, SaveTransfer.FORMAT);
        extra.put("rom.gb", ROM);
        refused(zip(extra), "x.zip", ROM_SHA, "Fichier inattendu ou doublon dans l'archive.");
        refused(zip(pkg("../", ROM_SHA, files, SaveTransfer.FORMAT)), "x.zip", ROM_SHA, "Dossier inattendu dans l'archive.");
        refused(zip(pkg("a/b/", ROM_SHA, files, SaveTransfer.FORMAT)), "x.zip", ROM_SHA, "Dossier inattendu dans l'archive.");

        Map<String, byte[]> states = new LinkedHashMap<>();
        states.put("slot0.state", new byte[]{7});
        refused(zip(pkg("", ROM_SHA, states, SaveTransfer.FORMAT)), "x.zip", ROM_SHA,
                "Cette archive ne contient que des états de la bêta, sans sauvegarde du jeu.");
        Map<String, byte[]> orphan = new LinkedHashMap<>(files);
        orphan.put("slot1.png", new byte[]{8});
        refused(zip(pkg("", ROM_SHA, orphan, SaveTransfer.FORMAT)), "x.zip", ROM_SHA,
                "Une miniature ne peut pas exister sans save state.");
    }

    @Test public void aSaveOfAnotherSizeIsReadAsTheCoreReadsIt() {
        byte[] fresh = {9, 9, 9, 9};
        assertArrayEquals("Longer: its first bytes", new byte[]{1, 2, 3, 4},
                SaveTransfer.fit(new byte[]{1, 2, 3, 4, 5, 6}, fresh));
        assertArrayEquals("Shorter: the rest stays the core's", new byte[]{1, 2, 9, 9},
                SaveTransfer.fit(new byte[]{1, 2}, fresh));
        byte[] same = {5, 6, 7, 8};
        assertSame(same, SaveTransfer.fit(same, fresh));
        assertArrayEquals("The core's SRAM is never changed", new byte[]{9, 9, 9, 9}, fresh);
        assertNull("A game without SRAM", SaveTransfer.fit(same, null));
        assertNull(SaveTransfer.fit(same, new byte[0]));
    }

    @Test public void anExportKeepsTheRomsNameForOtherEmulators() {
        assertEquals("Pokémon Cristal.srm", SaveTransfer.exportName("Pokémon Cristal.gbc", SaveTransfer.SRM));
        assertEquals("Jeu.sav", SaveTransfer.exportName("dossier/Jeu.gb", SaveTransfer.SAV));
        assertEquals("Zelda.sav", SaveTransfer.exportName("Ze\u0007lda.gba", SaveTransfer.SAV));
        assertEquals("GameBoy.sav", SaveTransfer.exportName("", "?"));
    }

    private static SaveTransfer.Payload read(byte[] bytes, String name, String sha) throws IOException {
        return SaveTransfer.read(new ByteArrayInputStream(bytes), name, sha);
    }

    private static void refused(byte[] bytes, String name, String sha, String reason) {
        try {
            read(bytes, name, sha);
            fail("Accepted: " + name);
        } catch (IOException expected) {
            assertEquals(reason, expected.getMessage());
        }
    }

    /** A package laid out as the beta writes one, manifest first. */
    private static Map<String, byte[]> pkg(String folder, String sha, Map<String, byte[]> files, String format)
            throws Exception {
        JSONObject checksums = new JSONObject();
        for (Map.Entry<String, byte[]> file : files.entrySet())
            checksums.put(file.getKey(), SaveTransfer.sha256(file.getValue()));
        JSONObject manifest = new JSONObject().put("format", format).put("version", SaveTransfer.VERSION)
                .put("romSha256", sha).put("entries", checksums);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(folder + "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> file : files.entrySet()) entries.put(folder + file.getKey(), file.getValue());
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
