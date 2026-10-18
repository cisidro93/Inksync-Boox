package com.example.inksyncboox.picco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicInfoTest {

    @Test
    fun `reads series, number, writer and manga direction`() {
        val info = ComicInfo.parse(
            """<?xml version="1.0" encoding="utf-8"?>
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <Title>Romance Dawn</Title>
              <Series>One Piece</Series>
              <Number>1</Number>
              <Volume>1</Volume>
              <Writer>Eiichiro Oda</Writer>
              <Manga>YesAndRightToLeft</Manga>
            </ComicInfo>"""
        )!!
        assertTrue(info.rightToLeft)
        val meta = info.meta("file")
        assertEquals("One Piece Vol. 1 #1 – Romance Dawn", meta.title)
        assertEquals("Eiichiro Oda", meta.author)
        assertEquals("One Piece", meta.series)
        assertEquals("1", meta.seriesIndex)
    }

    @Test
    fun `plain Manga=Yes is not right to left, and entities are decoded`() {
        val info = ComicInfo.parse("<ComicInfo><Title>Tom &amp; Jerry &#x2014; 2</Title><Manga>Yes</Manga></ComicInfo>")!!
        assertFalse(info.rightToLeft)
        assertEquals("Tom & Jerry — 2", info.meta("f").title)
        assertEquals("", info.meta("f").author)
    }

    @Test
    fun `empty or unrelated xml gives null and the file name is used`() {
        assertNull(ComicInfo.parse(""))
        assertNull(ComicInfo.parse("<ComicInfo><PageCount>20</PageCount></ComicInfo>"))
        assertEquals("file", ComicInfo(volume = null).meta("file").title)
    }

    @Test
    fun `long writer lists are trimmed`() {
        val info = ComicInfo.parse("<ComicInfo><Writer>A, B, C, D</Writer><Volume>-1</Volume></ComicInfo>")!!
        assertEquals("A, B, C", info.writer)
        assertNull(info.volume)
    }
}
