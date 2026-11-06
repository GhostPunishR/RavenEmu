package com.ravenemu.app.data.library;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Robolectric for the library's AtomicFile; the folder is a stand-in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public final class FolderImportTest {
    /** A documents tree in memory: folders by id, files by id. */
    static final class FakeTree implements FolderImport.Tree {
        final Map<String, List<FolderImport.Child>> folders = new HashMap<>();
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> broken = new ArrayList<>();

        FakeTree folder(String parent, String id) {
            folders.computeIfAbsent(id, ignored -> new ArrayList<>());
            if (parent != null) folders.get(parent).add(new FolderImport.Child(id, id, true, -1));
            return this;
        }

        FakeTree file(String parent, String name, byte[] bytes) {
            String id = parent + "/" + name;
            files.put(id, bytes);
            folders.get(parent).add(new FolderImport.Child(id, name, false, bytes == null ? -1 : bytes.length));
            return this;
        }

        @Override public String root() { return "root"; }
        @Override public List<FolderImport.Child> children(String id) throws IOException {
            if (broken.contains(id) || !folders.containsKey(id)) throw new IOException("Le dossier n'est pas accessible.");
            return folders.get(id);
        }
        @Override public InputStream open(String id) throws IOException {
            byte[] bytes = files.get(id);
            if (bytes == null) throw new IOException("ROM illisible");
            return new ByteArrayInputStream(bytes);
        }
    }

    private final File files = RuntimeEnvironment.getApplication().getFilesDir();

    static byte[] gb(int seed) {
        byte[] rom = new byte[0x150];
        rom[0x134] = (byte) seed;
        return rom;
    }

    static byte[] gba(int seed) {
        byte[] rom = new byte[0xc0];
        rom[0xb2] = (byte) 0x96;
        rom[0xa0] = (byte) seed;
        return rom;
    }

    @Test public void subfoldersAreReadAndEachFileCountedAsTheBetaDoes() throws Exception {
        FakeTree tree = new FakeTree().folder(null, "root").folder("root", "sous").folder("root", "cassé");
        tree.file("root", "Alpha.gb", gb(1)).file("root", "notes.txt", new byte[]{1})
                .file("sous", "Beta.gba", gba(2)).file("sous", "Sans marqueur.gba", new byte[0xc0])
                .file("sous", "Copie.gb", gb(1)).file("sous", "Vide.gbc", new byte[0])
                .file("sous", "Illisible.gb", null);
        tree.broken.add("cassé");
        List<Integer> progress = new ArrayList<>();
        FolderImport.Result result = FolderImport.run(files, tree, progress::add);
        assertEquals(2, result.added);
        assertEquals(1, result.duplicate);
        assertEquals("No 0x96 marker, empty", 2, result.invalid);
        assertEquals("A file and a folder", 2, result.unreadable);
        assertEquals(1, result.otherFiles);
        assertEquals(3, result.folders);
        assertFalse(result.limited);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), progress);
        List<String> titles = new ArrayList<>();
        for (RomLibrary.Entry entry : RomLibrary.list(files)) titles.add(entry.title + ":" + entry.system);
        assertEquals(List.of("Alpha.gb:gb", "Beta.gba:gba"), titles);
    }

    @Test public void aGameAlreadyInTheLibraryIsADuplicateAndStaysAsItWas() throws Exception {
        RomLibrary.importRom(files, "Déjà là.gb", gb(7));
        FakeTree tree = new FakeTree().folder(null, "root").file("root", "Autre nom.gb", gb(7));
        FolderImport.Result result = FolderImport.run(files, tree, roms -> {});
        assertEquals(0, result.added);
        assertEquals(1, result.duplicate);
        assertEquals("Déjà là.gb", RomLibrary.list(files).get(0).title);
    }

    @Test public void deepFoldersAndLoopsStopAtTheBetasLimits() throws Exception {
        FakeTree tree = new FakeTree().folder(null, "root");
        String parent = "root";
        for (int depth = 1; depth <= 10; depth++) {
            tree.folder(parent, "d" + depth);
            parent = "d" + depth;
        }
        // A folder listing itself again is read once.
        tree.folders.get("d1").add(new FolderImport.Child("d1", "d1", true, -1));
        tree.file("d10", "Profond.gb", gb(3));
        FolderImport.Result result = FolderImport.run(files, tree, roms -> {});
        assertTrue(result.limited);
        assertEquals("The root and 8 levels", 9, result.folders);
        assertEquals(0, result.added);
    }

    @Test public void tooManyEntriesStopTheImportAndKeepWhatWasAdded() throws Exception {
        FakeTree tree = new FakeTree().folder(null, "root").file("root", "Premier.gb", gb(4));
        for (int i = 0; i < FolderImport.MAX_ENTRIES; i++) tree.file("root", "f" + i + ".txt", new byte[]{1});
        FolderImport.Result result = FolderImport.run(files, tree, roms -> {});
        assertTrue(result.limited);
        assertEquals(1, result.added);
        assertEquals(1, RomLibrary.list(files).size());
    }

    @Test public void theChosenFolderMustOpen() {
        FakeTree tree = new FakeTree();
        try {
            FolderImport.run(files, tree, roms -> {});
            fail();
        } catch (IOException expected) {
            assertEquals("Le dossier n'est pas accessible.", expected.getMessage());
        }
    }

    @Test public void onlyHeadersRavenCoreLoadsCount() {
        assertTrue(FolderImport.plausible("gb", gb(0)));
        assertFalse(FolderImport.plausible("gbc", new byte[0x14f]));
        assertTrue(FolderImport.plausible("gba", gba(0)));
        assertFalse(FolderImport.plausible("gba", new byte[0xc0]));
        byte[] shortGba = new byte[0xb3];
        shortGba[0xb2] = (byte) 0x96;
        assertFalse(FolderImport.plausible("gba", shortGba));
    }
}
