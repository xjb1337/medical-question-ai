package com.xjb.medicalquestionai.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    @Test
    fun bottomRightDragChangesTheRect() {
        val result = CropGeometry.drag(CropGeometry.defaultRect, CropHandle.BOTTOM_RIGHT, -0.2f, -0.3f)

        assertRect(Rect(0.04f, 0.04f, 0.76f, 0.66f), result)
    }

    @Test
    fun consecutiveDeltasAccumulateInsteadOfRestartingAtTheInitialRect() {
        var result = CropGeometry.defaultRect
        repeat(6) {
            result = CropGeometry.drag(result, CropHandle.BOTTOM_RIGHT, -0.04f, -0.03f)
        }

        assertRect(Rect(0.04f, 0.04f, 0.72f, 0.78f), result)
        result = CropGeometry.drag(result, CropHandle.BOTTOM_RIGHT, -0.1f, -0.1f)
        assertRect(Rect(0.04f, 0.04f, 0.62f, 0.68f), result)
    }

    @Test
    fun hitTestingUsesTheResizedRectAndPrioritizesCorners() {
        val rect = Rect(0.2f, 0.25f, 0.65f, 0.7f)

        assertEquals(CropHandle.TOP_LEFT, CropGeometry.hitTest(rect, rect.topLeft))
        assertEquals(CropHandle.TOP_RIGHT, CropGeometry.hitTest(rect, rect.topRight))
        assertEquals(CropHandle.BOTTOM_LEFT, CropGeometry.hitTest(rect, rect.bottomLeft))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropGeometry.hitTest(rect, rect.bottomRight))
        assertEquals(CropHandle.MOVE, CropGeometry.hitTest(rect, rect.center))
        assertEquals(CropHandle.NONE, CropGeometry.hitTest(rect, Offset(0.95f, 0.95f)))
    }

    @Test
    fun translationClampsToEveryBoundaryAndPreservesSize() {
        val rect = Rect(0.2f, 0.3f, 0.6f, 0.8f)
        val atOrigin = CropGeometry.drag(rect, CropHandle.MOVE, -5f, -5f)
        val atEnd = CropGeometry.drag(atOrigin, CropHandle.MOVE, 5f, 5f)

        assertRect(Rect(0f, 0f, 0.4f, 0.5f), atOrigin)
        assertRect(Rect(0.6f, 0.5f, 1f, 1f), atEnd)
        assertRect(Rect(0.5f, 0.4f, 0.9f, 0.9f), CropGeometry.drag(atEnd, CropHandle.MOVE, -0.1f, -0.1f))
    }

    @Test
    fun overlappingHitTargetsStillChooseTheNearestCornerAtMinimumSize() {
        val rect = Rect(0.4f, 0.4f, 0.48f, 0.48f)
        assertEquals(CropHandle.TOP_LEFT, CropGeometry.hitTest(rect, rect.topLeft))
        assertEquals(CropHandle.TOP_RIGHT, CropGeometry.hitTest(rect, rect.topRight))
        assertEquals(CropHandle.BOTTOM_LEFT, CropGeometry.hitTest(rect, rect.bottomLeft))
        assertEquals(CropHandle.BOTTOM_RIGHT, CropGeometry.hitTest(rect, rect.bottomRight))
    }

    @Test
    fun allCornersKeepMinimumSizeAndStayInsideImage() {
        val rect = Rect(0.2f, 0.2f, 0.8f, 0.8f)
        val corners = listOf(CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT)
        corners.forEach { handle ->
            val inwardX = if (handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT) 5f else -5f
            val inwardY = if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT) 5f else -5f
            val minimum = CropGeometry.drag(rect, handle, inwardX, inwardY)
            assertEquals(CropGeometry.MIN_SIZE, minimum.width, 0.00001f)
            assertEquals(CropGeometry.MIN_SIZE, minimum.height, 0.00001f)
            val expanded = CropGeometry.drag(minimum, handle, -inwardX, -inwardY)
            assertTrue(expanded.left >= 0f && expanded.top >= 0f)
            assertTrue(expanded.right <= 1f && expanded.bottom <= 1f)
        }
    }

    @Test
    fun noHandleLeavesRectUnchanged() {
        assertEquals(CropGeometry.defaultRect, CropGeometry.drag(CropGeometry.defaultRect, CropHandle.NONE, 1f, 1f))
    }

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.00001f)
        assertEquals(expected.top, actual.top, 0.00001f)
        assertEquals(expected.right, actual.right, 0.00001f)
        assertEquals(expected.bottom, actual.bottom, 0.00001f)
    }
}
