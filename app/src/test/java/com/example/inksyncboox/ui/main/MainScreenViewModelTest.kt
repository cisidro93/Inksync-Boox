package com.example.inksyncboox.ui.main

import com.example.inksyncboox.data.DocKind
import com.example.inksyncboox.data.InMemoryReaderStore
import com.example.inksyncboox.data.ReaderSettings
import com.example.inksyncboox.data.RecentDocument
import com.example.inksyncboox.data.documentKey
import com.example.inksyncboox.epub.EpubAnchor
import com.example.inksyncboox.epub.EpubBookDocument
import com.example.inksyncboox.epub.EpubChapter
import com.example.inksyncboox.epub.EpubParagraph
import com.example.inksyncboox.study.BooxWorkspaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: InMemoryReaderStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = InMemoryReaderStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = MainScreenViewModel(store, dispatcher, clock = { 1_000L })

    private fun book(chapters: Int) = EpubBookDocument(
        id = "book",
        title = "Book",
        author = "Author",
        chapters = (0 until chapters).map { c ->
            EpubChapter(
                index = c,
                title = "Chapter ${c + 1}",
                estimatedMinutes = 1,
                paragraphs = (0 until 10).map { p ->
                    EpubParagraph("ch${c + 1}_p${p + 1}", c, p, "Paragraph $p")
                }
            )
        }
    )

    @Test
    fun `starts in the library with nothing open`() {
        val state = viewModel().readerState.value
        assertEquals(BooxWorkspaceMode.LIBRARY, state.workspaceMode)
        assertEquals(null, state.document)
    }

    @Test
    fun `reopening a pdf resumes on the saved page and saves progress`() = runTest(dispatcher) {
        store.recents = listOf(RecentDocument("content://doc", "Doc.pdf", DocKind.PDF, 0L, pageIndex = 4, pageCount = 10))
        val vm = viewModel()
        vm.onFixedDocumentOpened("content://doc", "Doc.pdf", DocKind.PDF, 10)
        assertEquals(4, vm.readerState.value.pageIndex)
        assertEquals(BooxWorkspaceMode.PDF_NEOFLOW, vm.readerState.value.workspaceMode)

        vm.stepForwardTierOrPage()
        advanceUntilIdle()
        assertEquals(5, vm.readerState.value.pageIndex)
        assertEquals(5, store.recents.first().pageIndex)
    }

    @Test
    fun `full refresh happens every N page turns`() = runTest(dispatcher) {
        store.settings = ReaderSettings(fullRefreshEveryNTurns = 3)
        val vm = viewModel()
        vm.onFixedDocumentOpened("content://doc", "Doc.pdf", DocKind.PDF, 20)
        val start = vm.readerState.value.regalFlashTick
        repeat(2) { vm.stepForwardTierOrPage() }
        assertEquals(start, vm.readerState.value.regalFlashTick)
        vm.stepForwardTierOrPage()
        assertEquals(start + 1, vm.readerState.value.regalFlashTick)
    }

    @Test
    fun `manga mode mirrors panel order`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.toggleRightToLeft()
        assertTrue(vm.readerState.value.settings.rightToLeft)
        assertEquals(com.example.inksyncboox.neoflow.BooxTraversalFlow.REVERSE_Z, vm.readerState.value.traversalFlow)
    }

    @Test
    fun `epub page turns cross chapter boundaries in both directions`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEpubOpened("content://book", book(chapters = 2))
        vm.onEpubPagesComputed(0, listOf(EpubAnchor(0, 0), EpubAnchor(5, 0)))

        vm.stepForwardTierOrPage()
        assertEquals(EpubAnchor(5, 0), vm.readerState.value.epubAnchor)

        vm.stepForwardTierOrPage()
        assertEquals(1, vm.readerState.value.epubChapterIndex)
        assertEquals(EpubAnchor.START, vm.readerState.value.epubAnchor)

        vm.onEpubPagesComputed(1, listOf(EpubAnchor(0, 0), EpubAnchor(4, 0)))
        vm.stepBackwardTierOrPage()
        assertEquals(0, vm.readerState.value.epubChapterIndex)
        assertEquals(EpubAnchor.END, vm.readerState.value.epubAnchor)

        // Once chapter 1 is laid out again, END resolves to its last page.
        vm.onEpubPagesComputed(0, listOf(EpubAnchor(0, 0), EpubAnchor(5, 0)))
        assertEquals(EpubAnchor(5, 0), vm.readerState.value.epubAnchor)
    }

    @Test
    fun `highlights are saved per document`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onEpubOpened("content://book", book(chapters = 1))
        vm.toggleEpubParagraphHighlight("ch1_p2", "Paragraph 1", 0, 1)
        advanceUntilIdle()

        val saved = store.annotations[documentKey("content://book")]!!
        assertTrue("ch1_p2" in saved.epubHighlights)
        assertEquals(1, saved.studyCards.size)

        // A different document starts with no highlights.
        vm.onFixedDocumentOpened("content://other", "Other.pdf", DocKind.PDF, 3)
        assertTrue(vm.readerState.value.epubHighlightedCfis.isEmpty())
        assertTrue(vm.readerState.value.studyCards.isEmpty())
    }
}
