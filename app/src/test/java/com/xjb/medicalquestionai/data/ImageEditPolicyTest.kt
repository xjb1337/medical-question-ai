package com.xjb.medicalquestionai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEditPolicyTest {

    private fun image(id: String, path: String = "$id.jpg") = DraftImage(
        id = id,
        path = path,
        width = 1200,
        height = 900,
        sizeBytes = 100,
        warnings = emptyList()
    )

    @Test
    fun keepOriginal_insertsCropImmediatelyAfterTarget() {
        val first = image("first")
        val target = image("target")
        val last = image("last")
        val cropped = image("crop", "crop.jpg")

        val result = ImageEditPolicy.insertAfter(listOf(first, target, last), "target", cropped)

        assertEquals(listOf("first", "target", "crop", "last"), result?.map { it.id })
        assertSame(target, result?.get(1))
        assertSame(cropped, result?.get(2))
        assertEquals(4, result?.size)
    }

    @Test
    fun keepOriginal_keepsTheOriginalObjectAndPath() {
        val original = image("original", "original.jpg")
        val cropped = image("crop", "crop.jpg")

        val result = ImageEditPolicy.insertAfter(listOf(original), "original", cropped)

        assertSame(original, result?.first())
        assertEquals("original.jpg", result?.first()?.path)
        assertEquals(2, result?.size)
    }

    @Test
    fun missingTarget_returnsNullWithoutChangingTheInputList() {
        val images = listOf(image("a"))
        val before = images.toList()

        assertNull(ImageEditPolicy.insertAfter(images, "missing", image("crop")))
        assertEquals(before, images)
    }

    @Test
    fun cropLimit_rejectsTheTenthImage() {
        val nine = (1..9).map { image("image-$it") }
        val eight = nine.dropLast(1)

        assertFalse(ImageEditPolicy.canAddImage(nine, 9))
        assertTrue(ImageEditPolicy.canAddImage(eight, 9))
        assertEquals(9, nine.size)
    }
}
