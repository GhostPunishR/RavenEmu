package com.ravenemu.app.ui.settings;

import com.ravenemu.app.settings.PlaybackSettings;
import com.ravenemu.app.ui.common.RavenTheme;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "en-rUS-w320dp-h568dp-mdpi")
public final class LegalDocumentActivityTest {
    private final List<ActivityController<LegalDocumentActivity>> activities = new ArrayList<>();

    @Before public void clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After public void close() {
        for (ActivityController<LegalDocumentActivity> controller : activities)
            controller.pause().stop().destroy();
    }

    @Test public void theApkCarriesTheRepositorysOwnDocuments() throws Exception {
        for (String[] pair : new String[][]{{"legal/TERMS.md", "TERMS.md"}, {"legal/PRIVACY.md", "PRIVACY.md"}}) {
            try (InputStream input = RuntimeEnvironment.getApplication().getAssets().open(pair[0])) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
                assertEquals("Copied at build time, not kept by hand: " + pair[1],
                        LegalMarkdownTest.read(pair[1]), bytes.toString(StandardCharsets.UTF_8.name()));
            }
        }
    }

    @Test public void theTermsReadInsideTheAppWithHeadingsBulletsAndLinks() throws Exception {
        LegalDocumentActivity activity = open(LegalDocumentActivity.TERMS);
        View root = activity.getWindow().getDecorView();
        assertNotNull(text(root, "Conditions d'utilisation"));
        assertNull("Loaded", text(root, "Chargement…"));
        assertNull(text(root, "Ce document est indisponible."));
        List<TextView> views = texts(root);
        TextView heading = null;
        for (TextView view : views)
            if (view.getTypeface() != null && view.getTypeface().isBold()
                    && view.getCurrentTextColor() == RavenTheme.RAVENEMU.accent) { heading = view; break; }
        assertNotNull("A heading in the accent", heading);
        assertNotNull("The beta's bullet mark", text(root, "•"));
        for (TextView view : views)
            assertFalse("No raw Markdown: " + view.getText(), view.getText().toString().contains("**"));
    }

    @Test public void privacyLinksWorkAndTakeTheTheme() throws Exception {
        RavenTheme.store(RuntimeEnvironment.getApplication(), RavenTheme.NVIDIA);
        LegalDocumentActivity activity = open(LegalDocumentActivity.PRIVACY);
        View root = activity.getWindow().getDecorView();
        assertNotNull(text(root, "Politique de confidentialité"));
        assertSame(RavenTheme.NVIDIA, activity.theme());
        TextView linked = null;
        for (TextView view : texts(root)) {
            CharSequence value = view.getText();
            if (value instanceof Spanned
                    && ((Spanned) value).getSpans(0, value.length(), ClickableSpan.class).length > 0) {
                linked = view;
                break;
            }
        }
        assertNotNull("PRIVACY.md links to its services", linked);
        assertTrue(linked.getMovementMethod() instanceof LinkMovementMethod);
        Spanned value = (Spanned) linked.getText();
        ClickableSpan link = value.getSpans(0, value.length(), ClickableSpan.class)[0];
        link.onClick(linked);
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertTrue(opened.getDataString().startsWith("http"));
    }

    @Test public void anUnknownDocumentSaysSo() throws Exception {
        LegalDocumentActivity activity = open("autre");
        assertNotNull(text(activity.getWindow().getDecorView(), "Ce document est indisponible."));
    }

    private LegalDocumentActivity open(String document) throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), LegalDocumentActivity.class)
                .putExtra(LegalDocumentActivity.EXTRA_DOCUMENT, document);
        ActivityController<LegalDocumentActivity> controller =
                Robolectric.buildActivity(LegalDocumentActivity.class, intent).create().start().resume().visible();
        activities.add(controller);
        ExecutorService io = ReflectionHelpers.getField(controller.get(), "io");
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }

    private static List<TextView> texts(View root) {
        List<TextView> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(View view, List<TextView> found) {
        if (view instanceof TextView) found.add((TextView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), found);
    }

    private static TextView text(View root, String value) {
        for (TextView view : texts(root)) if (value.contentEquals(view.getText())) return view;
        return null;
    }
}
