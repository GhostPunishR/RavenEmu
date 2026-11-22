package com.ravenemu.app.ui.settings;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import com.ravenemu.app.bridge.FakeAchievements;
import com.ravenemu.app.data.library.RomLibrary;
import com.ravenemu.app.data.saves.CloudManifest;
import com.ravenemu.app.data.saves.CloudSaves;
import com.ravenemu.app.data.saves.CloudSync;
import com.ravenemu.app.data.saves.CloudSyncState;
import com.ravenemu.app.data.saves.SaveRepository;
import com.ravenemu.app.platform.ClearSealer;
import com.ravenemu.app.platform.FakeWebDav;
import com.ravenemu.app.settings.CloudAccount;
import com.ravenemu.app.ui.library.CloudSyncRunner;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

/** Settings → Sauvegardes cloud with the WebDAV folder in memory: signing in,
 * the first sync, a conflict settled and signing out. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w320dp-h640dp-mdpi",
        shadows = {FakeAchievements.class, ClearSealer.class, FakeWebDav.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class CloudSettingsPageTest {
    private static final String FOLDER = "https://cloud.example.org/remote.php/dav/files/me/RavenEmu/";
    private final List<ActivityController<PlaybackSettingsActivity>> activities = new ArrayList<>();
    private final Context context = RuntimeEnvironment.getApplication();
    private File files;

    @Before public void prepare() throws Exception {
        files = context.getFilesDir();
        FakeWebDav.reset();
        FakeAchievements.reset();
        CloudAccount.clear(context);
        CloudSyncState.clear(files);
        deleteTree(new File(files, "native-saves"));
        deleteTree(new File(files, "cloud"));
        deleteTree(new File(files, "native-library"));
    }

    @After public void close() throws Exception {
        for (ActivityController<PlaybackSettingsActivity> controller : activities) controller.pause().stop().destroy();
        settle(null);
        CloudAccount.clear(context);
    }

    @Test public void signingInChecksTheFolderThenSyncsTheLibrarysSaves() throws Exception {
        RomLibrary.Entry game = RomLibrary.importRom(files, "Alpha.gb", new byte[0x180]);
        SaveRepository.write(files, game.key, "srm", "progress".getBytes(StandardCharsets.UTF_8));
        PlaybackSettingsActivity activity = open();
        assertNotNull(text(activity, "Sauvegardes cloud"));
        signIn(activity, FOLDER, "me", "app-pass");
        CloudAccount stored = CloudAccount.load(context);
        assertNotNull(stored);
        assertEquals(FOLDER, stored.folder);
        assertTrue(stored.automatic);
        CloudManifest list = CloudManifest.parse(FakeWebDav.folder.files.get(CloudManifest.FILE));
        CloudManifest.Entry entry = list.get(game.key);
        assertNotNull("The first sync sent the save", entry);
        assertArrayEquals("progress".getBytes(StandardCharsets.UTF_8),
                FakeWebDav.folder.files.get(CloudManifest.fileName(game.key, entry.sha256)));
        assertNotNull(text(activity, "cloud.example.org"));
        assertNotNull(find(activity, view -> view instanceof TextView
                && ((TextView) view).getText().toString().contains("1 sauvegarde envoyée")));
        // Nothing but cartridge saves reaches the folder.
        for (String name : FakeWebDav.folder.files.keySet())
            assertTrue(name, name.equals(CloudManifest.FILE) || name.endsWith(".srm"));
    }

    @Test public void anAddressThatIsNotHttpsOrAFolderThatDoesNotExistStoresNothing() throws Exception {
        PlaybackSettingsActivity activity = open();
        signIn(activity, "http://cloud.example.org/dav/", "me", "app-pass");
        assertNotNull(find(activity, view -> "cloud.error".equals(view.getTag())
                && ((TextView) view).getText().toString().startsWith("Adresse invalide")));
        assertFalse(CloudAccount.stored(context));
        assertTrue(FakeWebDav.folder.log.isEmpty());

        FakeWebDav.folder.missingFolder = true;
        signIn(activity, FOLDER, "me", "app-pass");
        assertEquals("Dossier introuvable : créez-le d'abord dans votre cloud",
                ((TextView) find(activity, view -> "cloud.error".equals(view.getTag()))).getText().toString());
        assertFalse(CloudAccount.stored(context));
        EditText password = field(activity, "Mot de passe d'application");
        assertEquals("The password is never kept in the form", "", password.getText().toString());
        assertEquals(FOLDER, field(activity, "Adresse WebDAV du dossier (https://…)").getText().toString());
    }

    @Test public void aConflictWaitsForTheChoiceThenTheCloudsSaveIsTaken() throws Exception {
        RomLibrary.Entry game = RomLibrary.importRom(files, "Alpha.gb", new byte[0x180]);
        SaveRepository.write(files, game.key, "srm", "this phone".getBytes(StandardCharsets.UTF_8));
        // Another device sent its own save first.
        byte[] theirs = "other phone".getBytes(StandardCharsets.UTF_8);
        CloudManifest list = CloudManifest.empty();
        list.put(game.key, CloudSync.sha256(theirs), theirs.length, 1_700_000_000_000L, "Galaxy Tab");
        FakeWebDav.folder.files.put(CloudManifest.fileName(game.key, CloudSync.sha256(theirs)), theirs);
        FakeWebDav.folder.replaceList(list);

        PlaybackSettingsActivity activity = open();
        signIn(activity, FOLDER, "me", "app-pass");
        assertNotNull(text(activity, "Conflits à régler"));
        assertNotNull(find(activity, view -> view instanceof TextView
                && ((TextView) view).getText().toString().contains("Cloud : Galaxy Tab")));
        assertArrayEquals("this phone".getBytes(StandardCharsets.UTF_8), SaveRepository.read(files, game.key, "srm"));

        described(activity, "Prendre la sauvegarde du cloud pour Alpha").performClick();
        settle(activity);
        assertArrayEquals(theirs, SaveRepository.read(files, game.key, "srm"));
        assertEquals(1, new CloudSaves(files).backups(game.key).length);
        assertNull("Settled", find(activity, view -> view instanceof TextView
                && "Conflits à régler".contentEquals(((TextView) view).getText())));
    }

    @Test public void signingOutForgetsTheAccountAndKeepsEverySave() throws Exception {
        RomLibrary.Entry game = RomLibrary.importRom(files, "Alpha.gb", new byte[0x180]);
        SaveRepository.write(files, game.key, "srm", "kept".getBytes(StandardCharsets.UTF_8));
        PlaybackSettingsActivity activity = open();
        signIn(activity, FOLDER, "me", "app-pass");
        Switch automatic = (Switch) find(activity, view -> view instanceof Switch
                && view.getContentDescription().toString().startsWith("Synchroniser automatiquement."));
        automatic.performClick();
        settle(activity);
        assertFalse(CloudAccount.load(context).automatic);

        described(activity, "Se déconnecter du cloud").performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(activity);
        assertFalse(CloudAccount.stored(context));
        assertArrayEquals("kept".getBytes(StandardCharsets.UTF_8), SaveRepository.read(files, game.key, "srm"));
        assertTrue("The cloud keeps its copy", FakeWebDav.folder.files.size() > 1);
        assertNotNull(field(activity, "Adresse WebDAV du dossier (https://…)"));
    }

    private PlaybackSettingsActivity open() throws Exception {
        ActivityController<PlaybackSettingsActivity> controller = Robolectric.buildActivity(
                PlaybackSettingsActivity.class, new Intent(context, PlaybackSettingsActivity.class)
                        .putExtra(PlaybackSettingsActivity.EXTRA_PAGE, PlaybackSettingsActivity.CLOUD)).setup();
        activities.add(controller);
        settle(controller.get());
        return controller.get();
    }

    private void signIn(PlaybackSettingsActivity activity, String folder, String user, String password)
            throws Exception {
        field(activity, "Adresse WebDAV du dossier (https://…)").setText(folder);
        field(activity, "Identifiant").setText(user);
        field(activity, "Mot de passe d'application").setText(password);
        Button connect = (Button) find(activity, view -> view instanceof Button
                && "Se connecter".contentEquals(((Button) view).getText()));
        assertTrue(connect.isEnabled());
        connect.performClick();
        settle(activity);
    }

    /** The settings' executors, the sync's worker and the UI thread, until all are idle. */
    private static void settle(PlaybackSettingsActivity activity) throws Exception {
        ExecutorService worker = ReflectionHelpers.getStaticField(CloudSyncRunner.class, "WORKER");
        for (int round = 0; round < 6; round++) {
            if (activity != null)
                for (String name : new String[]{"io", "network"}) {
                    ExecutorService service = ReflectionHelpers.getField(activity, name);
                    if (!service.isShutdown()) service.submit(() -> {}).get(10, TimeUnit.SECONDS);
                }
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            com.ravenemu.app.data.saves.SaveStorage.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
            shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private static EditText field(PlaybackSettingsActivity activity, String hint) {
        EditText view = (EditText) find(activity, candidate -> candidate instanceof EditText
                && hint.contentEquals(((EditText) candidate).getHint()));
        assertNotNull(hint, view);
        return view;
    }

    private static View described(PlaybackSettingsActivity activity, String description) {
        View view = find(activity, candidate -> description.contentEquals(
                candidate.getContentDescription() == null ? "" : candidate.getContentDescription()));
        assertNotNull(description, view);
        return view;
    }

    private static View text(PlaybackSettingsActivity activity, String value) {
        View view = find(activity, candidate -> candidate instanceof TextView
                && value.contentEquals(((TextView) candidate).getText()));
        assertNotNull(value, view);
        return view;
    }

    private static View find(PlaybackSettingsActivity activity, Predicate<View> match) {
        return find(activity.getWindow().getDecorView(), match);
    }

    private static View find(View root, Predicate<View> match) {
        if (match.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
