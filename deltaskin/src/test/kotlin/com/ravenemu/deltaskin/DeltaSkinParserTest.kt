package com.ravenemu.deltaskin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeltaSkinParserTest {
    @Test
    fun `manifeste GBC valide`() {
        val manifest = roundTrip(DeltaSkinTestFixtures.manifest(DeltaSkinConsole.GB_GBC))
        val validation = DeltaSkinValidator.validate(manifest)
        assertEquals(DeltaSkinConsole.GB_GBC, validation.console)
    }

    @Test
    fun `manifeste GBA valide avec L et R`() {
        val manifest = roundTrip(DeltaSkinTestFixtures.manifest(DeltaSkinConsole.GBA))
        val validation = DeltaSkinValidator.validate(manifest)
        assertEquals(DeltaSkinConsole.GBA, validation.console)
        val inputs = manifest.representations.iphone!!.standard!!.portrait!!.items
            .flatMap { (it.inputs as? DeltaSkinInputs.Buttons)?.values.orEmpty() }
        assertTrue("l" in inputs)
        assertTrue("r" in inputs)
    }

    @Test
    fun `standard et edgeToEdge portrait sont conserves`() {
        val iphone = roundTrip(DeltaSkinTestFixtures.manifest()).representations.iphone!!
        assertNotNull(iphone.standard?.portrait)
        assertNotNull(iphone.edgeToEdge?.portrait)
    }

    @Test
    fun `les proprietes inconnues sont ignorees`() {
        val encoded = DeltaSkinParser.encode(DeltaSkinTestFixtures.manifest())
        val withFutureProperties = encoded.dropLast(1) +
            ""","futureRoot":{"enabled":true},"otherDevice":{"portrait":{}}}"""
        val parsed = DeltaSkinParser.parse(withFutureProperties)
        assertEquals("Synthetic GBC", parsed.name)
    }

    @Test
    fun `le paysage est parse mais ne devient pas une representation portrait`() {
        val parsed = roundTrip(DeltaSkinTestFixtures.manifest(landscape = true))
        assertNotNull(parsed.representations.iphone?.standard?.landscape)
        assertEquals(
            setOf(
                DeltaSkinRepresentationKind.STANDARD,
                DeltaSkinRepresentationKind.EDGE_TO_EDGE,
            ),
            parsed.representations.iphone?.availablePortraitKinds(),
        )
    }

    @Test
    fun `item simple combinaison et D-pad gardent leur forme`() {
        val items = roundTrip(DeltaSkinTestFixtures.manifest())
            .representations.iphone!!.standard!!.portrait!!.items
        assertTrue(items.any { it.inputs == DeltaSkinInputs.Buttons(listOf("a")) })
        assertTrue(items.any { it.inputs == DeltaSkinInputs.Buttons(listOf("a", "b")) })
        assertTrue(items.first().inputs is DeltaSkinInputs.Directional)
    }

    @Test
    fun `extendedEdges partiel herite sans inventer les autres cotes`() {
        val representation = roundTrip(
            DeltaSkinTestFixtures.manifest(partialItemEdges = true)
        ).representations.iphone!!.standard!!.portrait!!
        val resolved = representation.items.first().extendedEdges
            .resolvedOver(representation.extendedEdges)
        assertEquals(12.0, resolved.left)
        assertEquals(5.0, resolved.top)
        assertEquals(6.0, resolved.bottom)
        assertEquals(8.0, resolved.right)
    }

    @Test
    fun `type de console inconnu est refuse`() {
        val manifest = DeltaSkinTestFixtures.manifest().copy(
            gameTypeIdentifier = "com.rileytestut.delta.game.unknown"
        )
        val error = assertFailsWith<DeltaSkinException> {
            DeltaSkinValidator.validate(manifest)
        }
        assertEquals(DeltaSkinErrorCode.UNSUPPORTED_CONSOLE, error.code)
    }

    @Test
    fun `mappingSize nul negatif et excessif sont refuses`() {
        for (
            size in listOf(
                0.0,
                -1.0,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                DeltaSkinValidator.MAX_LOGICAL_VALUE + 1.0,
            )
        ) {
            val manifest = withStandardRepresentation { representation ->
                representation.copy(mappingSize = DeltaSkinSize(size, 240.0))
            }
            val error = assertFailsWith<DeltaSkinException> {
                DeltaSkinValidator.validate(manifest)
            }
            assertEquals(DeltaSkinErrorCode.INVALID_MAPPING_SIZE, error.code)
        }
    }

    @Test
    fun `frame negative ou hors mapping est refusee`() {
        val manifest = withStandardRepresentation { representation ->
            representation.copy(
                items = representation.items.toMutableList().also { items ->
                    items[0] = items[0].copy(
                        frame = DeltaSkinFrame(-1.0, 0.0, 400.0, 20.0)
                    )
                }
            )
        }
        val error = assertFailsWith<DeltaSkinException> {
            DeltaSkinValidator.validate(manifest)
        }
        assertEquals(DeltaSkinErrorCode.INVALID_FRAME, error.code)
    }

    @Test
    fun `JSON malforme est refuse`() {
        val error = assertFailsWith<DeltaSkinException> {
            DeltaSkinParser.parse("""{"name":""")
        }
        assertEquals(DeltaSkinErrorCode.MALFORMED_JSON, error.code)
    }

    @Test
    fun `propriete obligatoire absente est refusee`() {
        val encoded = DeltaSkinParser.encode(DeltaSkinTestFixtures.manifest())
        val withoutName = encoded.replaceFirst(Regex("""\s*"name"\s*:\s*"[^"]*",?"""), "")
        val error = assertFailsWith<DeltaSkinException> {
            DeltaSkinParser.parse(withoutName)
        }
        assertEquals(DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY, error.code)
    }

    @Test
    fun `entree inconnue est signalee puis ignoree`() {
        val validation = DeltaSkinValidator.validate(
            DeltaSkinTestFixtures.manifest(ignoredInput = "quickSave")
        )
        assertEquals(setOf("quickSave"), validation.ignoredInputs)
    }

    private fun roundTrip(manifest: DeltaSkinManifest): DeltaSkinManifest =
        DeltaSkinParser.parse(DeltaSkinParser.encode(manifest))

    private fun withStandardRepresentation(
        change: (DeltaSkinRepresentation) -> DeltaSkinRepresentation,
    ): DeltaSkinManifest {
        val manifest = DeltaSkinTestFixtures.manifest(edgeToEdge = false)
        val iphone = manifest.representations.iphone!!
        val orientations = iphone.standard!!
        return manifest.copy(
            representations = manifest.representations.copy(
                iphone = iphone.copy(
                    standard = orientations.copy(
                        portrait = change(orientations.portrait!!)
                    )
                )
            )
        )
    }
}
