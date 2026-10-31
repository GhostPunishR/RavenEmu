package com.ravenemu.app.platform;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import com.ravenemu.app.bridge.NativeAchievements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The one User-Agent sent to RetroAchievements and its image server, after
 * the beta's RetroUserAgent.kt: product and numeric version, system, engine
 * and achievement SDK, such as
 * "RavenEmu/0.1 (Android 15; arm64-v8a) RavenCore/0.14.0 rcheevos/12.5".
 * The versions come from the build itself, never from a copied constant. */
public final class RetroUserAgent {
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){1,3}");
    private static volatile String cached;

    private RetroUserAgent() {}

    public static String of(Context context) {
        String known = cached;
        if (known != null) return known;
        String versionName = "";
        try {
            String name = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (name != null) versionName = name;
        } catch (PackageManager.NameNotFoundException absent) {
            versionName = "";
        }
        String engine = "", sdk = "";
        try {
            engine = NativeAchievements.engineVersion();
            sdk = NativeAchievements.sdkVersion();
        } catch (LinkageError withoutNativeLibrary) {
            // Unit tests without the library: the engine parts are left out.
        }
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        return cached = build(versionName, Build.VERSION.RELEASE, abi, engine, sdk);
    }

    /** "0.1-dev" gives "RavenEmu/0.1": the service reads a numeric version. */
    public static String build(String versionName, String androidRelease, String abi, String engine, String sdk) {
        Matcher number = VERSION.matcher(versionName == null ? "" : versionName);
        String version = number.find() ? number.group() : "0.0.0";
        List<String> system = new ArrayList<>();
        String android = ("Android " + headerSafe(androidRelease)).trim();
        system.add(android);
        if (!headerSafe(abi).isEmpty()) system.add(headerSafe(abi));
        StringBuilder agent = new StringBuilder("RavenEmu/").append(version)
                .append(" (").append(String.join("; ", system)).append(')');
        if (!headerSafe(engine).isEmpty()) agent.append(" RavenCore/").append(headerSafe(engine));
        if (!headerSafe(sdk).isEmpty()) agent.append(' ').append(headerSafe(sdk));
        return agent.toString();
    }

    /** Printable ASCII only, single spaces, no parenthesis. */
    private static String headerSafe(String text) {
        if (text == null) return "";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            safe.append(c >= 0x21 && c <= 0x7e && c != '(' && c != ')' ? c : ' ');
        }
        return safe.toString().trim().replaceAll(" +", " ");
    }
}
