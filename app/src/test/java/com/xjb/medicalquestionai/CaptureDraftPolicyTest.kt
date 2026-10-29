package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.QuestionDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftPolicyTest {

    @Test
    fun parkedDraftIsNeverSilentlyReplacedByAnotherNonEmptyDraft() {
        val parked = QuestionDraft(text = "上一份草稿")
        val current = QuestionDraft(text = "新的题目")

        assertFalse(canParkDraft(current, parked))
        assertTrue(canParkDraft(QuestionDraft(), parked))
        assertTrue(canParkDraft(current, null))
    }

    @Test
    fun onlyDraftsWithTextOrImagesCountAsContent() {
        assertFalse(QuestionDraft().hasContent())
        assertFalse(QuestionDraft(text = "  ").hasContent())
        assertTrue(QuestionDraft(text = "题目").hasContent())
    }
}
