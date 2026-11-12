package com.xjb.medicalquestionai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCompletenessCoreTest {

    private val draft = QuestionDraft(
        questionType = QuestionType.SINGLE,
        text = "虚构患者胸闷，首选检查是什么？",
        privacyConfirmed = true
    )

    @Test
    fun multipleVisibleQuestionsNeedConfirmation() {
        val result = AnalysisResultParser.parse(analysisJson(questionCount = "2"), draft)

        assertEquals("needs_confirmation", result.status)
        assertEquals(2, result.questionCount)
        assertTrue(result.warnings.any { it.contains("检测到画面中可能有 2 道题") })
        assertEquals(listOf("A"), result.answer)
    }

    @Test
    fun missingQuestionCountNeedsConfirmation() {
        val result = AnalysisResultParser.parse(analysisJson(questionCount = null), draft)

        assertEquals("needs_confirmation", result.status)
        assertTrue(result.warnings.any { it.contains("没有返回 question_count") })
    }

    @Test
    fun invalidQuestionCountNeedsConfirmation() {
        listOf("0", "-1", "1.5", "\"1\"", "true").forEach { questionCount ->
            val result = AnalysisResultParser.parse(
                analysisJson(questionCount = questionCount),
                draft
            )

            assertEquals(questionCount, "needs_confirmation", result.status)
            assertTrue(
                questionCount,
                result.warnings.any { it.contains("question_count 字段非法") }
            )
        }
    }

    @Test
    fun incompletePageNeedsConfirmation() {
        val result = AnalysisResultParser.parse(analysisJson(pageComplete = "false"), draft)

        assertEquals("needs_confirmation", result.status)
        assertEquals(false, result.pageComplete)
        assertTrue(result.warnings.any { it.contains("题干或选项可能被截断、拍漏") })
    }

    @Test
    fun missingOrInvalidPageCompleteNeedsConfirmation() {
        val missing = AnalysisResultParser.parse(analysisJson(pageComplete = null), draft)
        assertEquals("needs_confirmation", missing.status)
        assertTrue(missing.warnings.any { it.contains("没有返回 page_complete") })

        listOf("\"true\"", "null", "1").forEach { pageComplete ->
            val invalid = AnalysisResultParser.parse(
                analysisJson(pageComplete = pageComplete),
                draft
            )
            assertEquals(pageComplete, "needs_confirmation", invalid.status)
            assertTrue(
                pageComplete,
                invalid.warnings.any { it.contains("page_complete 字段格式异常") }
            )
        }
    }

    @Test
    fun completeSingleQuestionCanStayAnswered() {
        val result = AnalysisResultParser.parse(analysisJson(), draft)

        assertEquals("answered", result.status)
        assertEquals(1, result.questionCount)
        assertEquals(true, result.pageComplete)
    }

    @Test
    fun imagesRequireOneNonBlankObservationForEveryImage() {
        listOf(
            null,
            "[]",
            """[{"image_index":1,"observation":"虚构心电图显示相邻导联改变"}]""",
            """[{"image_index":1,"observation":"虚构心电图显示相邻导联改变"},{"image_index":2,"observation":" "}]""",
            """[{"image_index":1,"observation":"虚构心电图显示相邻导联改变"},{"image_index":3,"observation":"虚构题干文字"}]""",
            """[{"image_index":1,"observation":"虚构心电图显示相邻导联改变"},{"image_index":1,"observation":"虚构题干文字"}]""",
            """[{"observation":"虚构心电图显示相邻导联改变"},{"image_index":2,"observation":"虚构题干文字"}]"""
        ).forEach { observations ->
            val result = AnalysisResultParser.parse(
                analysisJson(imageObservations = observations),
                twoImageDraft()
            )

            assertEquals(observations.toString(), "needs_confirmation", result.status)
            assertTrue(
                observations.toString(),
                result.warnings.any { it.contains("image_observations") || it.contains("图片观察") }
            )
        }
    }

    @Test
    fun completeImageObservationsCanStayAnswered() {
        val result = AnalysisResultParser.parse(
            analysisJson(
                imageObservations = """
                    [
                      {"image_index":1,"observation":"虚构心电图显示相邻导联改变","uncertainty":""},
                      {"image_index":2,"observation":"虚构题干文字清楚可见","uncertainty":"部分基线轻微漂移"}
                    ]
                """.trimIndent()
            ),
            twoImageDraft()
        )

        assertEquals("answered", result.status)
        assertEquals(listOf(1, 2), result.imageObservations.map { it.imageIndex })
    }

    @Test
    fun noImagesDoNotRequireImageObservations() {
        listOf(null, "[]").forEach { observations ->
            val result = AnalysisResultParser.parse(
                analysisJson(questionCount = "1", imageObservations = observations),
                draft
            )

            assertEquals(observations.toString(), "answered", result.status)
            assertTrue(result.imageObservations.isEmpty())
        }
    }

    @Test
    fun previewRequiresPageEvidenceAndImageCoverage() {
        val completeRaw = analysisJson(
            imageObservations = """[{"image_index":1,"observation":"虚构心电图显示相邻导联改变"}]"""
        )

        val missingPage = PreviewJsonParser().feed(
            analysisJson(questionCount = null, pageComplete = null),
            QuestionType.SINGLE
        )
        assertNotNull(missingPage)
        assertFalse(missingPage!!.isComplete)
        assertTrue(missingPage.answerInvalid)
        assertTrue(missingPage.semanticIssue?.contains("question_count") == true)
        assertEquals("needs_confirmation", missingPage.toResult().status)

        val missingImage = PreviewJsonParser().feed(
            analysisJson(),
            QuestionType.SINGLE,
            imageCount = 1
        )
        assertNotNull(missingImage)
        assertFalse(missingImage!!.isComplete)
        assertTrue(missingImage.answerInvalid)
        assertTrue(missingImage.semanticIssue?.contains("image_observations") == true)
        assertEquals("needs_confirmation", missingImage.toResult().status)

        val complete = PreviewJsonParser().feed(
            completeRaw,
            QuestionType.SINGLE,
            imageCount = 0
        )
        assertNotNull(complete)
        assertTrue(complete!!.isComplete)
        assertEquals("answered", complete.toResult().status)
    }

    @Test
    fun openPreviewDoesNotDowngradeForFieldsThatHaveNotArrivedYet() {
        val preview = PreviewJsonParser().feed(
            openAnalysisJson(),
            QuestionType.SINGLE,
            imageCount = 1
        )

        assertNotNull(preview)
        assertFalse(preview!!.answerInvalid)
        assertNull(preview.semanticIssue)
        assertFalse(preview.isComplete)
        assertEquals(listOf("A"), preview.answer)
        assertEquals("answered", preview.toResult().status)
    }

    @Test
    fun openPreviewImmediatelyDowngradesOnObservedPageProblems() {
        listOf(
            openAnalysisJson(questionCount = "2") to "2 道题",
            openAnalysisJson(pageComplete = "false") to "截断",
            openAnalysisJson(questionCount = "\"1\"") to "question_count 字段非法",
            openAnalysisJson(pageComplete = "\"true\"") to "page_complete 字段格式异常"
        ).forEach { (raw, expectedIssue) ->
            val preview = PreviewJsonParser().feed(raw, QuestionType.SINGLE)

            assertNotNull(raw, preview)
            assertTrue(raw, preview!!.answerInvalid)
            assertTrue(raw, preview.semanticIssue?.contains(expectedIssue) == true)
            assertEquals(raw, "needs_confirmation", preview.toResult().status)
        }
    }

    @Test
    fun openPreviewImmediatelyDowngradesOnIncompleteImageObservations() {
        val preview = PreviewJsonParser().feed(
            openAnalysisJson(imageObservations = "[]"),
            QuestionType.SINGLE,
            imageCount = 1
        )

        assertNotNull(preview)
        assertTrue(preview!!.answerInvalid)
        assertTrue(preview.semanticIssue?.contains("image_observations") == true)
        assertEquals("needs_confirmation", preview.toResult().status)
    }

    @Test
    fun outOfScopeIsNotBlockedByPageOrImageEvidence() {
        val raw = """
            {"status":"out_of_scope","question_type":"unknown","answer_text":"本题不属于医学学习范围。"}
        """.trimIndent()

        val result = AnalysisResultParser.parse(raw, twoImageDraft())

        assertEquals("out_of_scope", result.status)
        assertFalse(result.needsConfirmation)
    }

    private fun analysisJson(
        questionCount: String? = "1",
        pageComplete: String? = "true",
        imageObservations: String? = null
    ): String {
        val fields = mutableListOf(
            "\"status\":\"answered\"",
            "\"question_type\":\"single\"",
            "\"recognized_question\":\"虚构患者胸闷，首选检查是什么？\"",
            "\"options_seen\":[{\"letter\":\"A\",\"text\":\"常规心电图\"},{\"letter\":\"B\",\"text\":\"胸部 X 线\"}]",
            "\"answer\":[\"A\"]",
            "\"answer_text\":\"常规心电图\"",
            "\"option_analysis\":[{\"option\":\"A\",\"verdict\":\"正确\",\"reason\":\"符合虚构题干\"},{\"option\":\"B\",\"verdict\":\"错误\",\"reason\":\"不是首选\"}]",
            "\"confidence\":\"high\"",
            "\"warnings\":[]"
        )
        questionCount?.let { fields += "\"question_count\":$it" }
        pageComplete?.let { fields += "\"page_complete\":$it" }
        imageObservations?.let { fields += "\"image_observations\":$it" }
        return "{${fields.joinToString(",")}}"
    }

    private fun twoImageDraft(): QuestionDraft = draft.copy(
        images = listOf(
            DraftImage("fictional-1", "/tmp/fictional-1.jpg", 10, 10, 10),
            DraftImage("fictional-2", "/tmp/fictional-2.jpg", 10, 10, 10)
        )
    )

    private fun openAnalysisJson(
        questionCount: String? = null,
        pageComplete: String? = null,
        imageObservations: String? = null
    ): String = analysisJson(
        questionCount = questionCount,
        pageComplete = pageComplete,
        imageObservations = imageObservations
    ).removeSuffix("}")
}
