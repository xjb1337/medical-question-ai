package com.xjb.medicalquestionai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface AnalysisEvent {
    data object WaitingForModel : AnalysisEvent
    data class Stage(val index: Int, val total: Int, val label: String) : AnalysisEvent
    data class Preview(val preview: AnalysisPreview) : AnalysisEvent
    data class Reasoning(val text: String) : AnalysisEvent
    data class Completed(val result: AnalysisResult) : AnalysisEvent
    data class Failed(val message: String) : AnalysisEvent
}

class QuestionAnalyzer(
    private val client: DeepSeekClient,
    private val imageStore: ImageStore
) {
    /**
     * 流式分析：边收边发预览，最后统一做一次严格校验。
     * 返回的 Flow 只会抛 CancellationException，其余错误以 Failed 事件返回，避免调用方漏捕。
     */
    fun analyzeStream(
        draft: QuestionDraft,
        apiKey: String,
        model: String,
        baseUrl: String,
        mode: AnswerMode,
        customModelSupportsImages: Boolean = false,
        customModelStreaming: Boolean = false
    ): Flow<AnalysisEvent> = flow {
        val totalStages = 4
        val requestStartedAt = System.currentTimeMillis()
        emit(AnalysisEvent.Stage(0, totalStages, "正在读取并压缩图片"))
        try {
            withTimeout(mode.timeoutMs) {
            val capability = ModelCatalog.capability(model)
            if (draft.images.isNotEmpty() && !ModelCatalog.supportsImages(model, customModelSupportsImages)) {
                val message = when (capability) {
                    ModelCapability.UNSUPPORTED ->
                        "当前模型 ${ModelCatalog.displayName(model)} 不支持图片，请切换到 DeepSeek Flash 后解析。"
                    ModelCapability.UNKNOWN ->
                        "当前自定义模型未声明支持图片。请在设置-高级接口中开启“自定义模型支持图片”，或切换到 DeepSeek Flash。"
                    ModelCapability.SUPPORTED -> ""
                }
                throw ApiException(message)
            }
            if (draft.images.isNotEmpty() &&
                imageStore.totalBytes(draft.images.map { it.path }) > MAX_TOTAL_IMAGE_BYTES
            ) {
                throw ApiException("图片总大小过大，请减少图片数量或先裁剪。")
            }
            val images = draft.images.map { imageStore.readBase64(it.path) }
            emit(AnalysisEvent.Stage(1, totalStages, "正在上传题目"))
            // 未知/自定义模型不能假设支持 JSON mode 或 thinking，否则容易被接口以 400/422 拒绝。
            val knownModel = ModelCatalog.isOfficialBaseUrl(baseUrl) &&
                ModelCatalog.capability(model) != ModelCapability.UNKNOWN
            val thinkingEnabled = knownModel && mode.thinkingEnabled
            val request = ChatRequest(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                messages = listOf(
                    ChatMessage("system", buildSystemPrompt(mode)),
                    ChatMessage("user", buildUserPrompt(draft))
                ),
                imagesBase64 = images,
                maxTokens = mode.maxTokens,
                temperature = if (thinkingEnabled) null else 0.1,
                thinking = ThinkingConfig(thinkingEnabled, if (thinkingEnabled) mode.reasoningEffort else null),
                sendThinkingParam = knownModel,
                jsonMode = knownModel
            )
            val content = StringBuilder()
            val previewParser = PreviewJsonParser()
            var usage = Usage()
            var responseModel = ""
            var lastPreviewLength = 0
            var emittedAnswerStage = false
            var finishReason: String? = null
            var sawDone = false
            // 未知/自定义接口默认不发送流式参数；只有用户在高级设置明确声明支持时才尝试 SSE。
            val usedStream = knownModel || customModelStreaming
            if (!usedStream) {
                emit(AnalysisEvent.WaitingForModel)
                val response = client.chatOnce(request, mode.timeoutMs)
                if (!emittedAnswerStage) {
                    emittedAnswerStage = true
                    emit(AnalysisEvent.Stage(2, totalStages, "正在接收答案"))
                }
                content.append(response.content)
                usage = response.usage
                responseModel = response.model
                finishReason = "stop"
                sawDone = true
            } else {
                client.stream(request).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ResponseStarted -> emit(AnalysisEvent.WaitingForModel)
                        is ChatStreamEvent.ResponseModel -> responseModel = event.model
                        is ChatStreamEvent.ReasoningDelta -> {
                            emit(AnalysisEvent.Reasoning(event.text))
                            if (!emittedAnswerStage && mode == AnswerMode.DETAILED) {
                                emittedAnswerStage = true
                                emit(AnalysisEvent.Stage(2, totalStages, "正在逐项分析选项"))
                            }
                        }
                        is ChatStreamEvent.ContentDelta -> {
                            if (!emittedAnswerStage) {
                                emittedAnswerStage = true
                                emit(AnalysisEvent.Stage(2, totalStages, "正在接收答案"))
                            }
                            content.append(event.text)
                            if (content.length - lastPreviewLength >= 48) {
                                lastPreviewLength = content.length
                                previewParser.feed(content.toString(), draft.questionType)?.let { emit(AnalysisEvent.Preview(it)) }
                            }
                        }
                        is ChatStreamEvent.UsageUpdate -> usage = event.usage
                        is ChatStreamEvent.Finished -> finishReason = event.finishReason
                        is ChatStreamEvent.StreamDone -> sawDone = true
                    }
                }
            }
            val normalizedFinishReason = validateFinishReason(finishReason, "回答")
            if (!sawDone) {
                throw ApiException("连接提前中断，未收到完整结束标记，请重试。")
            }
            previewParser.feed(content.toString(), draft.questionType)?.let { emit(AnalysisEvent.Preview(it)) }
            emit(AnalysisEvent.Stage(totalStages - 1, totalStages, "正在核对答案"))
            val result = parseResult(content.toString(), draft).copy(
                usage = usage,
                metrics = AnalysisMetrics(
                    requestModel = model,
                    responseModel = responseModel,
                    thinkingEnabled = thinkingEnabled,
                    streamed = usedStream,
                    finishReason = normalizedFinishReason,
                    transportClosed = sawDone,
                    saveStatus = "pending",
                    totalMs = System.currentTimeMillis() - requestStartedAt,
                    answerMode = mode.storageValue
                )
            )
            emit(AnalysisEvent.Completed(result))
            }
        } catch (e: TimeoutCancellationException) {
            emit(AnalysisEvent.Failed("分析超时（超过 ${mode.timeoutMs / 1000} 秒），请改用快速档或稍后重试。"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AnalysisEvent.Failed(e.message ?: "分析失败，请稍后重试"))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun followUp(
        question: Question,
        previous: List<FollowUp>,
        followUpText: String,
        attachImages: Boolean,
        apiKey: String,
        model: String,
        baseUrl: String,
        mode: AnswerMode = AnswerMode.STANDARD,
        customModelSupportsImages: Boolean = false
    ): FollowUpAnswer = withContext(Dispatchers.IO) {
        val request = buildFollowUpRequest(
            question, previous, followUpText, attachImages, apiKey, model, baseUrl, mode,
            customModelSupportsImages
        )
        val response = client.chatOnce(request, mode.timeoutMs)
        FollowUpAnswer(content = response.content.trim(), usage = response.usage)
    }

    /** 流式追问：正文边收边显示，取消时由调用方保留已收到的部分。 */
    fun followUpStream(
        question: Question,
        previous: List<FollowUp>,
        followUpText: String,
        attachImages: Boolean,
        apiKey: String,
        model: String,
        baseUrl: String,
        mode: AnswerMode = AnswerMode.STANDARD,
        customModelSupportsImages: Boolean = false,
        customModelStreaming: Boolean = false
    ): Flow<ChatStreamEvent> = flow {
        try {
            withTimeout(mode.timeoutMs) {
                val request = buildFollowUpRequest(
                    question, previous, followUpText, attachImages, apiKey, model, baseUrl, mode,
                    customModelSupportsImages
                )
                val knownModel = ModelCatalog.isOfficialBaseUrl(baseUrl) &&
                    ModelCatalog.capability(model) != ModelCapability.UNKNOWN
                if (!knownModel && !customModelStreaming) {
                    emit(ChatStreamEvent.ResponseStarted)
                    val response = client.chatOnce(request, mode.timeoutMs)
                    emit(ChatStreamEvent.ContentDelta(response.content))
                    emit(ChatStreamEvent.UsageUpdate(response.usage))
                    emit(ChatStreamEvent.Finished("stop"))
                    emit(ChatStreamEvent.StreamDone)
                } else {
                    emitAll(client.stream(request))
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ApiException("追问超时（超过 ${mode.timeoutMs / 1000} 秒），请稍后重试或改用快速档。")
        }
    }.flowOn(Dispatchers.IO)

    private fun buildFollowUpRequest(
        question: Question,
        previous: List<FollowUp>,
        followUpText: String,
        attachImages: Boolean,
        apiKey: String,
        model: String,
        baseUrl: String,
        mode: AnswerMode,
        customModelSupportsImages: Boolean = false
    ): ChatRequest {
        val result = question.result ?: throw ApiException("这道题还没有可追问的解析。")
        if (attachImages && question.imagePaths.isNotEmpty() &&
            !ModelCatalog.supportsImages(model, customModelSupportsImages)
        ) {
            val message = when (ModelCatalog.capability(model)) {
                ModelCapability.UNSUPPORTED ->
                    "当前模型 ${ModelCatalog.displayName(model)} 不支持图片，请关闭“附带原图”或切换到 DeepSeek Flash。"
                ModelCapability.UNKNOWN ->
                    "当前自定义模型未声明支持图片，请关闭“附带原图”，或在设置-高级接口中开启能力声明。"
                ModelCapability.SUPPORTED -> ""
            }
            throw ApiException(message)
        }
        if (question.imagePaths.isNotEmpty() && attachImages &&
            imageStore.totalBytes(question.imagePaths) > MAX_TOTAL_IMAGE_BYTES
        ) {
            throw ApiException("原图总大小过大，请关闭“附带原图”后再追问。")
        }
        val messages = mutableListOf(
            ChatMessage("system", FOLLOW_UP_SYSTEM_PROMPT),
            ChatMessage("user", trimQuestionContext(buildQuestionContext(question, result))),
            ChatMessage("assistant", result.answerDisplay.ifBlank { "（上一次解析没有给出答案）" })
        )
        val questionContextChars = messages[1].content.length
        trimFollowUpHistory(
            previous,
            maxChars = (MAX_FOLLOW_UP_HISTORY_CHARS - questionContextChars).coerceAtLeast(0)
        ).forEach { item ->
            messages += ChatMessage(item.role, item.content)
        }
        messages += ChatMessage("user", followUpText)
        val images = if (attachImages) question.imagePaths.map { imageStore.readBase64(it) } else emptyList()
        val knownModel = ModelCatalog.isOfficialBaseUrl(baseUrl) &&
            ModelCatalog.capability(model) != ModelCapability.UNKNOWN
        val thinkingEnabled = knownModel && mode.thinkingEnabled
        return ChatRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = messages,
            imagesBase64 = images,
            maxTokens = mode.maxTokens,
            temperature = if (thinkingEnabled) null else 0.1,
            thinking = ThinkingConfig(thinkingEnabled, if (thinkingEnabled) mode.reasoningEffort else null),
            sendThinkingParam = knownModel,
            jsonMode = false
        )
    }

    /** 供单元测试直接调用：严格解析由 [AnalysisResultParser] 负责。 */
    internal fun parseResult(raw: String, draft: QuestionDraft): AnalysisResult =
        AnalysisResultParser.parse(raw, draft)

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

    private fun buildSystemPrompt(mode: AnswerMode): String = buildString {
        append(SYSTEM_PROMPT)
        appendLine()
        appendLine()
        appendLine("【本次作答模式：${mode.label}】")
        appendLine(
            when (mode) {
                AnswerMode.FAST ->
                    "先给结论，再给最少必要解释：reasoning_steps 最多 3 步；option_analysis 只写正确项和 1-2 个关键干扰项；key_points 最多 2 条；pitfalls 最多 1 条。不要展开教材式长篇讲解。"
                AnswerMode.STANDARD ->
                    "先给结论，再给清晰解释：reasoning_steps 3-6 步；option_analysis 逐项说明；key_points 和 pitfalls 各 1-3 条。"
                AnswerMode.DETAILED ->
                    "完整讲题：reasoning_steps 6-10 步；option_analysis 必须逐项说明每个选项；补充易混点与鉴别依据；key_points 和 pitfalls 各 3-5 条。"
            }
        )
        appendLine("仍然必须只输出题目要求的 JSON 对象，不要输出 Markdown。")
    }



    companion object {
        const val PROMPT_VERSION = "device-feedback-v1"
        internal const val MAX_FOLLOW_UP_HISTORY_CHARS = 12_000
        internal const val MAX_QUESTION_CONTEXT_CHARS = 8_000
        private const val MAX_TOTAL_IMAGE_BYTES = 18L * 1024 * 1024

        /**
         * 流式结束原因必须显式存在且只能为 stop/normal；
         * length、content_filter、缺失或未知值都说明答案不完整，不能写入数据库冒充成功。
         */
        internal fun validateFinishReason(raw: String?, label: String = "回答"): String {
            val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: throw ApiException("接口未返回结束原因，无法确认${label}是否完整，请重试。")
            return when (normalized) {
                "length" -> throw ApiException("${label}未完成（达到长度上限）。请切换到更高档位后重试。")
                "content_filter" -> throw ApiException("${label}被模型安全策略截断，请调整题目图片或描述后重试。")
                "stop", "normal" -> normalized
                else -> throw ApiException("模型输出未正常完成（结束原因：$normalized），请重试。")
            }
        }

        /** 题干上下文也受预算限制，避免原题/上次解析很长时挤掉追问历史。 */
        internal fun trimQuestionContext(
            context: String,
            maxChars: Int = MAX_QUESTION_CONTEXT_CHARS
        ): String {
            if (maxChars <= 0) return ""
            if (context.length <= maxChars) return context
            val marker = "\n（原题上下文过长，已截断）"
            if (maxChars <= marker.length) return context.take(maxChars)
            return context.take(maxChars - marker.length).trimEnd() + marker
        }

        private fun FollowUp.isUsableForHistory(): Boolean =
            !failed &&
                content.isNotBlank() &&
                !content.contains("已停止生成") &&
                !content.startsWith("（追问失败：")

        /** 按整轮裁剪追问历史：从最新一轮往前保留，绝不留下只有半轮的消息。 */
        internal fun trimFollowUpHistory(
            previous: List<FollowUp>,
            maxChars: Int = MAX_FOLLOW_UP_HISTORY_CHARS
        ): List<FollowUp> {
            if (maxChars <= 0) return emptyList()
            val turns = mutableListOf<MutableList<FollowUp>>()
            var current: MutableList<FollowUp>? = null
            previous.forEach { item ->
                if (item.role != "user" && item.role != "assistant") return@forEach
                if (!item.isUsableForHistory()) return@forEach
                if (item.role == "user") {
                    current = mutableListOf(item)
                    turns += current!!
                } else {
                    // 没有对应提问的孤立回答无法构成完整轮次，直接丢弃。
                    current?.add(item)
                }
            }
            val kept = ArrayDeque<MutableList<FollowUp>>()
            var used = 0
            for (turn in turns.asReversed()) {
                // 只保留同时包含用户问题和助手回答的完整轮次。
                if (turn.none { it.role == "user" } || turn.none { it.role == "assistant" }) continue
                val cost = turn.sumOf { it.content.length }
                if (used + cost > maxChars) break
                kept.addFirst(turn)
                used += cost
            }
            return kept.flatten()
        }

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
  "status": "answered",
  "question_type": "single",
  "recognized_question": "你从文字和图片中识别出的完整题干（保留关键限定词和选项）",
  "answer": ["B"],
  "answer_text": "最终答案的文字表述；填空题、简答题可写在这里",
  "image_observations": [
    {"image_index": 1, "observation": "这一张图中实际可见的题干、选项、波形、影像特征", "uncertainty": "看不清、无法确认的部分；没有则留空"}
  ],
  "option_analysis": [
    {"option": "A", "verdict": "正确", "reason": "该选项对错的依据"}
  ],
  "reasoning_steps": [
    {"title": "步骤标题", "detail": "这一步骤的具体推理、鉴别点和依据"}
  ],
  "key_points": ["本题最值得记住的知识点"],
  "pitfalls": ["容易错的点、混淆点"],
  "confidence": "high",
  "warnings": ["需要用户核对的信息；没有则为空数组"]
}

字段取值说明（只取其中一个值，不要照抄说明文字）：status 取 answered、needs_confirmation、out_of_scope；question_type 取 single、multiple、fill、short、unknown；option_analysis.verdict 取 正确、错误、无法判断；confidence 取 high、medium、low。

要求：有图片时必须填写 image_observations，逐张说明实际可见信息与不确定处，不能把看不清的内容写成确定事实；选择题必须填写 option_analysis，逐项给出 verdict 和 reason。reasoning_steps 通常 3 到 8 步；key_points 和 pitfalls 各 1 到 5 条；answer 必须使用题干中的选项字母或选项原文。若题目信息不足，answer 可以为空数组，status 为 needs_confirmation，并在 warnings 中说明缺失内容。
        """.trimIndent()
    }
}

data class FollowUpAnswer(
    val content: String,
    val usage: Usage = Usage()
)
