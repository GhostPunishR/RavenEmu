package com.ravenemu.emulation.cheats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GameSharkGbaV1V2ParserTest {
    @Test
    fun `le vecteur GameShark Advance publie est dechiffre`() {
        val decoded = success("CD93194F 089CE0B4")
        assertEquals(GameSharkGbaEncoding.ENCRYPTED, decoded.encoding)
        assertEquals(0x0300_1C88L, decoded.words.left)
        assertEquals(0x2FL, decoded.words.right)
        assertEquals(GameSharkGbaCommandType.WRITE_8, decoded.command)
        assertEquals("CD93194F 089CE0B4", decoded.normalized)

        assertEquals(
            GameSharkGbaWords(0x0300_6DF4L, 0x63L),
            GameSharkGbaV1V2Parser.decrypt(0x2AC2_A65DL, 0x67FE_ACC6L),
        )
    }

    @Test
    fun `les commandes RAW v1 v2 exigent un prefixe explicite`() {
        assertRawCommand("02000000 0000002A", GameSharkGbaCommandType.WRITE_8)
        assertRawCommand("12000002 0000BEEF", GameSharkGbaCommandType.WRITE_16)
        assertRawCommand("22000004 12345678", GameSharkGbaCommandType.WRITE_32)
        assertRawCommand("60000002 1000102A", GameSharkGbaCommandType.ROM_PATCH_16)

        // Sans préfixe, la même paire est un bloc chiffré et ne doit jamais
        // être devinée comme une commande RAW valide.
        assertIs<GameSharkGbaV1V2Parser.DecodeResult.Failure>(
            GameSharkGbaV1V2Parser.decodeLine("02000000 0000002A")
        )

        val hook = success("raw: f8000970 00000101")
        assertEquals(GameSharkGbaEncoding.RAW, hook.encoding)
        assertEquals(GameSharkGbaCommandType.MASTER_HOOK, hook.command)
        assertEquals("RAW F8000970 00000101", hook.normalized)
        assertRawCommand("45584D42 001DC0DE", GameSharkGbaCommandType.MASTER_GAME_ID)

        assertEquals(
            GameSharkGbaCommandType.MASTER_HOOK,
            success("0C4CADCA 7D5C4CF0").command,
        )
        assertEquals(
            GameSharkGbaCommandType.MASTER_GAME_ID,
            success("17B397CA E7919D45").command,
        )
    }

    @Test
    fun `casse espaces et programme multiligne sont normalises ensemble`() {
        val parsed = assertIs<CheatCodeListParseResult.Success>(
            GameSharkGbaV1V2Parser.parseLines(
                "  cd93 194f   089c e0b4  \n\traw 02000000  0000002a",
            )
        )
        assertEquals(1, parsed.codes.size)
        assertEquals(
            "CD93194F 089CE0B4\nRAW 02000000 0000002A",
            parsed.codes.single().normalized,
        )
    }

    @Test
    fun `les conditions une ligne et bloc exigent toutes leurs lignes`() {
        val next = assertIs<CheatCodeListParseResult.Success>(
            GameSharkGbaV1V2Parser.parseLines(
                "RAW D2000010 00001234\nRAW 02000000 0000002A",
            )
        )
        assertEquals(1, next.codes.size)

        val block = assertIs<CheatCodeListParseResult.Success>(
            GameSharkGbaV1V2Parser.parseLines(
                "RAW E0021234 02000010\n" +
                    "RAW 02000000 0000002A\n" +
                    "RAW 02000001 0000003B",
            )
        )
        assertEquals(1, block.codes.size)

        assertProgramFailure(
            "RAW D2000010 00001234",
            line = 1,
            CheatParseError.INCOMPLETE_COMMAND,
        )
        assertProgramFailure(
            "RAW E0021234 02000010\nRAW 02000000 0000002A",
            line = 1,
            CheatParseError.INCOMPLETE_COMMAND,
        )
        assertProgramFailure(
            "RAW D2000010 00001234\nRAW 60000002 1000102A",
            line = 1,
            CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
        )
    }

    @Test
    fun `longueur caracteres commandes adresses et valeurs invalides sont refuses`() {
        assertEquals(CheatParseError.EMPTY, failure(" \t"))
        assertEquals(CheatParseError.WRONG_LENGTH, failure("CD93194F 089CE0B"))
        assertEquals(CheatParseError.NON_HEXADECIMAL_CHARACTER, failure("CD93194G 089CE0B4"))
        assertEquals(
            CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            failure("RAW 40000000 00000000"),
        )
        assertEquals(
            CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            failure("RAW 30000002 12345678"),
        )
        assertEquals(
            CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            failure("RAW 8A100000 0000002A"),
        )
        assertEquals(CheatParseError.ADDRESS_OUT_OF_RANGE, failure("RAW 01000000 0000002A"))
        assertEquals(CheatParseError.ADDRESS_MISALIGNED, failure("RAW 12000001 00001234"))
        assertEquals(CheatParseError.VALUE_WIDTH_INVALID, failure("RAW 02000000 0000012A"))
        assertEquals(CheatParseError.ADDRESS_OUT_OF_RANGE, failure("RAW D8000000 00001234"))
    }

    @Test
    fun `DEADFACE est refuse comme Master Code non supporte`() {
        assertEquals(
            CheatParseError.UNSUPPORTED_MASTER_CODE,
            failure("RAW DEADFACE 00000000"),
        )
        assertEquals(
            CheatParseError.UNSUPPORTED_MASTER_CODE,
            failure("D0CD0E46 4AA27D60"),
        )
        assertEquals(
            CheatParseError.UNSUPPORTED_MASTER_CODE,
            failure("RAW F7000000 00000101"),
        )
    }

    @Test
    fun `les identifiants persistants restent stables`() {
        assertEquals(0, CheatFormat.GAMESHARK_GB_GBC.storageId)
        assertEquals(1, CheatFormat.GAMESHARK_GBA_V1_V2.storageId)
        assertEquals("GameShark GBA v1/v2", CheatFormat.GAMESHARK_GBA_V1_V2.displayName)
        assertEquals(CheatFormat.GAMESHARK_GB_GBC, CheatFormat.fromStorageId(0))
        assertEquals(CheatFormat.GAMESHARK_GBA_V1_V2, CheatFormat.fromStorageId(1))
    }

    private fun assertRawCommand(raw: String, expected: GameSharkGbaCommandType) {
        val decoded = success("RAW $raw")
        assertEquals(GameSharkGbaEncoding.RAW, decoded.encoding)
        assertEquals(expected, decoded.command)
    }

    private fun success(raw: String): GameSharkGbaDecodedLine =
        assertIs<GameSharkGbaV1V2Parser.DecodeResult.Success>(
            GameSharkGbaV1V2Parser.decodeLine(raw)
        ).line

    private fun failure(raw: String): CheatParseError =
        assertIs<GameSharkGbaV1V2Parser.DecodeResult.Failure>(
            GameSharkGbaV1V2Parser.decodeLine(raw)
        ).error

    private fun assertProgramFailure(raw: String, line: Int, error: CheatParseError) {
        val failure = assertIs<CheatCodeListParseResult.Failure>(
            GameSharkGbaV1V2Parser.parseLines(raw)
        )
        assertEquals(CheatLineFailure(line, error), failure.failure)
    }
}
