package com.ravenemu.app.data.skins;

import com.ravenemu.app.data.skins.DeltaSkinException.Code;
import com.ravenemu.app.data.skins.DeltaSkinRepository.Outcome;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
public final class DeltaSkinRepositoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void installDuplicateConflictReplaceAndDelete() throws Exception {
        File root = temporary.newFolder("skins"), work = temporary.newFolder("work");
        DeltaSkinRepository repository = new DeltaSkinRepository(root);
        File premier = archive(work, "a.deltaskin", "Premier");
        DeltaSkinRepository.Result first = repository.install(prepare(root, premier), false);
        assertEquals(Outcome.INSTALLED, first.outcome);
        assertEquals(1, repository.list().size());
        DeltaSkinRepository.Installed installed = repository.find(DeltaSkinFixtures.IDENTIFIER);
        assertEquals("Premier", installed.manifest.name);
        assertEquals(DeltaSkinArchive.storageKey(DeltaSkinFixtures.IDENTIFIER), installed.directory.getName());
        assertTrue(installed.pdf(DeltaSkin.Kind.STANDARD).isFile());
        assertNull(installed.pdf(DeltaSkin.Kind.EDGE_TO_EDGE));
        assertTrue(installed.occupiedBytes > 0);

        DeltaSkinArchive.Prepared again = prepare(root, premier);
        assertEquals(Outcome.DUPLICATE, repository.install(again, false).outcome);
        assertFalse("A duplicate's staging folder is discarded", again.staging.exists());

        DeltaSkinArchive.Prepared update = prepare(root, archive(work, "b.deltaskin", "Second"));
        DeltaSkinRepository.Result conflict = repository.install(update, false);
        assertEquals(Outcome.CONFLICT, conflict.outcome);
        assertEquals("Premier", conflict.skin.manifest.name);
        DeltaSkinRepository.Result replaced = repository.install(update, true);
        assertEquals(Outcome.REPLACED, replaced.outcome);
        assertEquals(first.skin.metadata.archiveSha256, replaced.previousSha256);
        assertEquals("Second", repository.find(DeltaSkinFixtures.IDENTIFIER).manifest.name);
        assertEquals(1, repository.list().size());

        assertNull(repository.delete("inconnu"));
        assertEquals("Second", repository.delete(DeltaSkinFixtures.IDENTIFIER).manifest.name);
        assertTrue(repository.list().isEmpty());
        assertNull(repository.find(DeltaSkinFixtures.IDENTIFIER));
    }

    @Test public void aFolderThatDoesNotReadBackWholeIsNotListed() throws Exception {
        File root = temporary.newFolder("skins"), work = temporary.newFolder("work");
        DeltaSkinRepository repository = new DeltaSkinRepository(root);
        DeltaSkinRepository.Installed skin = repository.install(
                prepare(root, archive(work, "a.deltaskin", "Test")), false).skin;
        File metadata = new File(skin.directory, "metadata.json");
        String original = new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8);

        Files.write(metadata.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(repository.list().isEmpty());
        Files.write(metadata.toPath(), original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(repository.list().isEmpty());
        Files.write(metadata.toPath(), original.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, repository.list().size());

        File pdf = skin.pdf(DeltaSkin.Kind.STANDARD);
        byte[] bytes = Files.readAllBytes(pdf.toPath());
        assertTrue(pdf.delete());
        assertTrue("A missing PDF hides the skin", repository.list().isEmpty());
        Files.write(pdf.toPath(), bytes);
        assertEquals(1, repository.list().size());

        File archive = new File(skin.directory, "skin.deltaskin");
        Files.write(archive.toPath(), new byte[]{1, 2, 3});
        assertTrue("An archive of another size hides it", repository.list().isEmpty());

        File stray = new File(root, "pas-une-cle");
        assertTrue(stray.mkdirs());
        assertTrue(repository.list().isEmpty());
    }

    @Test public void onlyAFolderStagedByTheArchiveInstalls() throws Exception {
        File root = temporary.newFolder("skins"), work = temporary.newFolder("work");
        DeltaSkinArchive.Prepared prepared = prepare(root, archive(work, "a.deltaskin", "Test"));
        File elsewhere = temporary.newFolder("ailleurs");
        DeltaSkinArchive.Prepared moved = new DeltaSkinArchive.Prepared(elsewhere, prepared.metadata, prepared.manifest);
        try {
            new DeltaSkinRepository(root).install(moved, false);
            fail("installed from outside the staging folder");
        } catch (DeltaSkinException error) {
            assertEquals(Code.STORAGE_ERROR, error.code);
        }
    }

    private static File archive(File work, String file, String name) throws Exception {
        return DeltaSkinFixtures.zip(work, file, DeltaSkinFixtures.skin(DeltaSkinFixtures.manifest(name)));
    }

    private static DeltaSkinArchive.Prepared prepare(File root, File archive) throws Exception {
        return new DeltaSkinArchive(root, DeltaSkinFixtures.INSPECTOR).prepare(archive, archive.getName());
    }
}
