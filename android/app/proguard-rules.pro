# Règles R8 spécifiques RavenEmu. Les modules internes n'utilisent pas de
# réflexion ; kotlinx.serialization embarque ses propres règles consumer.

# Le pont utilise les noms JNI conventionnels et construit ces transports par
# leur nom binaire depuis C++ : R8 ne doit ni les renommer ni retirer leurs
# constructeurs/champs.
-keep class com.ravenemu.nativebridge.NativeCoreBridge { *; }
-keep class com.ravenemu.nativebridge.NativeBatterySnapshot { *; }
-keep class com.ravenemu.nativebridge.NativeGbaDebugSnapshot { *; }
-keep class com.ravenemu.nativebridge.NativeDiagnosticBatch { *; }

# Les erreurs natives sont matérialisées directement sous leurs types publics.
-keep class com.ravenemu.emulation.api.RomLoadException { *; }
-keep class com.ravenemu.emulation.api.SaveStateException { *; }
