package com.example.inksyncboox.neoflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoFlowEngineTest {

    @Test
    fun `computeBlocks generates 4 ordered blocks for 2x2 Z_FLOW`() {
        val blocks = NeoFlowEngine.computeBlocks(
            preset = BooxGridPreset.GRID_2X2,
            flow = BooxTraversalFlow.Z_FLOW,
            colSplitRatios = listOf(0.5f),
            rowSplitRatios = listOf(0.5f)
        )
        assertEquals(4, blocks.size)
        // Step 0: Row 0, Col 0
        assertEquals(0, blocks[0].row)
        assertEquals(0, blocks[0].col)
        // Step 1: Row 0, Col 1
        assertEquals(0, blocks[1].row)
        assertEquals(1, blocks[1].col)
        // Step 2: Row 1, Col 0
        assertEquals(1, blocks[2].row)
        assertEquals(0, blocks[2].col)
        // Step 3: Row 1, Col 1
        assertEquals(1, blocks[3].row)
        assertEquals(1, blocks[3].col)
    }

    @Test
    fun `computeBlocks generates academic columnar order for 2x2 N_FLOW`() {
        val blocks = NeoFlowEngine.computeBlocks(
            preset = BooxGridPreset.GRID_2X2,
            flow = BooxTraversalFlow.N_FLOW,
            colSplitRatios = listOf(0.5f),
            rowSplitRatios = listOf(0.5f)
        )
        assertEquals(4, blocks.size)
        // Step 0: Col 0 Top (0,0)
        assertEquals(0, blocks[0].row)
        assertEquals(0, blocks[0].col)
        // Step 1: Col 0 Bottom (1,0)
        assertEquals(1, blocks[1].row)
        assertEquals(0, blocks[1].col)
        // Step 2: Col 1 Top (0,1)
        assertEquals(0, blocks[2].row)
        assertEquals(1, blocks[2].col)
        // Step 3: Col 1 Bottom (1,1)
        assertEquals(1, blocks[3].row)
        assertEquals(1, blocks[3].col)
    }

    @Test
    fun `connectionRedundancy expands block bounds within page margins`() {
        val blocks = NeoFlowEngine.computeBlocks(
            preset = BooxGridPreset.GRID_2X2,
            flow = BooxTraversalFlow.Z_FLOW,
            colSplitRatios = listOf(0.5f),
            rowSplitRatios = listOf(0.5f),
            overlapRatio = 0.10f
        )
        val first = blocks.first()
        assertTrue(first.redundantRect.right > first.baseRect.right)
        assertTrue(first.redundantRect.bottom > first.baseRect.bottom)
        assertTrue(first.redundantRect.left >= 0f)
        assertTrue(first.redundantRect.top >= 0f)
    }
}
