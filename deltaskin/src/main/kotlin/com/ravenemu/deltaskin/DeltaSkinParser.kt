package com.ravenemu.deltaskin

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object DeltaSkinParser {
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        allowSpecialFloatingPointValues = false
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }

    fun parse(source: String): DeltaSkinManifest {
        val normalized = source.removePrefix("\uFEFF")
        return try {
            json.decodeFromString(DeltaSkinManifest.serializer(), normalized)
        } catch (error: MissingFieldException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY,
                "Une propriété obligatoire du manifeste est absente",
                error,
            )
        } catch (error: SerializationException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.MALFORMED_JSON,
                "Le manifeste info.json est malformé",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.MALFORMED_JSON,
                "Le manifeste info.json contient une valeur invalide",
                error,
            )
        }
    }

    fun encode(manifest: DeltaSkinManifest): String = json.encodeToString(manifest)

    internal inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    internal inline fun <reified T> decode(source: String): T = json.decodeFromString(source)
}
