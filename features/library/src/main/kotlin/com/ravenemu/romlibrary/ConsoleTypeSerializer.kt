package com.ravenemu.romlibrary

import com.ravenemu.emulation.api.ConsoleType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Lecture de la console d'une entrée d'index, tolérante aux noms retirés.
 *
 * L'index version 1 pouvait contenir `GAME_BOY_COLOR`, une seconde entrée pour
 * le même cœur Game Boy. Le nom a disparu de `ConsoleType` ; sans ce
 * traducteur, une entrée le portant ferait échouer la lecture de **tout**
 * l'index, qui repartirait vide. Un nom retiré est donc ramené sur le cœur qui
 * l'a toujours servi. Un nom réellement inconnu reste une erreur : mieux vaut
 * refuser un index qu'on ne comprend pas que ranger des ROM au hasard.
 */
internal object ConsoleTypeSerializer : KSerializer<ConsoleType> {

    /** Noms disparus et cœur qui les remplace. */
    private val RETIRED_NAMES = mapOf("GAME_BOY_COLOR" to ConsoleType.GAME_BOY)

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.ravenemu.romlibrary.ConsoleType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ConsoleType) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): ConsoleType {
        val name = decoder.decodeString()
        return ConsoleType.entries.firstOrNull { it.name == name }
            ?: RETIRED_NAMES[name]
            ?: throw SerializationException("Console inconnue dans l'index : $name")
    }
}
