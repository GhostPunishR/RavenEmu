package com.ravenemu.emulation.cheats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GameSharkGbParserTest {

    @Test
    fun `decode un code RAM GameShark valide`() {
        val result = assertIs<GameSharkGbParser.DecodeResult.Success>(
            GameSharkGbParser.decode("010238CD")
        )
        assertEquals(0x01, result.write.externalRamBank)
        assertEquals(0x02, result.write.value)
        assertEquals(0xCD38, result.write.address)
        assertEquals("010238CD", result.write.normalized)
    }

    @Test
    fun `normalise minuscules espaces et tirets`() {
        val result = assertIs<GameSharkGbParser.DecodeResult.Success>(
            GameSharkGbParser.decode("01-7f 00-c0")
        )
        assertEquals("017F00C0", result.write.normalized)
        assertEquals(0xC000, result.write.address)
    }

    @Test
    fun `refuse une longueur incorrecte`() {
        assertFailure("010238C", CheatParseError.WRONG_LENGTH)
        assertFailure("010238CDD", CheatParseError.WRONG_LENGTH)
    }

    @Test
    fun `refuse un caractere non hexadecimal`() {
        assertFailure("010G38CD", CheatParseError.NON_HEXADECIMAL_CHARACTER)
    }

    @Test
    fun `refuse une variante de commande ou banque inconnue`() {
        assertFailure("910238CD", CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
    }

    @Test
    fun `refuse une adresse hors de la RAM GameShark`() {
        assertFailure("01020080", CheatParseError.ADDRESS_OUT_OF_RANGE)
        assertFailure("010200E0", CheatParseError.ADDRESS_OUT_OF_RANGE)
    }

    @Test
    fun `un cheat accepte plusieurs lignes`() {
        val result = assertIs<CheatCodeListParseResult.Success>(
            CheatParserRegistry.DEFAULT.parseLines(
                CheatFormat.GAMESHARK_GB_GBC,
                "010100C0\n\n01-02-01-C0",
            )
        )
        assertEquals(listOf("010100C0", "010201C0"), result.codes.map { it.normalized })
    }

    @Test
    fun `une erreur multiligne indique la ligne fautive`() {
        val result = assertIs<CheatCodeListParseResult.Failure>(
            CheatParserRegistry.DEFAULT.parseLines(
                CheatFormat.GAMESHARK_GB_GBC,
                "010100C0\n01020XC0",
            )
        )
        assertEquals(2, result.failure.lineNumber)
        assertEquals(CheatParseError.NON_HEXADECIMAL_CHARACTER, result.failure.error)
    }

    private fun assertFailure(raw: String, expected: CheatParseError) {
        val result = assertIs<GameSharkGbParser.DecodeResult.Failure>(
            GameSharkGbParser.decode(raw)
        )
        assertEquals(expected, result.error)
    }
}
