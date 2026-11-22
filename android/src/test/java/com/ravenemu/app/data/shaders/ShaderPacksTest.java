package com.ravenemu.app.data.shaders;

import static org.junit.Assert.*;

import com.ravenemu.app.data.library.FolderImport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The packs the player imports: what is kept, where, and what is refused. */
public final class ShaderPacksTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ShaderPacks packs;

    @Before public void prepare() throws IOException {
        packs = new ShaderPacks(temporary.newFolder("files"));
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test public void aGithubArchiveIsUnwrappedAndKeepsOnlyWhatPresetsRead() throws IOException {
        byte[] archive = zip("glsl-shaders-master/crt/crt-geom.glslp", "shaders = 1\nshader0 = shaders/crt-geom.glsl\n",
                "glsl-shaders-master/crt/shaders/crt-geom.glsl", "void main() {}",
                "glsl-shaders-master/crt/shaders/mask.png", "png",
                "glsl-shaders-master/LICENSE", "GPL",
                "glsl-shaders-master/build.sh", "rm -rf /",
                "glsl-shaders-master/.git/config", "secret",
                "glsl-shaders-master/handheld/lcd3x.glslp", "shaders = 1\n");
        String id = packs.importZip("glsl-shaders-master.zip", new ByteArrayInputStream(archive));
        assertEquals("glsl-shaders-master", id);
        File pack = new File(packs.root(), id);
        assertTrue(new File(pack, "crt/shaders/crt-geom.glsl").isFile());
        assertTrue(new File(pack, "crt/shaders/mask.png").isFile());
        assertTrue("Licences stay with the shaders", new File(pack, "LICENSE").isFile());
        assertFalse(new File(pack, "build.sh").exists());
        assertFalse(new File(pack, ".git").exists());
        List<ShaderPacks.Pack> listed = packs.list();
        assertEquals(1, listed.size());
        assertEquals(Arrays.asList(id + "/crt/crt-geom.glslp", id + "/handheld/lcd3x.glslp"), listed.get(0).presets);
        assertTrue(packs.exists(id + "/crt/crt-geom.glslp"));
        assertFalse(packs.exists(id + "/crt/shaders/crt-geom.glsl"));
        assertFalse(packs.exists("../" + id + "/crt/crt-geom.glslp"));
        // The same archive again: a pack of its own.
        assertEquals(id + "-2", packs.importZip("glsl-shaders-master.zip", new ByteArrayInputStream(archive)));
        packs.deletePack(id);
        assertEquals(Collections.singletonList(id + "-2"), Arrays.asList(packs.list().get(0).id));
    }

    @Test public void aPathOutOfThePackOrAPackWithoutPresetIsRefusedWhole() throws IOException {
        for (byte[] archive : Arrays.asList(
                zip("a.glslp", "shaders = 1\n", "../../evil.glsl", "x"),
                zip("a.glslp", "shaders = 1\n", "/abs/evil.glsl", "x"),
                zip("a.glslp", "shaders = 1\n", "C:/evil.glsl", "x"),
                zip("only.glsl", "void main() {}"))) {
            try {
                packs.importZip("pack.zip", new ByteArrayInputStream(archive));
                fail("Imported");
            } catch (IOException refused) {
                assertNotNull(refused.getMessage());
            }
        }
        assertTrue(packs.list().isEmpty());
        String[] left = packs.root().list();
        assertEquals("Nothing left aside", 0, left == null ? 0 : left.length);
        assertFalse(new File(temporary.getRoot(), "evil.glsl").exists());
    }

    @Test public void pathsAreNormalisedInsideThePack() {
        assertEquals("crt/a.glsl", ShaderPacks.normalize("crt/shaders/../a.glsl"));
        assertEquals("crt/a.glsl", ShaderPacks.normalize("crt\\\\a.glsl".replace("\\\\", "\\")));
        assertNull(ShaderPacks.normalize("../a.glsl"));
        assertNull(ShaderPacks.normalize("a/../../b"));
        assertNull(ShaderPacks.normalize("/a"));
        assertNull(ShaderPacks.normalize("a:b"));
        assertEquals("glsl-shaders", ShaderPacks.idFor("glsl-shaders.ZIP"));
        assertEquals("Mes-shaders-CRT", ShaderPacks.idFor("Mes shaders (CRT)"));
        assertEquals("shaders", ShaderPacks.idFor("..."));
    }

    /** A folder of the documents picker, in memory. */
    private static final class Tree implements FolderImport.Tree {
        final Map<String, List<FolderImport.Child>> folders = new LinkedHashMap<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();

        void folder(String id, FolderImport.Child... children) { folders.put(id, Arrays.asList(children)); }

        @Override public String root() { return "root"; }

        @Override public List<FolderImport.Child> children(String folderId) {
            return folders.containsKey(folderId) ? folders.get(folderId) : new ArrayList<>();
        }

        @Override public InputStream open(String documentId) { return new ByteArrayInputStream(files.get(documentId)); }
    }

    @Test public void aChosenFolderIsCopiedWithItsSubfolders() throws IOException {
        Tree tree = new Tree();
        tree.folder("root", new FolderImport.Child("crt", "crt", true, -1),
                new FolderImport.Child("readme", "README.md", false, 3),
                new FolderImport.Child("junk", "notes.docx", false, 3));
        tree.folder("crt", new FolderImport.Child("p", "zfast.glslp", false, 10),
                new FolderImport.Child("s", "zfast.glsl", false, 10),
                new FolderImport.Child("bad", "../escape.glsl", false, 1));
        tree.files.put("readme", "doc".getBytes(StandardCharsets.UTF_8));
        tree.files.put("p", "shaders = 1".getBytes(StandardCharsets.UTF_8));
        tree.files.put("s", "void main(){}".getBytes(StandardCharsets.UTF_8));
        tree.files.put("bad", "x".getBytes(StandardCharsets.UTF_8));
        String id = packs.importFolder("My Shaders", tree);
        assertEquals("My-Shaders", id);
        File pack = new File(packs.root(), id);
        assertEquals("shaders = 1", new String(Files.readAllBytes(new File(pack, "crt/zfast.glslp").toPath()),
                StandardCharsets.UTF_8));
        assertTrue(new File(pack, "README.md").isFile());
        assertFalse(new File(pack, "notes.docx").exists());
        assertFalse(new File(packs.root(), "escape.glsl").exists());
        assertEquals(Collections.singletonList(id + "/crt/zfast.glslp"), packs.list().get(0).presets);
    }
}
