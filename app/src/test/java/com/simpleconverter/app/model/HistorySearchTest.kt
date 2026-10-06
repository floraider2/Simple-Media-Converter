package com.simpleconverter.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySearchTest {

    private fun matches(query: String, kind: MediaKind? = null) =
        matchesHistory("Urlaub Strand.mp4", "VID_20260806.mov", "video/mp4", query, kind)

    @Test
    fun `leere Suche findet alles`() = assertTrue(matches(""))

    @Test
    fun `Gross- und Kleinschreibung egal`() = assertTrue(matches("STRAND"))

    @Test
    fun `sucht auch im Namen der Quelldatei`() = assertTrue(matches("vid_2026"))

    @Test
    fun `mehrere Woerter muessen alle vorkommen`() {
        assertTrue(matches("urlaub mp4"))
        assertFalse(matches("urlaub berg"))
    }

    @Test
    fun `Filter nach Dateityp`() {
        assertTrue(matches("", MediaKind.VIDEO))
        assertFalse(matches("", MediaKind.AUDIO))
    }

    @Test
    fun `Medientyp aus MIME`() {
        assertEquals(MediaKind.VIDEO, kindOfMime("video/webm"))
        assertEquals(MediaKind.AUDIO, kindOfMime("audio/mpeg"))
        assertEquals(MediaKind.IMAGE, kindOfMime("image/jpeg"))
    }
}
