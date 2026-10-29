package com.ravenemu.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.text.Selection;
import android.text.Spannable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Legal text is shipped locally and remains accessible without JNI/network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "fr-rFR-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class LicensesActivityTest {
    private final List<ActivityController<LicensesActivity>> activities = new ArrayList<>();

    @After public void close() {
        for (ActivityController<LicensesActivity> activity : activities)
            activity.pause().stop().destroy();
    }

    @Test public void creditsDescribeTheActualPortsAndSelectableSourceNotAnOfficialFrontend() throws Exception {
        LicensesActivity activity = open(null).get();
        String notices = body(activity).getText().toString();
        assertTrue(notices.contains("Portions adaptées des pilotes AAudio et OpenGL2/GLES2 de RetroArch"));
        assertTrue(notices.contains("7986022baf6822677ad2cec4bc215069e89bd8ce"));
        assertTrue(notices.contains("RavenCore reste le moteur"));
        assertTrue(notices.contains("n’est pas le frontend officiel de RetroArch"));
        assertTrue(notices.contains("Material Components for Android 1.14.0"));
        assertTrue(body(activity).isTextSelectable());
        assertEquals(BuildConfig.SOURCE_MODIFIED,
                textExists(activity, "Version locale modifiée · base " + BuildConfig.SOURCE_REVISION.substring(0, 7)));
    }

    @Test public void allFourLicenseTextsArePresentLocally() throws Exception {
        LicensesActivity activity = open(null).get();
        choose(activity, "GPLv3");
        assertTrue(body(activity).getText().toString().contains("GNU GENERAL PUBLIC LICENSE"));
        assertTrue(body(activity).getText().toString().contains("Version 3, 29 June 2007"));
        choose(activity, "AGPLv3");
        assertTrue(body(activity).getText().toString().contains("GNU AFFERO GENERAL PUBLIC LICENSE"));
        choose(activity, "MIT");
        assertTrue(body(activity).getText().toString().contains("Copyright (C) 2010-2024 The RetroArch team"));
        assertTrue(body(activity).getText().toString().contains("Permission is hereby granted"));
        choose(activity, "Apache 2");
        assertTrue(body(activity).getText().toString().contains("Apache License"));
        assertTrue(body(activity).getText().toString().contains("Version 2.0, January 2004"));
    }

    @Test public void sourcesPointToTheBuildRevisionAndBackFinishesOnlyThisScreen() throws Exception {
        LicensesActivity activity = open(null).get();
        described(activity, "Consulter le code source").performClick();
        Intent source = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, source.getAction());
        assertEquals("https://github.com/GhostPunishR/RavenEmu/tree/" + BuildConfig.SOURCE_REVISION,
                source.getDataString());
        described(activity, "Retour aux paramètres").performClick();
        assertTrue(activity.isFinishing());
    }

    @Test public void recreationKeepsTheSelectedDocumentAndTextSelection() throws Exception {
        ActivityController<LicensesActivity> first = open(null);
        choose(first.get(), "GPLv3");
        Selection.setSelection((Spannable)body(first.get()).getText(), 12, 28);
        Bundle saved = new Bundle();
        first.saveInstanceState(saved).pause().stop().destroy();
        activities.remove(first);
        LicensesActivity restored = open(saved).get();
        assertTrue(body(restored).getText().toString().contains("GNU GENERAL PUBLIC LICENSE"));
        assertEquals(12, body(restored).getSelectionStart());
        assertEquals(28, body(restored).getSelectionEnd());
    }

    private ActivityController<LicensesActivity> open(Bundle saved) throws Exception {
        ActivityController<LicensesActivity> controller =
                Robolectric.buildActivity(LicensesActivity.class)
                        .create(saved).start().resume().visible();
        activities.add(controller);
        idle(controller.get());
        return controller;
    }

    private void choose(LicensesActivity activity, String document) throws Exception {
        described(activity, "Afficher " + document).performClick();
        idle(activity);
    }

    private static void idle(LicensesActivity activity) throws Exception {
        ExecutorService io = ReflectionHelpers.getField(activity, "io");
        io.submit(() -> {}).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static TextView body(LicensesActivity activity) {
        return activity.findViewById(android.R.id.text1);
    }

    private static View described(LicensesActivity activity, String description) {
        View result = find(activity.getWindow().getDecorView(), view ->
                description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()));
        assertNotNull(description, result);
        return result;
    }

    private static boolean textExists(LicensesActivity activity, String value) {
        return find(activity.getWindow().getDecorView(), view ->
                view instanceof TextView && value.contentEquals(((TextView)view).getText())) != null;
    }

    private static View find(View root, java.util.function.Predicate<View> predicate) {
        if (predicate.test(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
}
