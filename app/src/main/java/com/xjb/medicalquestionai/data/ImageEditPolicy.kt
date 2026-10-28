package com.xjb.medicalquestionai.data

/**
 * Pure list-editing rules for image drafts, kept outside the ViewModel so the
 * "keep original" crop behavior can be unit-tested without Android classes.
 */
object ImageEditPolicy {

    /**
     * Whether a draft may accept one more image. Kept here so the composer
     * and the crop flow share one testable limit rule.
     */
    fun canAddImage(images: List<DraftImage>, maxImages: Int = 9): Boolean =
        images.size < maxImages

    /**
     * Inserts [cropped] immediately after the image with [targetId].
     * Returns null when the target no longer exists so callers can delete the
     * orphaned crop file rather than leaking it.
     */
    fun insertAfter(
        images: List<DraftImage>,
        targetId: String,
        cropped: DraftImage
    ): List<DraftImage>? {
        val index = images.indexOfFirst { it.id == targetId }
        if (index < 0) return null
        return buildList(images.size + 1) {
            addAll(images)
            add(index + 1, cropped)
        }
    }
}
