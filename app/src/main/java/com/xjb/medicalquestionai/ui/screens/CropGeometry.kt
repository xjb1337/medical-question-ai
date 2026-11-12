package com.xjb.medicalquestionai.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

internal enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Screen-only view transform for the crop image. Crop rectangles remain in
 * normalized image coordinates, so zooming never changes what will be saved.
 */
internal data class CropViewport(
    val zoom: Float = MIN_ZOOM,
    val offset: Offset = Offset.Zero
) {
    fun imageToScreen(position: Offset, viewportSize: Size): Offset {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f) return Offset.Zero
        return Offset(
            x = viewportSize.width / 2f +
                (position.x - 0.5f) * viewportSize.width * zoom +
                offset.x,
            y = viewportSize.height / 2f +
                (position.y - 0.5f) * viewportSize.height * zoom +
                offset.y
        )
    }

    fun screenToImage(position: Offset, viewportSize: Size): Offset {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f || zoom <= 0f) return Offset.Zero
        return Offset(
            x = 0.5f +
                (position.x - viewportSize.width / 2f - offset.x) / (viewportSize.width * zoom),
            y = 0.5f +
                (position.y - viewportSize.height / 2f - offset.y) / (viewportSize.height * zoom)
        )
    }

    fun panBy(delta: Offset, viewportSize: Size): CropViewport =
        copy(offset = offset + delta).clamped(viewportSize)

    /** Zooms around [focus] so the image point under the fingers stays put. */
    fun zoomBy(factor: Float, focus: Offset, viewportSize: Size): CropViewport {
        if (!factor.isFinite() || factor <= 0f ||
            viewportSize.width <= 0f || viewportSize.height <= 0f
        ) {
            return this
        }
        val targetZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (abs(targetZoom - zoom) < 0.00001f) return this
        val imagePoint = screenToImage(focus, viewportSize)
        val center = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        val nextOffset = Offset(
            x = focus.x - center.x - (imagePoint.x - 0.5f) * viewportSize.width * targetZoom,
            y = focus.y - center.y - (imagePoint.y - 0.5f) * viewportSize.height * targetZoom
        )
        return copy(zoom = targetZoom, offset = nextOffset).clamped(viewportSize)
    }

    fun clamped(viewportSize: Size): CropViewport {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f) return this
        val safeZoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxX = viewportSize.width * (safeZoom - 1f) / 2f
        val maxY = viewportSize.height * (safeZoom - 1f) / 2f
        return CropViewport(
            zoom = safeZoom,
            offset = Offset(
                offset.x.coerceIn(-maxX, maxX),
                offset.y.coerceIn(-maxY, maxY)
            )
        )
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}

internal object CropGeometry {
    const val MIN_SIZE = 0.08f
    val defaultRect = Rect(0.04f, 0.04f, 0.96f, 0.96f)

    fun hitTest(rect: Rect, position: Offset, threshold: Float = 0.11f): CropHandle =
        hitTest(rect, position, threshold, threshold)

    fun hitTest(rect: Rect, position: Offset, thresholdX: Float, thresholdY: Float): CropHandle {
        fun near(targetX: Float, targetY: Float) =
            abs(position.x - targetX) <= thresholdX && abs(position.y - targetY) <= thresholdY

        val corner = listOf(
            CropHandle.TOP_LEFT to rect.topLeft,
            CropHandle.TOP_RIGHT to rect.topRight,
            CropHandle.BOTTOM_LEFT to rect.bottomLeft,
            CropHandle.BOTTOM_RIGHT to rect.bottomRight
        ).filter { near(it.second.x, it.second.y) }
            .minByOrNull { (it.second - position).getDistanceSquared() }
        return corner?.first ?: if (position.x in rect.left..rect.right && position.y in rect.top..rect.bottom) {
            CropHandle.MOVE
        } else CropHandle.NONE
    }

    fun drag(rect: Rect, handle: CropHandle, dx: Float, dy: Float): Rect {
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom
        when (handle) {
            CropHandle.TOP_LEFT -> {
                left = (left + dx).coerceIn(0f, right - MIN_SIZE)
                top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
            }
            CropHandle.TOP_RIGHT -> {
                right = (right + dx).coerceIn(left + MIN_SIZE, 1f)
                top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
            }
            CropHandle.BOTTOM_LEFT -> {
                left = (left + dx).coerceIn(0f, right - MIN_SIZE)
                bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f)
            }
            CropHandle.BOTTOM_RIGHT -> {
                right = (right + dx).coerceIn(left + MIN_SIZE, 1f)
                bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f)
            }
            CropHandle.MOVE -> {
                left = (left + dx).coerceIn(0f, 1f - rect.width)
                top = (top + dy).coerceIn(0f, 1f - rect.height)
                right = left + rect.width
                bottom = top + rect.height
            }
            CropHandle.NONE -> Unit
        }
        return Rect(left, top, right, bottom)
    }

    fun dragOnScreen(
        rect: Rect,
        handle: CropHandle,
        screenDx: Float,
        screenDy: Float,
        viewport: CropViewport,
        viewportSize: Size
    ): Rect {
        if (viewportSize.width <= 0f || viewportSize.height <= 0f || viewport.zoom <= 0f) return rect
        return drag(
            rect,
            handle,
            screenDx / (viewportSize.width * viewport.zoom),
            screenDy / (viewportSize.height * viewport.zoom)
        )
    }
}
