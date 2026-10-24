package com.ravenemu.shared.settings

import kotlin.test.Test
import kotlin.test.assertContentEquals

class GbFrameBlenderTest {
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()
    private val red = 0xffff0000.toInt()

    @Test
    fun offLeavesEveryImageAlone() {
        val blender = GbFrameBlender()
        val frame = intArrayOf(white, red)
        blender.apply(frame, "off")
        assertContentEquals(intArrayOf(white, red), frame)
    }

    @Test
    fun mixAveragesEachImageWithTheOneBefore() {
        val blender = GbFrameBlender()
        // An object shown every other frame comes out half transparent.
        val first = intArrayOf(white, red)
        blender.apply(first, "mix")
        assertContentEquals(intArrayOf(white, red), first, "The first image has nothing to mix with")
        val second = intArrayOf(black, red)
        blender.apply(second, "mix")
        assertContentEquals(intArrayOf(0xff808080.toInt(), red), second)
        // Mixed with the raw image before, not the mixed one.
        val third = intArrayOf(white, black)
        blender.apply(third, "mix")
        assertContentEquals(intArrayOf(0xff808080.toInt(), 0xff800000.toInt()), third)
    }

    @Test
    fun ghostingKeepsThreeEighthsOfTheImageShownBefore() {
        val blender = GbFrameBlender()
        blender.apply(intArrayOf(white), "ghosting")
        val second = intArrayOf(black)
        blender.apply(second, "ghosting")
        // (0 * 5 + 255 * 3 + 4) / 8 = 96
        assertContentEquals(intArrayOf(0xff606060.toInt()), second)
        val third = intArrayOf(black)
        blender.apply(third, "ghosting")
        // A trail: 96 * 3 / 8, rounded.
        assertContentEquals(intArrayOf(0xff242424.toInt()), third)
    }

    @Test
    fun anotherModeOrSizeStartsOver() {
        val blender = GbFrameBlender()
        blender.apply(intArrayOf(white), "mix")
        val switched = intArrayOf(black)
        blender.apply(switched, "ghosting")
        assertContentEquals(intArrayOf(black), switched)
        val resized = intArrayOf(red, red)
        blender.apply(resized, "ghosting")
        assertContentEquals(intArrayOf(red, red), resized)
    }
}
