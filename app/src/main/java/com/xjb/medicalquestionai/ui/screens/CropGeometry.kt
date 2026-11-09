package com.xjb.medicalquestionai.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

internal enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

internal object CropGeometry {
    const val MIN_SIZE = 0.08f
    val defaultRect = Rect(0.04f, 0.04f, 0.96f, 0.96f)

    fun hitTest(rect: Rect, position: Offset, threshold: Float = 0.11f): CropHandle {
        fun near(targetX: Float, targetY: Float) =
            abs(position.x - targetX) <= threshold && abs(position.y - targetY) <= threshold

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
}
