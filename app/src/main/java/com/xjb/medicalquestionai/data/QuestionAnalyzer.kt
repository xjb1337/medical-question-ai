package com.xjb.medicalquestionai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class QuestionAnalyzer(
    private val client: DeepSeekClient,
    private val imageStore: ImageStore
) {
    suspend fun analyze(
        draft: QuestionDraft,
        apiKey: String,
        model: String,
        baseUrl: String
    ): AnalysisResult = withContext(Dispatchers.IO) {
        if (draft.images.isNotEmpty() && imageStore.totalBytes(draft.images.map { it.path }) > MAX_TOTAL_IMAGE_BYTES) {
            throw ApiException("图片总大小过大，请减少图片数量或先裁剪。")
        }
        val images = draft.images.map { imageStore.readBase64(it.path) }
        val response = client.chat(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(draft),
            imagesBase64 = images
        )
        parseResult(response.content, draft).copy(usage = response.usage)
    }

    suspend fun followUp(
        question: Question,
        previous: List<FollowUp>,
        followUpText: String,
        attachImages: Boolean,
        apiKey: String,
        model: String,
        baseUrl: String
    ): FollowUpAnswer = withContext(Dispatchers.IO) {
        val result = question.result ?: throw ApiException("这道题还没有可追问的解析。")
        if (question.imagePaths.isNotEmpty() && attachImages &&
            imageStore.totalBytes(question.imagePaths) > MAX_TOTAL_IMAGE_BYTES
        ) {
            throw ApiException("原图总大小过大，请关闭“附带原图”后再追问。")
        }
        val messages = mutableListOf(
            ChatMessage("system", FOLLOW_UP_SYSTEM_PROMPT),
            ChatMessage("user", buildQuestionContext(question, result)),
            ChatMessage("assistant", result.answerDisplay.ifBlank { "（上一次解析没有给出答案）" })
        )
        previous.forEach { item ->
            if (item.role == "user" || item.role == "assistant") {
                if (item.content.isNotBlank()) messages += ChatMessage(item.role, item.content)
            }
        }
        messages += ChatMessage("user", followUpText)
        val images = if (attachImages) question.imagePaths.map { imageStore.readBase64(it) } else emptyList()
        val response = client.chatConversation(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = messages,
            imagesBase64 = images,
            maxTokens = 3072
        )
        FollowUpAnswer(content = response.content.trim(), usage = response.usage)
    }

    private fun buildQuestionContext(question: Question, result: AnalysisResult): String = buildString {
        appendLine("这是一次围绕同一道医学题的追问。下面是原题和上次解析，请以此为基础回答。")
        appendLine("【用户输入的题目文字】")
        appendLine(question.questionText.ifBlank { "（无文字输入）" })
        appendLine("【识别出的完整题干】")
        appendLine(result.recognizedQuestion.ifBlank { "（未识别出完整题干）" })
        appendLine("【上次答案】")
        appendLine(result.answerDisplay.ifBlank { "（无）" })
        if (result.reasoningSteps.isNotEmpty()) {
            appendLine("【上次推理要点】")
            result.reasoningSteps.forEachIndexed { index, step ->
                appendLine("${index + 1}. ${step.title}：${step.detail}")
            }
        }
        if (result.imageObservations.isNotEmpty()) {
            appendLine("【上次的图片观察】")
            result.imageObservations.forEach { observation ->
                appendLine("- 图 ${observation.imageIndex}：${observation.observation}")
                if (observation.uncertainty.isNotBlank()) appendLine("  不确定：${observation.uncertainty}")
            }
        }
        if (result.optionAnalyses.isNotEmpty()) {
            appendLine("【上次的选项分析】")
            result.optionAnalyses.forEach { option ->
                appendLine("- ${option.option}：${option.verdict}；${option.reason}")
            }
        }
        if (result.keyPoints.isNotEmpty()) {
            appendLine("【关键知识点】")
            result.keyPoints.forEach { appendLine("- $it") }
        }
        appendLine("【图片】原题共有 ${question.imagePaths.size} 张图片。")
        if (question.imagePaths.isNotEmpty()) {
            appendLine("如果本轮消息附带原图，请结合图片回答；如果没有附带，请不要假装看到了图片细节。")
        }
    }

    private fun buildUserPrompt(draft: QuestionDraft): String = buildString {
        appendLine("请解答下面的医学题目。")
        appendLine("用户选择的题型：${draft.questionType.label}。")
        if (draft.questionType == QuestionType.AUTO) {
            appendLine("题型未指定，请自行判断是单选、多选还是其他形式。")
        }
        appendLine("用户输入的文字（可能为空，若有图片请以图片内容为主并相互核对）：")
        appendLine(draft.text.ifBlank { "（无文字输入）" })
        appendLine("图片数量：${draft.images.size} 张。请逐张查看，识别题干、选项、单位和图片中的关键信息。")
        appendLine("如果图片与文字冲突，请指出冲突并以更清晰的信息为准。")
    }

    private fun parseResult(raw: String, draft: QuestionDraft): AnalysisResult {
        val cleaned = stripMarkdownFence(raw)
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ApiException("模型返回的内容不是结构化答案，请重试。", raw = raw.take(1000))
        }
        val jsonText = cleaned.substring(start, end + 1)
        val obj = runCatching { JSONObject(jsonText) }.getOrElse {
            throw ApiException("模型返回的 JSON 无法解析，请重试。", raw = raw.take(1000))
        }
        val source = obj.optJSONObject("result") ?: obj
        val parsed = AnalysisResult.fromJson(source.toString())
        val normalizedType = normalizeQuestionType(parsed.questionType)
        val warnings = parsed.warnings.toMutableList()

        if (parsed.needsConfirmation) {
            warnings += "题干、选项或图片信息可能不完整，当前答案需要你对照原题核对。"
        }
        if (normalizedType == "unknown") {
            warnings += "未能确定题型，请手动选择题型或补充题干。"
        }
        if (draft.questionType == QuestionType.SINGLE && parsed.answer.size > 1) {
            warnings += "模型给出了多个答案，但你选择的是单选题，请重点核对。"
        }
        if (parsed.answer.isEmpty() && parsed.answerText.isBlank()) {
            warnings += "没有提取到明确答案。"
        }
        if (parsed.confidence.equals("low", ignoreCase = true)) {
            warnings += "模型自评把握较低，建议查阅教材或请老师确认。"
        }
        if (parsed.recognizedQuestion.isBlank() && draft.images.isNotEmpty() && draft.text.isBlank()) {
            warnings += "没有完整识别出题干，请确认图片是否清晰完整。"
        }
        if (draft.images.isNotEmpty() && parsed.imageObservations.isEmpty()) {
            warnings += "模型没有逐张说明图片观察结果，请重点核对题干和图中细节。"
        }
        if (normalizedType in setOf("single", "multiple") && parsed.optionAnalyses.isEmpty()) {
            warnings += "模型没有给出逐项分析，选项辨析请结合教材核对。"
        }

        val status = when {
            parsed.status == "out_of_scope" -> "out_of_scope"
            parsed.status == "needs_confirmation" -> "needs_confirmation"
            parsed.answer.isEmpty() && parsed.answerText.isBlank() -> "needs_confirmation"
            else -> "answered"
        }
        return parsed.copy(
            status = status,
            questionType = normalizedType,
            warnings = warnings.distinct(),
            rawJson = raw
        )
    }

    private fun normalizeQuestionType(value: String): String = when (value.lowercase().trim()) {
        "single", "single_choice", "单选", "单选题" -> "single"
        "multiple", "multiple_choice", "multi", "多选", "多选题" -> "multiple"
        "fill", "fill_blank", "填空", "填空题" -> "fill"
        "short", "short_answer", "简答", "简答题", "case" -> "short"
        else -> if (value.isBlank()) "unknown" else value.lowercase()
    }

    private fun stripMarkdownFence(value: String): String {
        var text = value.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.removeSuffix("```").trim()
        }
        return text
    }

    companion object {
        private const val MAX_TOTAL_IMAGE_BYTES = 18L * 1024 * 1024

        private val FOLLOW_UP_SYSTEM_PROMPT = """
你是“医学拍题助手”的解题老师，正在回答学生围绕上一道医学题提出的追问。请结合原题、上次解析和本轮问题，用严谨、好懂的中文讲解。

要求：
1. 只依据原题、图片中可见信息和对话内容回答；信息不足时明确指出缺少什么，不要编造。
2. 如果学生的问题指向选项辨析，请逐项说明为什么对、为什么错。
3. 如果学生要求换一种说法，请用更基础的语言重新解释，并给出记忆抓手。
4. 可以分点、分段，但不要输出 JSON，也不要使用 Markdown 表格。不要写临床诊疗建议。
5. 回答控制在 600 字以内，除非学生明确要求展开。
        """.trimIndent()

        private val SYSTEM_PROMPT = """
你是“医学拍题助手”的解题老师。你的读者是正在复习医学课程的学生。请严谨、逐步地分析题目，帮助他们理解，而不是只给一个字母。

工作原则：
1. 只依据用户提供的题干、选项和图片中可见的信息作答。看不清、缺选项、关键数值缺失或题目明显残缺时，必须明确说明，不得猜测或编造。
2. 先识别题型：单选题（single）、多选题（multiple）、填空题（fill）、简答题（short）或其他；无法判断时用 unknown。
3. 医学题常考“最可能”“首选”“禁忌”“错误的是”等限定词。请抓住否定词、年龄、性别、病程、实验室单位和剂量，避免因漏读而答错。
4. 多选题必须给出全部正确选项；单选题只给一个选项。若多个选项看似都正确，请说明鉴别依据和最终取舍。
5. 推理步骤要像老师讲题：先翻译题干考点，再列鉴别点，最后落到答案。不要写空泛套话。
6. 不确定就降低 confidence，并在 warnings 中说明需要核对什么。不要虚构指南、文献、剂量或诊断标准。
7. 填空题要按空的顺序逐空作答，answer_text 用“第1空：……；第2空：……”的格式；简答题要按要点分条，先给结论再给依据，突出关键词和逻辑。
8. 这是学习辅助，不替代临床诊疗。
9. 只处理医学及医学相关学习内容。若题目明显与医学无关，status 使用 "out_of_scope"，answer_text 用一句礼貌中文说明本助手只解答医学学习问题，不要给出无关答案。

只输出一个合法 JSON 对象，不要输出 Markdown 代码块、前后解释或注释。JSON 结构必须为：
{
  "status": "answered" 或 "needs_confirmation" 或 "out_of_scope",
  "question_type": "single" 或 "multiple" 或 "fill" 或 "short" 或 "unknown",
  "recognized_question": "你从文字和图片中识别出的完整题干（保留关键限定词和选项）",
  "answer": ["B"],
  "answer_text": "最终答案的文字表述；填空题或简答题可写在这里",
  "image_observations": [
    {"image_index": 1, "observation": "这一张图中实际可见的题干、选项、波形或影像特征", "uncertainty": "看不清或无法确认的部分；没有则留空"}
  ],
  "option_analysis": [
    {"option": "A", "verdict": "正确或错误或无法判断", "reason": "该选项对错的依据"}
  ],
  "reasoning_steps": [
    {"title": "步骤标题", "detail": "这一步骤的具体推理、鉴别点和依据"}
  ],
  "key_points": ["本题最值得记住的知识点"],
  "pitfalls": ["容易错的点或混淆点"],
  "confidence": "high" 或 "medium" 或 "low",
  "warnings": ["需要用户核对的信息；没有则为空数组"]
}

要求：有图片时必须填写 image_observations，逐张说明实际可见信息与不确定处，不能把看不清的内容写成确定事实；选择题必须填写 option_analysis，逐项给出 verdict 和 reason。reasoning_steps 通常 3 到 8 步；key_points 和 pitfalls 各 1 到 5 条；answer 必须使用题干中的选项字母或选项原文。若题目信息不足，answer 可以为空数组，status 为 needs_confirmation，并在 warnings 中说明缺失内容。
        """.trimIndent()
    }
}

data class FollowUpAnswer(
    val content: String,
    val usage: Usage = Usage()
)
