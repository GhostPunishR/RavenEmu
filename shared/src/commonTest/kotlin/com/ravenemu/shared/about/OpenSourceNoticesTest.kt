package com.ravenemu.shared.about

import kotlin.test.Test
import kotlin.test.assertTrue

/** The licence texts must travel with the app, complete. */
class OpenSourceNoticesTest {
    private fun notice(name: String) = OpenSourceNotices.all.single { it.name.startsWith(name) }

    @Test
    fun rcheevosShipsItsMitNoticeWithItsCopyright() {
        val text = notice("rcheevos").text
        assertTrue("Copyright (c) 2018 RetroAchievements.org" in text)
        assertTrue("The above copyright notice and this permission notice shall be included" in text)
        assertTrue(text.trimEnd().endsWith("SOFTWARE."))
    }

    @Test
    fun apacheLicenceIsTheFullText() {
        val text = notice("Kotlin").text
        assertTrue("Apache License" in text && "Version 2.0, January 2004" in text)
        assertTrue("END OF TERMS AND CONDITIONS" in text)
    }

    @Test
    fun everyNoticeLinksToItsProjectOverHttps() {
        for (notice in OpenSourceNotices.all) assertTrue(notice.url.startsWith("https://"), notice.name)
    }
}
