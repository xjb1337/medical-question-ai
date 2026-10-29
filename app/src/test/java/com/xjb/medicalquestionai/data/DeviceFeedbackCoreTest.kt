package com.xjb.medicalquestionai.data

import com.xjb.medicalquestionai.LifecycleStopEvent
import com.xjb.medicalquestionai.StopPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceFeedbackCoreTest {

    // ---------- 模型配置 ----------

    @Test
    fun catalogUsesCurrentOfficialModelsAndImageCapability() {
        assertEquals("deepseek-flash", ModelCatalog.defaultModel)
        assertTrue(ModelCatalog.supportsImages("deepseek-flash"))
        assertFalse(ModelCatalog.supportsImages("deepseek-v4-pro"))
        // 自定义/未知模型不能假定支持图片：能力未知时按不支持处理，避免误发图片请求。
        assertFalse(ModelCatalog.supportsImages("my-gateway-model"))
        assertEquals(ModelCapability.UNKNOWN, ModelCatalog.capability("my-gateway-model"))
        assertEquals(ModelCapability.SUPPORTED, ModelCatalog.capability("deepseek-flash"))
        assertEquals(ModelCapability.UNSUPPORTED, ModelCatalog.capability("deepseek-v4-pro"))
        assertEquals("DeepSeek V4 Pro", ModelCatalog.displayName("deepseek-v4-pro"))
        assertEquals("deepseek-v4-pro", ModelCatalog.PRO.id)
    }

    @Test
    fun officialBaseUrlCheckIsStrict() {
        assertTrue(ModelCatalog.isOfficialBaseUrl("https://api.deepseek.com"))
        assertTrue(ModelCatalog.isOfficialBaseUrl("https://api.deepseek.com/v1"))
        assertFalse(ModelCatalog.isOfficialBaseUrl("http://api.deepseek.com"))
        assertFalse(ModelCatalog.isOfficialBaseUrl("https://api.deepseek.com.evil.test"))
        assertFalse(ModelCatalog.isOfficialBaseUrl("https://gateway.example.test/v1"))
        assertFalse(ModelCatalog.isOfficialBaseUrl("not a url"))
        assertTrue(ModelCatalog.isOfficialBaseUrl(""))
    }

    // ---------- 设置迁移 ----------

    @Test
    fun migratesLegacyOfficialModelsToCurrentNames() {
        val chat = SettingsMigrations.migrate("deepseek-chat", "https://api.deepseek.com", null, 1)
        assertEquals("deepseek-flash", chat.model)
        assertEquals(AnswerMode.FAST, chat.answerMode)
        assertTrue(chat.changed)
        assertTrue(chat.note.contains("deepseek-chat"))

        val reasoner = SettingsMigrations.migrate("deepseek-reasoner", "https://api.deepseek.com/v1", "detailed", 1)
        assertEquals("deepseek-flash", reasoner.model)
        assertEquals(AnswerMode.DETAILED, reasoner.answerMode)
        assertTrue(reasoner.changed)
    }

    @Test
    fun customGatewayKeepsUserModelName() {
        val custom = SettingsMigrations.migrate("deepseek-chat", "https://my-gateway.example.test/v1", "fast", 2)
        assertEquals("deepseek-chat", custom.model)
        assertEquals(AnswerMode.FAST, custom.answerMode)
        assertTrue(custom.note.isBlank())
        // version 相同、模式已有值、模型未变 -> 不产生无意义写入
        assertFalse(custom.changed)
    }

    @Test
    fun blankSettingsFallBackToFlashAndStandard() {
        val blank = SettingsMigrations.migrate(null, "", "", 0)
        assertEquals("deepseek-flash", blank.model)
        assertEquals(AnswerMode.STANDARD, blank.answerMode)
        assertEquals(SettingsMigrations.CURRENT_VERSION, blank.version)
        assertTrue(blank.changed)
    }

    @Test
    fun answerModeParametersMatchProductTiers() {
        assertFalse(AnswerMode.FAST.usesThinking)
        assertNull(AnswerMode.FAST.reasoningEffort)
        assertEquals(3072, AnswerMode.FAST.maxTokens)

        assertTrue(AnswerMode.STANDARD.usesThinking)
        assertEquals("low", AnswerMode.STANDARD.reasoningEffort)
        assertEquals(8192, AnswerMode.STANDARD.maxTokens)

        assertEquals("high", AnswerMode.DETAILED.reasoningEffort)
        assertEquals(16384, AnswerMode.DETAILED.maxTokens)

        assertEquals(AnswerMode.STANDARD, AnswerMode.fromStorage("STANDARD"))
        assertEquals(AnswerMode.STANDARD, AnswerMode.fromStorage("unknown"))
    }

    // ---------- 请求体 ----------

    private fun request(
        baseUrl: String = "https://api.deepseek.com",
        mode: AnswerMode = AnswerMode.STANDARD,
        images: List<String> = emptyList(),
        jsonMode: Boolean = true
    ) = ChatRequest(
        baseUrl = baseUrl,
        apiKey = "sk-test",
        model = "deepseek-flash",
        messages = listOf(ChatMessage("system", "sys"), ChatMessage("user", "题干")),
        imagesBase64 = images,
        maxTokens = mode.maxTokens,
        temperature = if (mode.thinkingEnabled) null else 0.1,
        thinking = ThinkingConfig(mode.thinkingEnabled, mode.reasoningEffort),
        jsonMode = jsonMode
    )

    @Test
    fun payloadSendsThinkingTiersAndJsonMode() {
        val fast = buildChatPayload(request(mode = AnswerMode.FAST), stream = true)
        assertEquals("disabled", fast.getJSONObject("thinking").getString("type"))
        assertFalse(fast.has("reasoning_effort"))
        assertEquals(0.1, fast.getDouble("temperature"), 0.0)
        assertEquals("json_object", fast.getJSONObject("response_format").getString("type"))
        assertTrue(fast.getBoolean("stream"))

        val detailed = buildChatPayload(request(mode = AnswerMode.DETAILED), stream = true)
        assertEquals("enabled", detailed.getJSONObject("thinking").getString("type"))
        assertEquals("high", detailed.getString("reasoning_effort"))
        assertFalse(detailed.has("temperature"))
    }

    @Test
    fun imagesOnlyAttachToLastUserMessage() {
        val payload = buildChatPayload(
            request(images = listOf("AAAA", "BBBB")),
            stream = true
        )
        val messages = payload.getJSONArray("messages")
        val system = messages.getJSONObject(0)
        assertEquals("sys", system.getString("content"))

        val user = messages.getJSONObject(1)
        val parts = user.getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("题干", parts.getJSONObject(0).getString("text"))
        assertEquals(2, parts.length() - 1)
        assertTrue(
            parts.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,AAAA")
        )
    }

    @Test
    fun streamOptionsOnlyForOfficialBaseUrl() {
        assertTrue(
            buildChatPayload(request(baseUrl = "https://api.deepseek.com"), stream = true)
                .has("stream_options")
        )
        assertFalse(
            buildChatPayload(request(baseUrl = "https://gateway.example.test/v1"), stream = true)
                .has("stream_options")
        )
        assertFalse(
            buildChatPayload(request(baseUrl = "https://api.deepseek.com"), stream = false)
                .has("stream_options")
        )
    }

    // ---------- SSE 增量解析 ----------

    @Test
    fun sseParserHandlesUtf8SplitAcrossPackets() {
        val parser = SseParser()
        val text = "data: 中文答案\n\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 在“中”的三个字节中间切开
        val cut = text.toByteArray(Charsets.UTF_8).indexOfFirst { it.toInt() == 0xE4 } + 1
        val first = parser.feed(bytes.copyOfRange(0, cut))
        val second = parser.feed(bytes.copyOfRange(cut, bytes.size))
        val events = first + second
        assertEquals(1, events.size)
        assertEquals("中文答案", events.first().data)
    }

    @Test
    fun sseParserSupportsCommentsMultilineDataAndFinish() {
        val parser = SseParser()
        val chunk = ": keep-alive\n\ndata: line1\ndata: line2\n\nevent: done\ndata: [DONE]\n\n"
        val events = parser.feed(chunk.toByteArray())
        assertEquals(2, events.size)
        assertEquals("line1\nline2", events[0].data)
        assertEquals("done", events[1].event)
        assertEquals("[DONE]", events[1].data)
        assertEquals(0, parser.finish().size)
    }

    @Test
    fun sseParserFlushesUnterminatedLineOnFinish() {
        val parser = SseParser()
        val events = parser.feed("data: tail".toByteArray())
        assertTrue(events.isEmpty())
        val flushed = parser.finish()
        assertEquals(1, flushed.size)
        assertEquals("tail", flushed.first().data)
    }

    // ---------- 流式预览 ----------

    @Test
    fun previewShowsAnswerAsSoonAsFieldsComplete() {
        val parser = PreviewJsonParser()
        assertNull(parser.feed("{\"status\":\"answered\",\"recognized_question\":\"题干还在"))
        assertNull(parser.feed("{\"recognized_question\":\"半句"))
        val preview = parser.feed(
            "{\"status\":\"answered\",\"question_type\":\"single\"," +
                "\"recognized_question\":\"急性心梗首选检查？\"," +
                "\"answer\":[\"B\"],\"answer_text\":\"心电图\",\"warnings\":[]}"
        )
        assertEquals("B", preview?.answer?.first())
        assertEquals("心电图", preview?.answerText)
        assertEquals("single", preview?.questionType)
    }

    @Test
    fun previewIgnoresBracesInsideStringsAndNestedObjects() {
        val parser = PreviewJsonParser()
        val preview = parser.feed(
            "{\"status\":\"answered\",\"question_type\":\"single\",\"recognized_question\":\"题干包含 {花括号} 和 \\\"引号\\\"\"," +
                "\"image_observations\":[{\"image_index\":1,\"observation\":\"ST 段抬高\"}]," +
                "\"answer\":[\"A\"],\"warnings\":[]}"
        )
        assertEquals("题干包含 {花括号} 和 \"引号\"", preview?.recognizedQuestion)
        assertEquals("A", preview?.answer?.first())
    }

    // ---------- 最终 JSON 解析 ----------

    private val draft = QuestionDraft(questionType = QuestionType.SINGLE, text = "题干", privacyConfirmed = true)

    @Test
    fun parserAcceptsFenceAndSurroundingExplanation() {
        val raw = """
            下面是答案：
            ```json
            {"status":"answered","question_type":"单选","recognized_question":"题干","answer":["B"],
             "option_analysis":[{"option":"A","verdict":"错误","reason":"不符合"},
                               {"option":"B","verdict":"正确","reason":"符合诊断标准"}],"confidence":"high","warnings":[]}
            ```
            希望有帮助。
        """.trimIndent()
        val result = AnalysisResultParser.parse(raw, draft)
        assertEquals("single", result.questionType)
        assertEquals("B", result.answerDisplay)
        assertEquals("answered", result.status)
    }

    @Test
    fun parserHandlesBracesInStringsAndWrapperObject() {
        val raw = """
            {"result":{"status":"answered","question_type":"multiple","recognized_question":"包含 } 的题干",
            "answer":["A","C"],"confidence":"medium","warnings":[]}}
        """.trimIndent()
        val result = AnalysisResultParser.parse(
            raw,
            QuestionDraft(questionType = QuestionType.MULTIPLE, text = "题干", privacyConfirmed = true)
        )
        assertEquals("multiple", result.questionType)
        assertEquals(listOf("A", "C"), result.answer)
    }

    @Test
    fun truncatedJsonFailsWithFriendlyMessage() {
        try {
            AnalysisResultParser.parse("{\"status\":\"answered\",\"answer\":[\"B\"]", draft)
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message.orEmpty().contains("不完整"))
        }
    }

    @Test
    fun emptyAnswerGetsNeedsConfirmationAndWarnings() {
        val raw = """
            {"status":"answered","question_type":"single","recognized_question":"题干","answer":[],
             "answer_text":"","confidence":"low"}
        """.trimIndent()
        val result = AnalysisResultParser.parse(raw, draft)
        assertEquals("needs_confirmation", result.status)
        assertTrue(result.warnings.any { it.contains("没有提取到明确答案") })
        assertTrue(result.warnings.any { it.contains("把握较低") })
        assertTrue(result.warnings.any { it.contains("逐项分析") })
    }

    @Test
    fun singleChoiceWithMultipleAnswersAddsWarning() {
        val raw = """{"status":"answered","question_type":"single","answer":["A","B"],"confidence":"high"}"""
        val result = AnalysisResultParser.parse(raw, draft)
        assertTrue(result.warnings.any { it.contains("单选题") })
    }

    @Test
    fun imageQuestionWithoutObservationsAddsWarning() {
        val imageDraft = QuestionDraft(
            questionType = QuestionType.AUTO,
            text = "",
            images = listOf(DraftImage(id = "1", path = "/tmp/a.jpg", width = 10, height = 10, sizeBytes = 10)),
            privacyConfirmed = true
        )
        val raw = """{"status":"answered","question_type":"single","answer":["A"],"confidence":"high"}"""
        val result = AnalysisResultParser.parse(raw, imageDraft)
        assertTrue(result.warnings.any { it.contains("逐张说明") })
        assertTrue(result.warnings.any { it.contains("没有完整识别出题干") })
    }

    @Test
    fun outOfScopeIsPreserved() {
        val raw = """{"status":"out_of_scope","question_type":"unknown","answer_text":"只解答医学问题"}"""
        val result = AnalysisResultParser.parse(raw, draft)
        assertEquals("out_of_scope", result.status)
        assertFalse(result.needsConfirmation)
    }

    // ---------- 追问上下文预算 ----------

    @Test
    fun followUpHistoryKeepsNewestCompleteTurnsOnly() {
        val previous = (1..4).flatMap { turn ->
            listOf(
                FollowUp("u$turn", "q", "user", "U$turn" + "x".repeat(40), turn.toLong()),
                FollowUp("a$turn", "q", "assistant", "A$turn" + "y".repeat(40), turn.toLong())
            )
        }
        val trimmed = QuestionAnalyzer.trimFollowUpHistory(previous, maxChars = 220)
        assertEquals(4, trimmed.size)
        assertEquals("U3", trimmed.first().content.take(2))
        assertEquals("A4", trimmed.last().content.take(2))
        assertEquals(0, trimmed.size % 2)
    }

    @Test
    fun followUpHistoryNeverKeepsHalfTurn() {
        val previous = listOf(
            FollowUp("u1", "q", "user", "问".repeat(100), 1),
            FollowUp("a1", "q", "assistant", "答".repeat(100), 2)
        )
        val trimmed = QuestionAnalyzer.trimFollowUpHistory(previous, maxChars = 150)
        assertTrue(trimmed.isEmpty())
    }

    @Test
    fun followUpHistoryExcludesFailedAndStoppedMessages() {
        val previous = listOf(
            FollowUp("u1", "q", "user", "正常提问", 1),
            FollowUp("a1", "q", "assistant", "失败回答", 2, failed = true),
            FollowUp("u2", "q", "user", "第二问", 3),
            FollowUp("a2", "q", "assistant", "部分内容\n\n（已停止生成）", 4),
            FollowUp("u3", "q", "user", "第三问", 5),
            FollowUp("a3", "q", "assistant", "正常回答", 6)
        )
        val trimmed = QuestionAnalyzer.trimFollowUpHistory(previous, maxChars = 1000)
        assertEquals(listOf("第三问", "正常回答"), trimmed.map { it.content })
    }

    @Test
    fun trimQuestionContextRespectsCharacterBudget() {
        val context = "题干".repeat(5_000)
        val trimmed = QuestionAnalyzer.trimQuestionContext(context, maxChars = 200)
        assertEquals(200, trimmed.length)
        assertTrue(trimmed.endsWith("（原题上下文过长，已截断）"))
    }

    @Test
    fun previewShowsPartialContentButOnlyCompletesWithAllFields() {
        val parser = PreviewJsonParser()
        // 有答案但缺 status / warnings 时可以先展示部分内容，但绝不能标记为完整预览。
        val partial = parser.feed("{\"answer\":[\"B\"],\"answer_text\":\"心电图\"}")
        assertNotNull(partial)
        assertFalse(partial!!.isComplete)
        val missingWarnings = parser.feed(
            "{\"status\":\"answered\",\"answer\":[\"B\"],\"answer_text\":\"心电图\"}"
        )
        assertNotNull(missingWarnings)
        assertFalse(missingWarnings!!.isComplete)
        val complete = parser.feed(
            "{\"status\":\"answered\",\"question_type\":\"single\",\"answer\":[\"B\"],\"answer_text\":\"心电图\",\"warnings\":[]}"
        )
        assertEquals("answered", complete?.status)
        assertEquals(listOf("B"), complete?.answer)
        assertTrue(complete?.isComplete == true)
        assertTrue(complete?.warnings?.isEmpty() == true)
    }

    @Test
    fun previewParsesWarningsArray() {
        val preview = PreviewJsonParser().feed(
            "{\"status\":\"answered\",\"question_type\":\"single\",\"answer\":[\"A\"]," +
                "\"warnings\":[\"题干可能不完整\",\"请核对选项\"]}"
        )
        assertEquals(listOf("题干可能不完整", "请核对选项"), preview?.warnings)
    }

    @Test
    fun parserDowngradesInvalidChoiceAnswers() {
        val single = AnalysisResultParser.parse(
            """{"status":"answered","question_type":"single","answer":["A","B"],"confidence":"high","warnings":[]}""",
            draft
        )
        assertEquals("needs_confirmation", single.status)
        assertTrue(single.warnings.any { it.contains("单选题") })

        val multipleDraft = QuestionDraft(questionType = QuestionType.MULTIPLE, text = "题干", privacyConfirmed = true)
        val multiple = AnalysisResultParser.parse(
            """{"status":"answered","question_type":"multiple","answer":["A"],"confidence":"high","warnings":[]}""",
            multipleDraft
        )
        assertEquals("needs_confirmation", multiple.status)
        assertTrue(multiple.warnings.any { it.contains("多选题") })

        val missingOption = AnalysisResultParser.parse(
            """{"status":"answered","question_type":"single","answer":["D"],"option_analysis":[{"option":"A","verdict":"错误","reason":"不符合"}],"confidence":"high","warnings":[]}""",
            draft
        )
        assertEquals("needs_confirmation", missingOption.status)
        assertTrue(missingOption.warnings.any { it.contains("不一致") })
    }

    @Test
    fun settingsMigrationDoesNotReapplyAfterCurrentVersion() {
        val legacy = SettingsMigrations.migrate(
            "deepseek-chat",
            "https://api.deepseek.com",
            "fast",
            SettingsMigrations.CURRENT_VERSION
        )
        assertEquals("deepseek-chat", legacy.model)
        assertFalse(legacy.changed)
        assertEquals("", legacy.note)
    }

    @Test
    fun questionToEntityKeepsOriginalRawJson() {
        val raw = """{"status":"answered","question_type":"single","answer":["A"],"legacy_note":"保留我"}"""
        val question = Question(
            id = "q-raw",
            createdAt = 0L,
            questionType = "single",
            source = "text",
            questionText = "题干",
            imagePaths = emptyList(),
            status = "completed",
            result = AnalysisResult.fromJson(raw),
            model = "deepseek-flash",
            errorMessage = null,
            isFavorite = false
        )
        assertEquals(raw, question.toEntity().answerJson)
    }

    @Test
    fun connectionCheckResultCanReportUnavailableModelList() {
        val result = ConnectionCheckResult(emptyList(), selectedModelFound = false, modelListAvailable = false)
        assertFalse(result.modelListAvailable)
        assertFalse(result.selectedModelFound)
    }

    @Test
    fun finishReasonMustBeExplicitStopOrNormal() {
        assertEquals("stop", QuestionAnalyzer.validateFinishReason("stop"))
        assertEquals("normal", QuestionAnalyzer.validateFinishReason(" NORMAL "))
        listOf(null, "", "length", "content_filter", "tool_calls").forEach { raw ->
            assertThrows(ApiException::class.java) {
                QuestionAnalyzer.validateFinishReason(raw)
            }
        }
    }

    @Test
    fun previewKeepsRootOpenContentButDoesNotComplete() {
        val preview = PreviewJsonParser().feed(
            "{\"status\":\"answered\",\"question_type\":\"single\",\"answer\":[\"A\"],\"warnings\":[]"
        )
        assertNotNull(preview)
        assertFalse(preview!!.isComplete)
        assertEquals(listOf("A"), preview.answer)
    }

    @Test
    fun singleAnswerStringContainingTwoOptionsIsRejected() {
        val result = AnalysisResultParser.parse(
            """{"status":"answered","question_type":"single","answer":["A、B"],"confidence":"high","warnings":[]}""",
            draft
        )
        assertEquals("needs_confirmation", result.status)
        assertTrue(result.warnings.any { it.contains("单选题") })
    }

    @Test
    fun missingModelTypeCannotBeMaskedByManualSelection() {
        val result = AnalysisResultParser.parse(
            """{"status":"answered","answer":["A"],"confidence":"high","warnings":[]}""",
            draft
        )
        assertEquals("needs_confirmation", result.status)
        assertTrue(result.warnings.any { it.contains("题型") })
    }

    @Test
    fun previewRejectsOptionAnalysisMismatch() {
        val preview = PreviewJsonParser().feed(
            """{"status":"answered","question_type":"single","answer":["A"],"warnings":[],"option_analysis":[{"option":"B","verdict":"正确","reason":"不符"}]}""",
            QuestionType.SINGLE
        )
        assertNotNull(preview)
        assertFalse(preview!!.isComplete)
        assertTrue(preview.semanticIssue?.contains("不一致") == true)
    }

    @Test
    fun parserRequiresExplicitWarningsArray() {
        val result = AnalysisResultParser.parse(
            """{"status":"answered","question_type":"single","answer":["A"],"confidence":"high"}""",
            draft
        )
        assertEquals("needs_confirmation", result.status)
        assertTrue(result.warnings.any { it.contains("warnings") })
    }

    @Test
    fun staleAnswerTracksInputRevision() {
        val result = AnalysisResult.fromJson(
            """{"status":"answered","question_type":"single","answer":["A"],"warnings":[]}"""
        )
        val question = Question(
            id = "q-stale",
            createdAt = 0L,
            questionType = "single",
            source = "text",
            questionText = "题干",
            imagePaths = emptyList(),
            status = "completed",
            result = result,
            model = "deepseek-flash",
            errorMessage = null,
            isFavorite = false,
            inputRevision = 2L,
            answerInputRevision = 1L
        )
        assertTrue(question.isAnswerStale)
        assertFalse(question.copy(answerInputRevision = 2L).isAnswerStale)
    }

    @Test
    fun customModelDoesNotGainImageCapabilityByDefault() {
        assertTrue(ModelCatalog.supportsImages("deepseek-flash"))
        assertFalse(ModelCatalog.supportsImages("deepseek-v4-pro"))
        assertFalse(ModelCatalog.supportsImages("my-private-model"))
        assertFalse(ModelCatalog.supportsImages("deepseek-chat"))
    }

    @Test
    fun metricsRoundTripThroughAnalysisResultJson() {
        val metrics = AnalysisMetrics(
            requestModel = "deepseek-flash",
            responseModel = "deepseek-flash",
            thinkingEnabled = true,
            streamed = true,
            finishReason = "stop",
            transportClosed = true,
            saveStatus = "saved",
            totalMs = 1234L,
            answerMode = "fast",
            clickToImageReadyMs = 120L,
            clickToRequestReadyMs = 180L,
            clickToRequestBodySentMs = 260L,
            clickToHeadersMs = 420L,
            clickToFirstEventMs = 430L,
            clickToFirstContentMs = 520L,
            clickToFirstAnswerMs = 900L,
            validationMs = 80L,
            saveMs = 40L
        )
        val result = AnalysisResult(
            status = "answered",
            questionType = "single",
            answer = listOf("A"),
            metrics = metrics
        )
        val restored = AnalysisResult.fromJson(result.toJson())
        assertEquals(metrics, restored.metrics)
    }

    @Test
    fun previewRejectsMissingModelTypeEvenWithManualSelection() {
        val preview = PreviewJsonParser().feed(
            """{"status":"answered","answer":["A"],"warnings":[]}""",
            QuestionType.SINGLE
        )
        assertNotNull(preview)
        assertFalse(preview!!.isComplete)
        assertTrue(preview.semanticIssue?.contains("题型") == true)
    }

    @Test
    fun nullFollowUpContentDoesNotEnterHistory() {
        val previous = listOf(
            FollowUp("u1", "q", "user", "请解释", 1L),
            FollowUp("a1", "q", "assistant", "null", 2L),
            FollowUp("u2", "q", "user", "再解释", 3L),
            FollowUp("a2", "q", "assistant", "有效回答", 4L)
        )
        val trimmed = QuestionAnalyzer.trimFollowUpHistory(previous)
        assertEquals(listOf("u2", "a2"), trimmed.map { it.id })
    }

    @Test
    fun illegalPreviewIsDowngradedBeforeAnswerCard() {
        val cases = listOf(
            """{"status":"answered","question_type":"single","answer":["A、B"],"warnings":[]}""",
            """{"status":"answered","question_type":"banana","answer":["A"],"warnings":[]}""",
            """{"status":"answered","question_type":"single","answer":["A"],"warnings":[],"option_analysis":[{"option":"B","verdict":"正确","reason":"不符"}]}"""
        )
        cases.forEach { raw ->
            val preview = PreviewJsonParser().feed(raw, QuestionType.SINGLE)
            assertNotNull(raw, preview)
            assertTrue(raw, preview!!.answerInvalid)
            val result = preview.toResult()
            assertEquals(raw, "needs_confirmation", result.status)
            assertTrue(raw, result.answer.isEmpty())
            assertTrue(raw, result.answerText.isBlank())
        }
    }

    @Test
    fun pollutedNullPrefixHistoryIsIsolatedButNormalNullWordIsKept() {
        val previous = listOf(
            FollowUp("u1", "q", "user", "第一问", 1L),
            FollowUp("a1", "q", "assistant", "nullnullnullfixture answer", 2L),
            FollowUp("u2", "q", "user", "第二问", 3L),
            FollowUp("a2", "q", "assistant", "null hypothesis 是正常英文术语", 4L)
        )
        val trimmed = QuestionAnalyzer.trimFollowUpHistory(previous)
        assertEquals(listOf("u2", "a2"), trimmed.map { it.id })
    }

    @Test
    fun lifecycleStopPolicyDoesNotStopOnRotationOrPermissionDialogs() {
        assertFalse(StopPolicy.shouldStopOnLifecycle(LifecycleStopEvent.STOP, changingConfigurations = true))
        assertFalse(StopPolicy.shouldStopOnLifecycle(LifecycleStopEvent.DESTROY, changingConfigurations = true))
        assertFalse(StopPolicy.shouldStopOnLifecycle(LifecycleStopEvent.OTHER, changingConfigurations = false))
        assertTrue(StopPolicy.shouldStopOnLifecycle(LifecycleStopEvent.STOP, changingConfigurations = false))
        assertTrue(StopPolicy.shouldStopOnLifecycle(LifecycleStopEvent.DESTROY, changingConfigurations = false))
    }

    @Test
    fun requestIdentityRejectsLateStopFromOldPage() {
        assertFalse(StopPolicy.matchesRequest(activeRequestId = "new-request", expectedRequestId = "old-request"))
        assertTrue(StopPolicy.matchesRequest(activeRequestId = "new-request", expectedRequestId = null))
        assertTrue(StopPolicy.matchesRequest(activeRequestId = "same", expectedRequestId = "same"))
    }

    @Test
    fun nextQuestionDraftStartsWithNoImagesOrText() {
        val draft = QuestionDraft(sessionId = "next-session")
        assertTrue(draft.images.isEmpty())
        assertTrue(draft.text.isBlank())
        assertEquals(QuestionType.AUTO, draft.questionType)
        assertTrue(draft.sessionId.isNotBlank())
    }
}