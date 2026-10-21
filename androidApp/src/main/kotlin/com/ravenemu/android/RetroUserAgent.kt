package com.ravenemu.android

import android.content.Context
import android.os.Build

/**
 * The one User-Agent sent to RetroAchievements and its image server: product
 * and numeric version, system, emulation engine and achievement SDK, e.g.
 * "RavenEmu/0.2.0 (Android 14; arm64-v8a) RavenCore/0.8.0 rcheevos/12.5".
 * The versions come from the build itself, never from a copied constant.
 */
internal object RetroUserAgent {
    @Volatile private var cached: String? = null

    fun of(context: Context): String = cached ?: build(
        versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        // Without the native library (unit tests), the engine parts are left out.
        engine = runCatching { NativeAchievementsBridge.engineVersion() }.getOrDefault(""),
        sdk = runCatching { NativeAchievementsBridge.sdkVersion() }.getOrDefault(""),
    ).also { cached = it }

    /** "0.1.0 - Bêta" gives "RavenEmu/0.1.0": the service reads a numeric version. */
    fun build(versionName: String, androidRelease: String, abi: String, engine: String, sdk: String): String {
        val version = Regex("""\d+(\.\d+){1,3}""").find(versionName)?.value ?: "0.0.0"
        val system = listOf("Android ${headerSafe(androidRelease)}".trim(), headerSafe(abi))
            .filter { it.isNotEmpty() }.joinToString("; ")
        return listOf(
            "RavenEmu/$version ($system)",
            headerSafe(engine).takeIf { it.isNotEmpty() }?.let { "RavenCore/$it" },
            headerSafe(sdk).takeIf { it.isNotEmpty() },
        ).filterNotNull().joinToString(" ")
    }

    // Printable ASCII only, and no parenthesis inside the system part.
    private fun headerSafe(text: String): String =
        text.map { if (it.code in 0x21..0x7e && it != '(' && it != ')') it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
