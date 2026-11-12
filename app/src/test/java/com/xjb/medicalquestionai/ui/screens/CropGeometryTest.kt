package com.xjb.medicalquestionai.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    @Test
    fun viewportZoomKeepsTheFingertipImagePointAndRoundTripsCoordinates() {
        val viewportSize = Size(400f, 300f)
        val focus = Offset(100f, 75f)
        val zoomed = CropViewport().zoomBy(2f, focus, viewportSize)

        assertEquals(2f, zoomed.zoom, 0.00001f)
        val before = CropViewport().screenToImage(focus, viewportSize)
        val after = zoomed.screenToImage(focus, viewportSize)
        assertEquals(before.x, after.x, 0.00001f)
        assertEquals(before.y, after.y, 0.00001f)
        val roundTrip = zoomed.screenToImage(zoomed.imageToScreen(before, viewportSize), viewportSize)
        assertEquals(before.x, roundTrip.x, 0.00001f)
        assertEquals(before.y, roundTrip.y, 0.00001f)
    }

    @Test
    fun viewportZoomIsBoundedAndPanCannotDragTheImageOutOfView() {
        val viewportSize = Size(400f, 300f)
        var zoomed = CropViewport()
        repeat(10) { zoomed = zoomed.zoomBy(2f, Offset(200f, 150f), viewportSize) }
        assertEquals(CropViewport.MAX_ZOOM, zoomed.zoom, 0.00001f)

        val clamped = zoomed.panBy(Offset(10_000f, -10_000f), viewportSize)
        val maxX = viewportSize.width * (zoomed.zoom - 1f) / 2f
        val maxY = viewportSize.height * (zoomed.zoom - 1f) / 2f
        assertEquals(maxX, clamped.offset.x, 0.00001f)
        assertEquals(-maxY, clamped.offset.y, 0.00001f)
        assertEquals(0f, clamped.screenToImage(Offset.Zero, viewportSize).x, 0.00001f)
        assertEquals(1f, clamped.screenToImage(Offset(viewportSize.width, viewportSize.height), viewportSize).y, 0.00001f)

        var zoomedOut = clamped
        repeat(10) { zoomedOut = zoomedOut.zoomBy(0.1f, Offset(200f, 150f), viewportSize) }
        assertEquals(CropViewport.MIN_ZOOM, zoomedOut.zoom, 0.00001f)
        assertEquals(0f, zoomedOut.offset.x, 0.00001f)
        assertEquals(0f, zoomedOut.offset.y, 0.00001f)
    }

    @Test
    fun cornerDragAfterZoomAccumulatesUsingScreenToImageScale() {
        val viewportSize = Size(400f, 400f)
        val viewport = CropViewport(zoom = 2f)
        var rect = CropGeometry.defaultRect
        repeat(3) {
            rect = CropGeometry.dragOnScreen(
                rect,
                CropHandle.BOTTOM_RIGHT,
                screenDx = -40f,
                screenDy = -40f,
                viewport = viewport,
                viewportSize = viewportSize
            )
        }

        assertRect(Rect(0.04f, 0.04f, 0.81f, 0.81f), rect)
    }

    @Test
    fun hitTestingUsesScreenSizedCornersAtHighZoom() {
        val viewportSize = Size(400f, 400f)
        val viewport = CropViewport(zoom = 4f)
        val rect = Rect(0.4f, 0.4f, 0.48f, 0.48f)
        val threshold = 22f / (viewportSize.width * viewport.zoom)
        val corner = viewport.imageToScreen(rect.topLeft, viewportSize)
        val nearby = viewport.screenToImage(corner + Offset(18f, 18f), viewportSize)

        assertEquals(CropHandle.TOP_LEFT, CropGeometry.hitTest(rect, nearby, threshold, threshold))
    }

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.00001f)
        assertEquals(expected.top, actual.top, 0.00001f)
        assertEquals(expected.right, actual.right, 0.00001f)
        assertEquals(expected.bottom, actual.bottom, 0.00001f)
    }
}
