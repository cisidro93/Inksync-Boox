package com.example.inksyncboox.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubPagingTest {

    private val starts = listOf(EpubAnchor(0, 0), EpubAnchor(3, 120), EpubAnchor(7, 0))

    @Test
    fun `pageIndexFor finds the page containing an anchor`() {
        assertEquals(0, EpubPaging.pageIndexFor(starts, EpubAnchor(0, 0)))
        assertEquals(0, EpubPaging.pageIndexFor(starts, EpubAnchor(3, 119)))
        assertEquals(1, EpubPaging.pageIndexFor(starts, EpubAnchor(3, 120)))
        assertEquals(1, EpubPaging.pageIndexFor(starts, EpubAnchor(6, 999)))
        assertEquals(2, EpubPaging.pageIndexFor(starts, EpubAnchor.END))
    }

    @Test
    fun `next moves within the chapter, then to the next chapter`() {
        assertEquals(
            EpubPaging.Step.SamePage(EpubAnchor(3, 120)),
            EpubPaging.next(starts, EpubAnchor(0, 0), chapter = 0, chapterCount = 2)
        )
        assertEquals(
            EpubPaging.Step.OtherChapter(1, EpubAnchor.START),
            EpubPaging.next(starts, EpubAnchor(7, 0), chapter = 0, chapterCount = 2)
        )
        assertEquals(
            EpubPaging.Step.None,
            EpubPaging.next(starts, EpubAnchor(7, 0), chapter = 1, chapterCount = 2)
        )
    }

    @Test
    fun `previous from the first page goes to the end of the previous chapter`() {
        assertEquals(
            EpubPaging.Step.SamePage(EpubAnchor(0, 0)),
            EpubPaging.previous(starts, EpubAnchor(3, 120), chapter = 1)
        )
        assertEquals(
            EpubPaging.Step.OtherChapter(0, EpubAnchor.END),
            EpubPaging.previous(starts, EpubAnchor(0, 0), chapter = 1)
        )
        assertEquals(EpubPaging.Step.None, EpubPaging.previous(starts, EpubAnchor(0, 0), chapter = 0))
    }

    @Test
    fun `no pages means no movement`() {
        assertEquals(EpubPaging.Step.None, EpubPaging.next(emptyList(), EpubAnchor.START, 0, 3))
        assertEquals(EpubPaging.Step.None, EpubPaging.previous(emptyList(), EpubAnchor.START, 1))
    }
}
