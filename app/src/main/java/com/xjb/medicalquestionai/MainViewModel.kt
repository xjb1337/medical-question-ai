package com.xjb.medicalquestionai

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xjb.medicalquestionai.data.AnalysisMetrics
import com.xjb.medicalquestionai.data.AnalysisPreview
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.AnswerMode
import com.xjb.medicalquestionai.data.ApiException
import com.xjb.medicalquestionai.data.AppDatabase
import com.xjb.medicalquestionai.data.AppSettings
import com.xjb.medicalquestionai.data.CaptureTarget
import com.xjb.medicalquestionai.data.ChatStreamEvent
import com.xjb.medicalquestionai.data.DeepSeekClient
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.FollowUp
import com.xjb.medicalquestionai.data.FollowUpRepository
import com.xjb.medicalquestionai.data.ImageEditPolicy
import com.xjb.medicalquestionai.data.ImageStore
import com.xjb.medicalquestionai.data.ModelCapability
import com.xjb.medicalquestionai.data.ModelCatalog
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionAnalyzer
import com.xjb.medicalquestionai.data.QuestionDraft
import com.xjb.medicalquestionai.data.QuestionRepository
import com.xjb.medicalquestionai.data.QuestionType
import com.xjb.medicalquestionai.data.SecureKeyStore
import com.xjb.medicalquestionai.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.get(application)
    private val repository = QuestionRepository(database.questionDao())
    private val followUpRepository = FollowUpRepository(database.followUpDao())
    private val imageStore = ImageStore(application)
    private val keyStore = SecureKeyStore(application)
    private val settingsRepository = SettingsRepository(application, keyStore)
    private val client = DeepSeekClient()
    private val analyzer = QuestionAnalyzer(client, imageStore)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _draft = MutableStateFlow(QuestionDraft())
    val draft: StateFlow<QuestionDraft> = _draft.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _isCancelling = MutableStateFlow(false)
    val isCancelling: StateFlow<Boolean> = _isCancelling.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _analysisTask = MutableStateFlow<AnalysisTaskState?>(null)
    val analysisTask: StateFlow<AnalysisTaskState?> = _analysisTask.asStateFlow()

    val analysisStages: StateFlow<List<AnalysisStage>> = _analysisTask
        .map { it?.stages.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val answerPreview: StateFlow<AnalysisPreview?> = _analysisTask
        .map { it?.preview }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _analysisElapsedMs = MutableStateFlow(0L)
    val analysisElapsedMs: StateFlow<Long> = _analysisElapsedMs.asStateFlow()

    private val _draftNotice = MutableStateFlow<String?>(null)
    val draftNotice: StateFlow<String?> = _draftNotice.asStateFlow()

    private val _isTestingKey = MutableStateFlow(false)
    val isTestingKey: StateFlow<Boolean> = _isTestingKey.asStateFlow()

    private val _isCheckingConnection = MutableStateFlow(false)
    val isCheckingConnection: StateFlow<Boolean> = _isCheckingConnection.asStateFlow()

    private val _isVerifyingModel = MutableStateFlow(false)
    val isVerifyingModel: StateFlow<Boolean> = _isVerifyingModel.asStateFlow()

    private val _retryingIds = MutableStateFlow<Set<String>>(emptySet())
    val retryingIds: StateFlow<Set<String>> = _retryingIds.asStateFlow()

    private val _followUpStreams = MutableStateFlow<Map<String, FollowUpStreamState>>(emptyMap())
    val followUpStreams: StateFlow<Map<String, FollowUpStreamState>> = _followUpStreams.asStateFlow()

    private val followUpJobs = mutableMapOf<String, Job>()

    val followUpSendingIds: StateFlow<Set<String>> = _followUpStreams
        .map { streams -> streams.filterValues { it.sending }.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _followUpError = MutableStateFlow<String?>(null)
    val followUpError: StateFlow<String?> = _followUpError.asStateFlow()

    private val _historyQuery = MutableStateFlow("")
    val historyQuery: StateFlow<String> = _historyQuery.asStateFlow()

    val history: StateFlow<List<Question>> = combine(
        repository.observeAll(),
        _historyQuery,
        settings
    ) { all, query, config ->
        val visible = if (config.saveHistory) all else all.filter { it.isFavorite }
        if (query.isBlank()) visible else visible.filter { question ->
            question.questionText.contains(query, ignoreCase = true) ||
                question.result?.answer?.joinToString().orEmpty().contains(query, ignoreCase = true) ||
                question.result?.answerText.orEmpty().contains(query, ignoreCase = true) ||
                question.result?.recognizedQuestion.orEmpty().contains(query, ignoreCase = true) ||
                question.result?.keyPoints?.joinToString().orEmpty().contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var draftImageSource: String? = null
    private var analysisJob: Job? = null
    private var elapsedJob: Job? = null
    private var activeRequestId: String? = null
    private var activeQuestionId: String? = null
    private var cancelRequested = false

    init {
        // 进程被杀后，数据库里遗留的 analyzing/receiving/validating 不能继续显示为“正在解析”。
        viewModelScope.launch {
            runCatching { repository.markInterrupted() }
        }
    }

    fun questionFlow(id: String): Flow<Question?> = repository.observeById(id)

    fun followUps(questionId: String, answerRevision: Long): Flow<List<FollowUp>> =
        followUpRepository.observeForQuestion(questionId, answerRevision)

    fun updateQuestionType(type: QuestionType) {
        _draft.value = _draft.value.copy(questionType = type)
    }

    fun updateDraftText(text: String) {
        _draft.value = _draft.value.copy(text = text)
    }

    fun setPrivacyConfirmed(value: Boolean) {
        _draft.value = _draft.value.copy(privacyConfirmed = value)
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value) return
        val remaining = ImageStore.MAX_IMAGES - _draft.value.images.size
        if (remaining <= 0) {
            _draftNotice.value = "最多添加 ${ImageStore.MAX_IMAGES} 张图片"
            return
        }
        draftImageSource = "gallery"
        _isImporting.value = true
        viewModelScope.launch {
            var failed = 0
            val imported = mutableListOf<DraftImage>()
            for (uri in uris.take(remaining)) {
                try {
                    imported += imageStore.importImage(uri)
                } catch (e: Exception) {
                    failed++
                }
            }
            _draft.value = _draft.value.copy(
                images = _draft.value.images + imported,
                privacyConfirmed = if (imported.isEmpty()) _draft.value.privacyConfirmed else false
            )
            _isImporting.value = false
            if (uris.size > remaining) {
                _draftNotice.value = "最多添加 ${ImageStore.MAX_IMAGES} 张图片，已忽略多余图片"
            } else if (failed > 0) {
                _draftNotice.value = "$failed 张图片读取失败，请换一张或重新拍摄"
            }
        }
    }

    fun addCameraImage(uri: Uri, captureFile: java.io.File, onComplete: () -> Unit) {
        if (_isImporting.value) return
        if (_draft.value.images.size >= ImageStore.MAX_IMAGES) {
            _draftNotice.value = "最多添加 ${ImageStore.MAX_IMAGES} 张图片"
            return
        }
        draftImageSource = "camera"
        _isImporting.value = true
        viewModelScope.launch {
            try {
                val image = imageStore.importImage(uri)
                _draft.value = _draft.value.copy(
                    images = _draft.value.images + image,
                    privacyConfirmed = false
                )
                onComplete()
            } catch (e: Exception) {
                _draftNotice.value = "照片读取失败：${e.message ?: "请重新拍摄"}"
            } finally {
                imageStore.deleteCapture(captureFile)
                _isImporting.value = false
            }
        }
    }

    fun createCaptureTarget(): CaptureTarget = imageStore.createCaptureTarget()

    fun deleteCapture(file: java.io.File) = imageStore.deleteCapture(file)

    fun removeImage(id: String) {
        if (_isAnalyzing.value) {
            _draftNotice.value = "解析过程中不能修改题目图片"
            return
        }
        val image = _draft.value.images.firstOrNull { it.id == id } ?: return
        imageStore.delete(image.path)
        _draft.value = _draft.value.copy(images = _draft.value.images.filterNot { it.id == id })
    }

    fun moveImage(id: String, delta: Int) {
        val images = _draft.value.images.toMutableList()
        val index = images.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, images.lastIndex)
        if (target == index) return
        val item = images.removeAt(index)
        images.add(target, item)
        _draft.value = _draft.value.copy(images = images)
    }

    fun rotateImage(id: String, degrees: Int) {
        if (_isAnalyzing.value) {
            _draftNotice.value = "解析过程中不能修改题目图片"
            return
        }
        val image = _draft.value.images.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                val meta = imageStore.rotate(image.path, degrees)
                _draft.value = _draft.value.copy(images = _draft.value.images.map {
                    if (it.id == id) it.copy(width = meta.width, height = meta.height, sizeBytes = meta.sizeBytes) else it
                })
            } catch (e: Exception) {
                _draftNotice.value = "旋转失败：${e.message ?: "请重试"}"
            }
        }
    }

    fun cropImage(id: String, rect: android.graphics.RectF, keepOriginal: Boolean = true) {
        if (_isAnalyzing.value) {
            _draftNotice.value = "解析过程中不能修改题目图片"
            return
        }
        val image = _draft.value.images.firstOrNull { it.id == id } ?: return
        if (keepOriginal && !ImageEditPolicy.canAddImage(_draft.value.images, ImageStore.MAX_IMAGES)) {
            _draftNotice.value = "最多添加 ${ImageStore.MAX_IMAGES} 张图片，请先删除一张再裁剪"
            return
        }
        viewModelScope.launch {
            try {
                if (keepOriginal) {
                    val cropped = imageStore.cropCopy(image.path, rect)
                    if (!ImageEditPolicy.canAddImage(_draft.value.images, ImageStore.MAX_IMAGES)) {
                        imageStore.delete(cropped.path)
                        _draftNotice.value = "最多添加 ${ImageStore.MAX_IMAGES} 张图片，请先删除一张再裁剪"
                        return@launch
                    }
                    val images = ImageEditPolicy.insertAfter(_draft.value.images, id, cropped)
                    if (images == null) {
                        imageStore.delete(cropped.path)
                        return@launch
                    }
                    _draft.value = _draft.value.copy(images = images, privacyConfirmed = false)
                } else {
                    val meta = imageStore.cropInPlace(image.path, rect)
                    _draft.value = _draft.value.copy(images = _draft.value.images.map {
                        if (it.id == id) it.copy(width = meta.width, height = meta.height, sizeBytes = meta.sizeBytes) else it
                    })
                }
            } catch (e: Exception) {
                _draftNotice.value = "裁剪失败：${e.message ?: "请重试"}"
            }
        }
    }

    /**
     * 点击解析后先把任务落库并立即导航到结果页，网络请求在结果页继续跑。
     * 如果建任务失败，停留首页并保留草稿。
     */
    fun startAnalysis(onNavigate: (String) -> Unit) {
        val clickedAt = SystemClock.elapsedRealtime()
        val snapshot = _draft.value
        if (_isAnalyzing.value || _retryingIds.value.isNotEmpty()) {
            _analysisError.value = "已有解析任务正在进行，请先取消或等待完成"
            return
        }
        if (snapshot.text.isBlank() && snapshot.images.isEmpty()) {
            _analysisError.value = "请先输入题干或添加题目图片"
            return
        }
        if (snapshot.images.isNotEmpty()) {
            imageCapabilityError(settings.value.model)?.let {
                _analysisError.value = it
                return
            }
        }
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _analysisError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
        val mode = settings.value.answerMode
        val questionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val question = Question(
            id = questionId,
            createdAt = now,
            questionType = snapshot.questionType.storageValue,
            source = when {
                snapshot.images.isEmpty() -> "text"
                snapshot.text.isBlank() -> draftImageSource ?: "gallery"
                else -> "mixed"
            },
            questionText = snapshot.text,
            imagePaths = snapshot.images.map { it.path },
            status = "analyzing",
            result = null,
            model = settings.value.model,
            baseUrl = settings.value.baseUrl,
            errorMessage = null,
            isFavorite = false,
            isEphemeral = !settings.value.saveHistory,
            mode = mode.storageValue,
            promptVersion = QuestionAnalyzer.PROMPT_VERSION,
            inputRevision = now,
            answerInputRevision = 0
        )
        _analysisError.value = null
        _isAnalyzing.value = true
        _isCancelling.value = false
        _analysisElapsedMs.value = 0L
        analysisJob = viewModelScope.launch {
            try {
                repository.insert(question)
            } catch (e: Exception) {
                _analysisError.value = "无法创建解析任务：${e.message ?: "请重试"}"
                _isAnalyzing.value = false
                return@launch
            }
            _draft.value = QuestionDraft()
            draftImageSource = null
            onNavigate(questionId)
            runAnalysis(question, mode, clickedAt = clickedAt)
        }
    }

    private suspend fun runAnalysis(
        question: Question,
        mode: AnswerMode,
        previous: Question? = null,
        clickedAt: Long = SystemClock.elapsedRealtime()
    ) {
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _analysisError.value = "尚未配置 API 密钥，请先到设置中填写"
            _isAnalyzing.value = false
            return
        }
        val requestId = UUID.randomUUID().toString()
        activeRequestId = requestId
        activeQuestionId = question.id
        cancelRequested = false
        val startedAt = clickedAt
        val titles = stageTitles(mode)
        val requestModel = question.model.ifBlank { settings.value.model }
        val requestBaseUrl = question.baseUrl.ifBlank { settings.value.baseUrl }
        updateTask(
            AnalysisTaskState(
                requestId = requestId,
                questionId = question.id,
                status = AnalysisTaskStatus.PREPARING,
                stages = titles.mapIndexed { index, title ->
                    AnalysisStage(title, if (index == 0) AnalysisStageStatus.RUNNING else AnalysisStageStatus.PENDING)
                },
                startedAtElapsedRealtime = startedAt,
                mode = mode
            )
        )
        startElapsedTicker(startedAt)
        // 重答时必须保留旧结果和旧元数据，直到新结果真正完成；请求本身使用新的目标模型/地址/模式。
        var current = previous?.copy() ?: question.copy(
            status = "analyzing",
            model = requestModel,
            baseUrl = requestBaseUrl,
            mode = mode.storageValue,
            promptVersion = QuestionAnalyzer.PROMPT_VERSION,
            errorMessage = null,
            durationMs = 0
        )
        if (previous == null) {
            try {
                repository.update(current)
            } catch (e: Exception) {
                _analysisError.value = "无法创建解析任务：${e.message ?: "本地存储不可用"}"
                _isAnalyzing.value = false
                return
            }
        }

        // B0 埋点：只写 ID、耗时和用量，不写题干、答案、图片或密钥。
        fun logMetrics(
            resultStatus: String,
            durationMs: Long,
            metricsOverride: AnalysisMetrics? = null
        ) {
            // 失败/取消/中断不能复用上一次成功结果的 usage，只有本次 completed 才记录用量。
            val metrics = metricsOverride ?: if (resultStatus == "completed") current.result?.metrics else null
            val usage = if (resultStatus == "completed") current.result?.usage else null
            Log.i(
                "AnalysisMetrics",
                listOf(
                    "run_id=$requestId",
                    "question_id=${current.id}",
                    "build=${BuildConfig.VERSION_NAME}",
                    "request_model=$requestModel",
                    "response_model=${metrics?.responseModel.orEmpty()}",
                    "model=${current.model}",
                    "mode=${mode.storageValue}",
                    "answer_mode=${metrics?.answerMode ?: mode.storageValue}",
                    "thinking=${metrics?.thinkingEnabled ?: (mode.thinkingEnabled && ModelCatalog.capability(requestModel) != ModelCapability.UNKNOWN)}",
                    "streamed=${metrics?.streamed ?: false}",
                    "finish_reason=${metrics?.finishReason.orEmpty()}",
                    "transport_closed=${metrics?.transportClosed ?: false}",
                    "save_status=${metrics?.saveStatus.orEmpty()}",
                    "prompt_version=${QuestionAnalyzer.PROMPT_VERSION}",
                    "kind=${current.source}",
                    "image_count=${current.imagePaths.size}",
                    "image_ready_ms=${metrics?.clickToImageReadyMs?.toString().orEmpty()}",
                    "request_ready_ms=${metrics?.clickToRequestReadyMs?.toString().orEmpty()}",
                    "headers_ms=${metrics?.clickToHeadersMs?.toString().orEmpty()}",
                    "first_event_ms=${metrics?.clickToFirstEventMs?.toString().orEmpty()}",
                    "first_content_ms=${metrics?.clickToFirstContentMs?.toString().orEmpty()}",
                    "first_answer_ms=${metrics?.clickToFirstAnswerMs?.toString().orEmpty()}",
                    "validation_ms=${metrics?.validationMs?.toString().orEmpty()}",
                    "save_ms=${metrics?.saveMs?.toString().orEmpty()}",
                    "total_ms=${metrics?.totalMs ?: durationMs}",
                    "prompt_tokens=${usage?.promptTokens ?: ""}",
                    "completion_tokens=${usage?.completionTokens ?: ""}",
                    "reasoning_tokens=",
                    "result_status=$resultStatus"
                ).joinToString(",")
            )
        }

        suspend fun moveToDbStatus(status: String) {
            // 重答期间数据库保留旧完成记录，避免失败/取消时覆盖旧结果与元数据。
            if (previous != null) return
            if (current.status == status) return
            current = current.copy(status = status)
            runCatching { repository.update(current) }
        }

        try {
            val draft = QuestionDraft(
                questionType = QuestionType.fromStorage(current.questionType),
                text = current.questionText,
                images = current.imagePaths.map { path ->
                    DraftImage(id = path, path = path, width = 0, height = 0, sizeBytes = 0)
                },
                privacyConfirmed = true
            )
            analyzer.analyzeStream(
                draft = draft,
                apiKey = apiKey,
                model = requestModel,
                baseUrl = requestBaseUrl,
                mode = mode,
                customModelSupportsImages = settings.value.customModelSupportsImages,
                customModelStreaming = settings.value.customModelStreaming,
                clickedAtElapsedRealtime = clickedAt
            ).collect { event ->
                if (activeRequestId != requestId) return@collect
                when (event) {
                    is com.xjb.medicalquestionai.data.AnalysisEvent.WaitingForModel -> {
                        updateTaskStatus(AnalysisTaskStatus.WAITING, runningIndex = 2, titles = titles)
                        moveToDbStatus("receiving")
                    }
                    is com.xjb.medicalquestionai.data.AnalysisEvent.Stage -> {
                        val mapped = when (event.index) {
                            0 -> AnalysisTaskStatus.PREPARING
                            1 -> AnalysisTaskStatus.UPLOADING
                            2 -> AnalysisTaskStatus.RECEIVING
                            else -> AnalysisTaskStatus.VALIDATING
                        }
                        updateTaskStages(mapped, event.index, titles)
                        moveToDbStatus(
                            when (event.index) {
                                0, 1 -> "analyzing"
                                2 -> "receiving"
                                else -> "validating"
                            }
                        )
                    }
                    is com.xjb.medicalquestionai.data.AnalysisEvent.Preview -> {
                        _analysisTask.update { it?.copy(preview = event.preview) }
                    }
                    is com.xjb.medicalquestionai.data.AnalysisEvent.Reasoning -> {
                        _analysisTask.update { current ->
                            current?.copy(
                                reasoningPreview = (current.reasoningPreview + event.text).takeLast(MAX_REASONING_PREVIEW)
                            )
                        }
                    }
                    is com.xjb.medicalquestionai.data.AnalysisEvent.Completed -> {
                        val completionAt = SystemClock.elapsedRealtime()
                        val duration = completionAt - clickedAt
                        val resultForSave = event.result.copy(
                            metrics = event.result.metrics?.copy(
                                saveStatus = "saving",
                                totalMs = duration
                            )
                        )
                        val base = previous ?: current
                        val completed = base.copy(
                            status = "completed",
                            result = resultForSave,
                            errorMessage = null,
                            model = requestModel,
                            baseUrl = requestBaseUrl,
                            mode = mode.storageValue,
                            durationMs = duration,
                            promptVersion = QuestionAnalyzer.PROMPT_VERSION,
                            answerInputRevision = current.inputRevision,
                            answerRevision = maxOf(base.answerRevision, current.answerRevision) + 1
                        )
                        var savedQuestion = completed
                        val saveFailure = try {
                            val saveStart = SystemClock.elapsedRealtime()
                            repository.update(completed)
                            val saveEnd = SystemClock.elapsedRealtime()
                            savedQuestion = completed.copy(
                                durationMs = saveEnd - clickedAt,
                                result = completed.result?.copy(
                                    metrics = completed.result.metrics?.copy(
                                        saveStatus = "saved",
                                        totalMs = saveEnd - clickedAt,
                                        saveMs = saveEnd - saveStart
                                    )
                                )
                            )
                            // 答案已经由上一笔写入落盘；这里只补保存耗时，失败不应把已完成结果误报为保存失败。
                            runCatching { repository.update(savedQuestion) }
                            null
                        } catch (e: Exception) {
                            e
                        }
                        if (saveFailure != null) {
                            val message = "结果已生成，但保存到本地数据库失败：${saveFailure.message ?: "存储空间可能不足"}。请检查存储空间后重试。"
                            if (previous == null) {
                                current = current.copy(status = "failed", errorMessage = message, durationMs = duration)
                                runCatching { repository.update(current) }
                            } else {
                                // 重答时不覆盖旧完成记录；旧结果仍保留在数据库，页面显示本次保存失败。
                                current = previous.copy()
                            }
                            _analysisElapsedMs.value = duration
                            logMetrics("save_failed", duration, resultForSave.metrics?.copy(saveStatus = "failed"))
                            updateTaskStatus(
                                AnalysisTaskStatus.FAILED,
                                titles = titles,
                                error = message,
                                failedIndex = currentStageIndex()
                            )
                            _analysisError.value = message
                            stopElapsedTicker()
                            _isAnalyzing.value = false
                            activeRequestId = null
                            activeQuestionId = null
                        } else {
                            current = savedQuestion
                            // 新答案不再对应旧的追问上下文，避免追问引用修改前的答案。
                            if (previous != null) {
                                runCatching { followUpRepository.deleteForQuestion(current.id) }
                            }
                            _analysisElapsedMs.value = current.durationMs
                            logMetrics("completed", current.durationMs)
                            updateTaskStatus(AnalysisTaskStatus.COMPLETED, titles = titles, allDone = true, preview = null)
                            stopElapsedTicker()
                            _isAnalyzing.value = false
                            activeRequestId = null
                            activeQuestionId = null
                        }
                    }
                    is com.xjb.medicalquestionai.data.AnalysisEvent.Failed -> {
                        val duration = SystemClock.elapsedRealtime() - startedAt
                        if (previous != null) {
                            // 重答失败：数据库保留旧结果、旧模型、旧时长等元数据。
                            current = previous.copy()
                        } else {
                            current = current.copy(
                                status = "failed",
                                errorMessage = event.message,
                                durationMs = duration
                            )
                            runCatching { repository.update(current) }
                        }
                        _analysisElapsedMs.value = duration
                        logMetrics("failed", duration)
                        updateTaskStatus(
                            AnalysisTaskStatus.FAILED,
                            titles = titles,
                            error = event.message,
                            failedIndex = currentStageIndex()
                        )
                        _analysisError.value = event.message
                        stopElapsedTicker()
                        _isAnalyzing.value = false
                        activeRequestId = null
                        activeQuestionId = null
                    }
                }
            }
        } catch (e: CancellationException) {
            if (activeRequestId == requestId) {
                val cancelledByUser = cancelRequested
                val duration = SystemClock.elapsedRealtime() - startedAt
                logMetrics(if (cancelledByUser) "cancelled" else "interrupted", duration)
                withContext(NonCancellable) {
                    if (previous == null) {
                        runCatching {
                            repository.update(
                                current.copy(
                                    status = if (cancelledByUser) "cancelled" else "interrupted",
                                    errorMessage = if (cancelledByUser) {
                                        "已取消解析，可回到原题重新解析"
                                    } else {
                                        "解析被中断，请重新解析"
                                    }
                                )
                            )
                        }
                    } else {
                        // 重答被取消/中断：旧完成记录及其元数据保持原样。
                        current = previous.copy()
                    }
                }
                updateTaskStatus(
                    if (cancelledByUser) AnalysisTaskStatus.CANCELLED else AnalysisTaskStatus.INTERRUPTED,
                    titles = titles,
                    error = if (cancelledByUser) "已取消" else "已中断",
                    failedIndex = currentStageIndex()
                )
                stopElapsedTicker()
                _isAnalyzing.value = false
                _isCancelling.value = false
                activeRequestId = null
                activeQuestionId = null
            }
            throw e
        } catch (e: Exception) {
            if (activeRequestId == requestId) {
                val message = e.message ?: "分析失败，请稍后重试"
                val duration = SystemClock.elapsedRealtime() - startedAt
                logMetrics("failed", duration)
                withContext(NonCancellable) {
                    if (previous == null) {
                        current = current.copy(status = "failed", errorMessage = message, durationMs = duration)
                        runCatching { repository.update(current) }
                    } else {
                        // 重答异常：旧结果与元数据保持原样。
                        current = previous.copy()
                    }
                }
                updateTaskStatus(AnalysisTaskStatus.FAILED, titles = titles, error = message, failedIndex = currentStageIndex())
                _analysisError.value = message
                stopElapsedTicker()
                _isAnalyzing.value = false
                _isCancelling.value = false
                activeRequestId = null
                activeQuestionId = null
            }
        }
    }

    fun cancelAnalysis() {
        if (!_isAnalyzing.value) return
        cancelRequested = true
        _isCancelling.value = true
        analysisJob?.cancel()
    }

    fun retryAnalysis(id: String, onSuccess: (() -> Unit)? = null) {
        val clickedAt = SystemClock.elapsedRealtime()
        if (id in _retryingIds.value) return
        if (_isAnalyzing.value || _retryingIds.value.isNotEmpty()) {
            _analysisError.value = "已有解析任务正在进行，请先取消或等待完成"
            return
        }
        if (followUpJobs[id]?.isActive == true) {
            _analysisError.value = "这道题的追问仍在生成，请先停止追问再重答"
            return
        }
        // 先占住单任务锁，再做重答前置检查，避免与 startAnalysis/其他重答并发。
        _isAnalyzing.value = true
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _isAnalyzing.value = false
            _analysisError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
        val mode = settings.value.answerMode
        _retryingIds.update { it + id }
        _analysisError.value = null
        _isCancelling.value = false
        _analysisElapsedMs.value = 0L
        analysisJob = viewModelScope.launch {
            try {
                val previous = repository.getById(id) ?: return@launch
                val targetModel = settings.value.model
                imageCapabilityError(targetModel)?.let { message ->
                    if (previous.imagePaths.isNotEmpty()) {
                        _analysisError.value = message
                        return@launch
                    }
                }
                runAnalysis(
                    previous.copy(
                        model = targetModel,
                        baseUrl = settings.value.baseUrl,
                        mode = mode.storageValue
                    ),
                    mode,
                    previous = previous,
                    clickedAt = clickedAt
                )
                onSuccess?.invoke()
            } finally {
                _retryingIds.update { it - id }
                if (activeRequestId == null) {
                    _isAnalyzing.value = false
                    _isCancelling.value = false
                }
            }
        }
    }

    fun updateQuestionTextAndRetry(
        id: String,
        newText: String,
        newType: QuestionType? = null,
        onSaved: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) {
            val message = "题目文字不能为空，如需保留图片请至少补充关键词"
            _analysisError.value = message
            onError(message)
            return
        }
        if (_isAnalyzing.value && activeQuestionId == id) {
            val message = "解析进行中，暂时不能修改题干；请先取消或等待完成"
            _analysisError.value = message
            onError(message)
            return
        }
        viewModelScope.launch {
            val question = repository.getById(id)
            if (question == null) {
                val message = "题目不存在或已被删除"
                _analysisError.value = message
                onError(message)
                return@launch
            }
            val updated = question.copy(
                questionText = trimmed,
                questionType = (newType ?: QuestionType.fromStorage(question.questionType)).storageValue,
                inputRevision = question.inputRevision + 1
            )
            try {
                repository.update(updated)
            } catch (e: Exception) {
                val message = "题目修改保存失败：${e.message ?: "本地存储不可用"}，请检查存储空间后重试"
                _analysisError.value = message
                onError(message)
                return@launch
            }
            onSaved()
            retryAnalysis(id)
        }
    }

    fun addImagesToQuestion(id: String, uris: List<Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty()) {
            onDone("没有选择图片")
            return
        }
        if (_isAnalyzing.value && activeQuestionId == id) {
            onDone("解析进行中，暂时不能修改题目图片；请先取消或等待完成")
            return
        }
        viewModelScope.launch {
            val question = repository.getById(id)
            if (question == null) {
                onDone("题目不存在或已被删除")
                return@launch
            }
            val remaining = ImageStore.MAX_IMAGES - question.imagePaths.size
            if (remaining <= 0) {
                onDone("这道题已经有 ${ImageStore.MAX_IMAGES} 张图片")
                return@launch
            }
            var failed = 0
            val added = mutableListOf<String>()
            for (uri in uris.take(remaining)) {
                try {
                    added += imageStore.importImage(uri).path
                } catch (e: Exception) {
                    failed++
                }
            }
            if (added.isNotEmpty()) {
                try {
                    repository.update(
                        question.copy(
                            imagePaths = question.imagePaths + added,
                            inputRevision = question.inputRevision + 1
                        )
                    )
                } catch (e: Exception) {
                    imageStore.deleteAll(added)
                    onDone("图片已读取，但保存到本地失败：${e.message ?: "存储空间可能不足"}，请重试")
                    return@launch
                }
            }
            val message = when {
                added.isEmpty() && failed > 0 -> "图片读取失败，请换一张或重新拍摄"
                uris.size > remaining -> "已添加 ${added.size} 张；最多保留 ${ImageStore.MAX_IMAGES} 张，已忽略多余图片"
                failed > 0 -> "已添加 ${added.size} 张，${failed} 张读取失败"
                else -> "已添加 ${added.size} 张图片，可点击“重新解析”"
            }
            onDone(message)
        }
    }

    fun sendFollowUp(questionId: String, text: String, attachImages: Boolean, onAccepted: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            _followUpError.value = "请输入追问内容"
            return
        }
        if (followUpJobs[questionId]?.isActive == true) return
        if (_isAnalyzing.value || _retryingIds.value.isNotEmpty()) {
            _followUpError.value = "解析正在生成，请等当前任务完成或先取消后再追问"
            return
        }
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _followUpError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
        _followUpStreams.update {
            it + (questionId to FollowUpStreamState(
                questionId = questionId,
                sending = true,
                startedAtElapsedRealtime = SystemClock.elapsedRealtime()
            ))
        }
        _followUpError.value = null
        val job = viewModelScope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage = com.xjb.medicalquestionai.data.Usage()
            var sawDone = false
            var finishReason: String? = null
            var answerRevision = 0L
            try {
                val question = repository.getById(questionId)
                    ?: throw ApiException("题目不存在或已被删除")
                if (question.isAnswerStale) {
                    throw ApiException("题目已修改，旧答案的追问不会继续。请先重新解析。")
                }
                answerRevision = question.answerRevision
                val userItem = FollowUp(
                    id = UUID.randomUUID().toString(),
                    questionId = questionId,
                    role = "user",
                    content = trimmed,
                    createdAt = System.currentTimeMillis(),
                    answerRevision = answerRevision
                )
                followUpRepository.insert(userItem)
                onAccepted?.invoke()
                val previous = followUpRepository.getForQuestion(questionId, answerRevision)
                    .filterNot { it.id == userItem.id }
                analyzer.followUpStream(
                    question = question,
                    previous = previous,
                    followUpText = trimmed,
                    attachImages = attachImages,
                    apiKey = apiKey,
                    model = settings.value.model,
                    baseUrl = settings.value.baseUrl,
                    mode = settings.value.answerMode,
                    customModelSupportsImages = settings.value.customModelSupportsImages,
                    customModelStreaming = settings.value.customModelStreaming
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.ContentDelta -> {
                            content.append(event.text)
                            _followUpStreams.update {
                                it[questionId]?.copy(content = content.toString())?.let { stream ->
                                    it + (questionId to stream)
                                } ?: it
                            }
                        }
                        is ChatStreamEvent.ReasoningDelta -> {
                            reasoning.append(event.text)
                            _followUpStreams.update {
                                it[questionId]?.copy(reasoningPreview = reasoning.toString().takeLast(MAX_REASONING_PREVIEW))
                                    ?.let { stream -> it + (questionId to stream) } ?: it
                            }
                        }
                        is ChatStreamEvent.UsageUpdate -> usage = event.usage
                        is ChatStreamEvent.Finished -> finishReason =
                            event.finishReason?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                        is ChatStreamEvent.StreamDone -> sawDone = true
                        is ChatStreamEvent.ResponseStarted, is ChatStreamEvent.ResponseModel -> Unit
                    }
                }
                val answer = content.toString().trim()
                QuestionAnalyzer.validateFinishReason(finishReason, "追问")
                if (!sawDone) {
                    throw ApiException("追问连接提前中断，未收到完整结束标记，请重试。")
                }
                if (answer.isBlank()) throw ApiException("模型没有返回内容，请重试")
                followUpRepository.insert(
                    FollowUp(
                        id = UUID.randomUUID().toString(),
                        questionId = questionId,
                        role = "assistant",
                        content = answer,
                        createdAt = System.currentTimeMillis(),
                        usage = usage,
                        answerRevision = answerRevision
                    )
                )
            } catch (e: CancellationException) {
                val partial = content.toString().trim()
                if (partial.isNotBlank()) {
                    withContext(NonCancellable) {
                        runCatching {
                            followUpRepository.insert(
                                FollowUp(
                                    id = UUID.randomUUID().toString(),
                                    questionId = questionId,
                                    role = "assistant",
                                    content = "$partial\n\n（已停止生成）",
                                    createdAt = System.currentTimeMillis(),
                                    usage = usage,
                                    answerRevision = answerRevision
                                )
                            )
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "追问失败，请稍后重试"
                runCatching {
                    followUpRepository.insert(
                        FollowUp(
                            id = UUID.randomUUID().toString(),
                            questionId = questionId,
                            role = "assistant",
                            content = "（追问失败：$message）",
                            createdAt = System.currentTimeMillis(),
                            failed = true,
                            answerRevision = answerRevision
                        )
                    )
                }
                _followUpError.value = message
            } finally {
                _followUpStreams.update { it - questionId }
            }
        }
        followUpJobs[questionId] = job
        job.invokeOnCompletion { followUpJobs.remove(questionId) }
    }

    fun cancelFollowUp(questionId: String) {
        followUpJobs[questionId]?.cancel()
    }

    fun consumeFollowUpError() {
        _followUpError.value = null
    }

    fun deleteQuestion(id: String) {
        val runningAnalysis = if (activeQuestionId == id) analysisJob else null
        val runningFollowUp = followUpJobs[id]
        if (runningAnalysis != null) cancelAnalysis()
        runningFollowUp?.cancel()
        viewModelScope.launch {
            // 等待正在取消的请求彻底释放图片引用后再删文件，避免后台仍读取已删除文件。
            runningAnalysis?.join()
            runningFollowUp?.join()
            val question = repository.getById(id)
            followUpRepository.deleteForQuestion(id)
            repository.delete(id)
            question?.imagePaths?.let(imageStore::deleteAll)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            val runningAnalysis = analysisJob
            if (runningAnalysis?.isActive == true) {
                cancelRequested = true
                _isCancelling.value = true
                runningAnalysis.cancel()
                val stopped = withTimeoutOrNull(HISTORY_STOP_TIMEOUT_MS) {
                    runningAnalysis.join()
                    true
                } ?: false
                if (!stopped) {
                    // 不能确认后台已停止读取图片：放弃删除，并解除界面上的加载态，避免永久 loading。
                    _isAnalyzing.value = false
                    _isCancelling.value = false
                    _draftNotice.value = "解析任务未能及时停止，请稍后重试清空历史"
                    return@launch
                }
            }
            val runningFollowUps = followUpJobs.values.toList()
            runningFollowUps.forEach { it.cancel() }
            val followUpsStopped = runningFollowUps.all { job ->
                withTimeoutOrNull(HISTORY_STOP_TIMEOUT_MS) {
                    job.join()
                    true
                } ?: false
            }
            if (!followUpsStopped || _isAnalyzing.value || followUpJobs.values.any { it.isActive }) {
                _draftNotice.value = "仍有追问未能停止，请稍后重试清空历史"
                return@launch
            }
            val all = repository.getAll()
            all.forEach { question -> imageStore.deleteAll(question.imagePaths) }
            followUpRepository.clearAll()
            repository.clearAll()
            _draftNotice.value = "历史记录已清空"
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            val question = repository.getById(id) ?: return@launch
            val nextFavorite = !question.isFavorite
            repository.update(
                question.copy(
                    isFavorite = nextFavorite,
                    isEphemeral = if (nextFavorite) false else question.isEphemeral
                )
            )
        }
    }

    fun setHistoryQuery(query: String) {
        _historyQuery.value = query
    }

    fun consumeAnalysisError() {
        _analysisError.value = null
    }

    fun consumeDraftNotice() {
        _draftNotice.value = null
    }

    fun saveApiKey(key: String, onDone: (String?) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            onDone("请输入 API 密钥")
            return
        }
        settingsRepository.saveApiKey(trimmed)
        onDone(null)
    }

    fun testSavedConnection(onDone: (String?) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥")
            return
        }
        if (_isTestingKey.value) return
        _isTestingKey.value = true
        viewModelScope.launch {
            val error = client.verifyConnection(
                baseUrl = settings.value.baseUrl,
                apiKey = key,
                model = settings.value.model
            )
            onDone(error)
            _isTestingKey.value = false
        }
    }

    /** 检查连接：只请求 /models，报告接口是否可达、Key 是否可用、当前模型是否在列表中。 */
    fun checkSavedConnection(onDone: (message: String, ok: Boolean) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥", false)
            return
        }
        if (_isCheckingConnection.value) return
        _isCheckingConnection.value = true
        viewModelScope.launch {
            try {
                val result = client.checkConnection(
                    baseUrl = settings.value.baseUrl,
                    apiKey = key,
                    model = settings.value.model
                )
                val message = when {
                    !result.modelListAvailable ->
                        "接口可达，密钥可用；接口未返回模型列表，无法确认当前模型是否存在，请点击“验证模型”确认模型可用"
                    result.selectedModelFound ->
                        "接口可达，密钥可用；当前模型可见"
                    else ->
                        "接口可达，但当前模型不在 /models 返回列表中，请检查模型名或账号权限"
                }
                onDone(message, result.modelListAvailable && result.selectedModelFound)
            } catch (e: ApiException) {
                onDone(e.message ?: "连接检查失败", false)
            } finally {
                _isCheckingConnection.value = false
            }
        }
    }

    /** 验证模型：发一次极短文字请求，确认当前模型可以真正生成内容。 */
    fun verifySavedModel(onDone: (message: String, ok: Boolean) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥", false)
            return
        }
        if (_isVerifyingModel.value) return
        _isVerifyingModel.value = true
        viewModelScope.launch {
            val error = client.verifyModel(
                baseUrl = settings.value.baseUrl,
                apiKey = key,
                model = settings.value.model
            )
            if (error == null) {
                val capability = when (ModelCatalog.capability(settings.value.model)) {
                    ModelCapability.SUPPORTED -> "支持图片"
                    ModelCapability.UNSUPPORTED -> "仅文字"
                    ModelCapability.UNKNOWN -> "图片能力未知"
                }
                onDone("模型验证通过（$capability）；图片链路仍需真机实拍验证", true)
            } else {
                onDone(error, false)
            }
            _isVerifyingModel.value = false
        }
    }

    fun removeApiKey() {
        settingsRepository.clearApiKey()
    }

    fun saveModel(model: String) {
        settingsRepository.saveModel(model)
    }

    fun saveAnswerMode(mode: AnswerMode) {
        settingsRepository.saveAnswerMode(mode)
    }

    fun saveBaseUrl(url: String): Boolean = settingsRepository.saveBaseUrl(url)

    fun saveHistoryPreference(enabled: Boolean) {
        settingsRepository.setSaveHistory(enabled)
    }

    fun dismissModelMigrationNote() {
        settingsRepository.dismissModelMigrationNote()
    }

    fun saveCustomModelSupportsImages(enabled: Boolean) {
        settingsRepository.saveCustomModelCapabilities(enabled, settings.value.customModelStreaming)
    }

    fun saveCustomModelStreaming(enabled: Boolean) {
        settingsRepository.saveCustomModelCapabilities(settings.value.customModelSupportsImages, enabled)
    }

    private fun imageCapabilityError(model: String): String? =
        if (ModelCatalog.supportsImages(model, settings.value.customModelSupportsImages)) null
        else when (ModelCatalog.capability(model)) {
            ModelCapability.SUPPORTED -> null
            ModelCapability.UNSUPPORTED ->
                "当前模型 ${ModelCatalog.displayName(model)} 不支持图片，请切换到 DeepSeek Flash 后解析"
            ModelCapability.UNKNOWN ->
                "当前自定义模型未声明支持图片。请关闭图片，或在“设置-高级接口”中开启“自定义模型支持图片”。"
        }

    private fun stageTitles(mode: AnswerMode): List<String> = listOf(
        "准备图片",
        "上传题目",
        if (mode == AnswerMode.DETAILED) "逐项分析" else "接收答案",
        "核对答案"
    )

    private fun startElapsedTicker(startedAt: Long) {
        stopElapsedTicker()
        elapsedJob = viewModelScope.launch {
            while (true) {
                _analysisElapsedMs.value = SystemClock.elapsedRealtime() - startedAt
                delay(100)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedJob?.cancel()
        elapsedJob = null
    }

    private fun updateTask(task: AnalysisTaskState) {
        _analysisTask.value = task
    }

    private fun updateTaskStatus(
        status: AnalysisTaskStatus,
        runningIndex: Int? = null,
        titles: List<String>,
        allDone: Boolean = false,
        preview: AnalysisPreview? = _analysisTask.value?.preview,
        error: String? = null,
        failedIndex: Int? = null
    ) {
        _analysisTask.update { current ->
            val base = current ?: return@update null
            val stages = titles.mapIndexed { index, title ->
                val stageStatus = when {
                    allDone -> AnalysisStageStatus.DONE
                    failedIndex != null && index == failedIndex -> AnalysisStageStatus.FAILED
                    runningIndex != null && index < runningIndex -> AnalysisStageStatus.DONE
                    runningIndex != null && index == runningIndex -> AnalysisStageStatus.RUNNING
                    else -> base.stages.getOrNull(index)?.status ?: AnalysisStageStatus.PENDING
                }
                AnalysisStage(title, stageStatus)
            }
            base.copy(status = status, stages = stages, preview = preview, errorMessage = error)
        }
    }

    private fun updateTaskStages(status: AnalysisTaskStatus, runningIndex: Int, titles: List<String>) {
        _analysisTask.update { current ->
            val base = current ?: return@update null
            val stages = titles.mapIndexed { index, title ->
                AnalysisStage(
                    title,
                    when {
                        index < runningIndex -> AnalysisStageStatus.DONE
                        index == runningIndex -> AnalysisStageStatus.RUNNING
                        else -> AnalysisStageStatus.PENDING
                    }
                )
            }
            base.copy(status = status, stages = stages)
        }
    }

    private fun currentStageIndex(): Int =
        _analysisTask.value?.stages?.indexOfFirst { it.status == AnalysisStageStatus.RUNNING }?.takeIf { it >= 0 } ?: 0

    companion object {
        private const val MAX_REASONING_PREVIEW = 4000
        private const val HISTORY_STOP_TIMEOUT_MS = 5_000L
    }
}