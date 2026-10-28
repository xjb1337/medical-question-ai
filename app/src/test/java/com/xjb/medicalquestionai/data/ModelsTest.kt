package com.xjb.medicalquestionai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun analysisResult_roundTripsThroughJson() {
        val original = AnalysisResult(
            status = "answered",
            questionType = "multiple",
            recognizedQuestion = "以下关于急性心肌梗死的心电图表现，正确的是？",
            answer = listOf("A", "C"),
            answerText = "A、C",
            imageObservations = listOf(
                ImageObservation(1, "II、III、aVF 导联 ST 段抬高", "部分基线漂移")
            ),
            optionAnalyses = listOf(
                OptionAnalysis("A", "正确", "下壁导联改变符合表现"),
                OptionAnalysis("B", "错误", "不符合题干限定")
            ),
            reasoningSteps = listOf(
                ReasoningStep("定位诊断", "相邻导联 ST 段抬高提示相应区域梗死。"),
                ReasoningStep("排除干扰", "单纯 T 波倒置不能作为急性期唯一依据。")
            ),
            keyPoints = listOf("ST 段抬高的导联组合用于定位"),
            pitfalls = listOf("不要把早期复极误认为急性梗死"),
            confidence = "high",
            warnings = emptyList(),
            usage = Usage(120, 88, 208)
        )

        val restored = AnalysisResult.fromJson(original.toJson())

        assertEquals(original.answer, restored.answer)
        assertEquals(original.answerText, restored.answerText)
        assertEquals(original.imageObservations, restored.imageObservations)
        assertEquals(original.optionAnalyses, restored.optionAnalyses)
        assertEquals(original.recognizedQuestion, restored.recognizedQuestion)
        assertEquals(original.reasoningSteps, restored.reasoningSteps)
        assertEquals(original.keyPoints, restored.keyPoints)
        assertEquals(original.pitfalls, restored.pitfalls)
        assertEquals(original.confidence, restored.confidence)
        assertEquals(original.usage, restored.usage)
    }

    @Test
    fun analysisResult_acceptsLegacyAndAlternativeKeys() {
        val json = """
            {
              "type": "single",
              "question": "首选的检查是？",
              "answer": ["B"],
              "reasoning_steps": ["先判断病情稳定性"],
              "usage": {"input_tokens": 10, "output_tokens": 20}
            }
        """.trimIndent()

        val result = AnalysisResult.fromJson(json)

        assertEquals("single", result.questionType)
        assertEquals("首选的检查是？", result.recognizedQuestion)
        assertEquals("B", result.answerDisplay)
        assertEquals("步骤 1", result.reasoningSteps.first().title)
        assertEquals(10, result.usage.promptTokens)
        assertEquals(20, result.usage.completionTokens)
    }

    @Test
    fun analysisResult_marksEmptyAnswerForConfirmation() {
        val result = AnalysisResult(
            status = "answered",
            answer = emptyList(),
            answerText = ""
        )

        assertTrue(result.needsConfirmation)
        assertEquals("未能确定答案", result.answerDisplay)
    }

    @Test
    fun fillInAnswerTextIsNotTreatedAsMissingAnswer() {
        val result = AnalysisResult(
            status = "answered",
            answer = emptyList(),
            answerText = "第一空：左心室肥大；第二空：ST 段压低"
        )

        assertFalse(result.needsConfirmation)
        assertEquals("第一空：左心室肥大；第二空：ST 段压低", result.answerDisplay)
    }

    @Test
    fun outOfScopeAnswerIsNotTreatedAsUncertainAnswer() {
        val result = AnalysisResult(
            status = "out_of_scope",
            answer = emptyList(),
            answerText = "本助手只解答医学学习问题。"
        )

        assertFalse(result.needsConfirmation)
        assertEquals("本助手只解答医学学习问题。", result.answerDisplay)
    }

    @Test
    fun questionTypeLabelsAndStorageValuesAreStable() {
        assertEquals(QuestionType.AUTO, QuestionType.fromStorage("auto"))
        assertEquals(QuestionType.SINGLE, QuestionType.fromStorage("single"))
        assertEquals(QuestionType.MULTIPLE, QuestionType.fromStorage("multiple"))
        assertEquals(QuestionType.FILL, QuestionType.fromStorage("fill"))
        assertEquals(QuestionType.SHORT, QuestionType.fromStorage("short"))
        assertEquals(QuestionType.AUTO, QuestionType.fromStorage("unexpected"))
        assertEquals("多选题", QuestionType.MULTIPLE.label)
        assertEquals("填空题", QuestionType.FILL.label)
        assertEquals("简答题", QuestionType.SHORT.label)
    }

    @Test
    fun questionFallbacksDescribeImageOnlyQuestions() {
        val question = Question(
            id = "q1",
            createdAt = 0L,
            questionType = "auto",
            source = "camera",
            questionText = "  ",
            imagePaths = listOf("a.jpg", "b.jpg"),
            status = "completed",
            result = null,
            model = "deepseek-flash",
            errorMessage = null,
            isFavorite = false
        )

        assertEquals("图片题目（2 张）", question.shortQuestion)
        assertEquals("拍照", question.sourceLabel)
        assertFalse(question.isFavorite)
    }
}
