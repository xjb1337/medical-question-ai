package com.xjb.medicalquestionai

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.ApiException
import com.xjb.medicalquestionai.data.AppDatabase
import com.xjb.medicalquestionai.data.AppSettings
import com.xjb.medicalquestionai.data.CaptureTarget
import com.xjb.medicalquestionai.data.DeepSeekClient
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.FollowUp
import com.xjb.medicalquestionai.data.FollowUpRepository
import com.xjb.medicalquestionai.data.ImageEditPolicy
import com.xjb.medicalquestionai.data.ImageStore
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionAnalyzer
import com.xjb.medicalquestionai.data.QuestionDraft
import com.xjb.medicalquestionai.data.QuestionRepository
import com.xjb.medicalquestionai.data.QuestionType
import com.xjb.medicalquestionai.data.SecureKeyStore
import com.xjb.medicalquestionai.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _draftNotice = MutableStateFlow<String?>(null)
    val draftNotice: StateFlow<String?> = _draftNotice.asStateFlow()

    private val _isTestingKey = MutableStateFlow(false)
    val isTestingKey: StateFlow<Boolean> = _isTestingKey.asStateFlow()

    private val _retryingIds = MutableStateFlow<Set<String>>(emptySet())
    val retryingIds: StateFlow<Set<String>> = _retryingIds.asStateFlow()

    private val _followUpSendingIds = MutableStateFlow<Set<String>>(emptySet())
    val followUpSendingIds: StateFlow<Set<String>> = _followUpSendingIds.asStateFlow()

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

    fun questionFlow(id: String): Flow<Question?> = repository.observeById(id)

    fun followUps(questionId: String): Flow<List<FollowUp>> =
        followUpRepository.observeForQuestion(questionId)

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
            _draft.value = _draft.value.copy(images = _draft.value.images + imported)
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
                _draft.value = _draft.value.copy(images = _draft.value.images + image)
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
                    _draft.value = _draft.value.copy(images = images)
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

    fun startAnalysis(onSuccess: (String) -> Unit) {
        val snapshot = _draft.value
        if (_isAnalyzing.value) return
        if (!snapshot.privacyConfirmed) {
            _analysisError.value = "请先确认图片不含可识别的患者身份信息"
            return
        }
        if (snapshot.text.isBlank() && snapshot.images.isEmpty()) {
            _analysisError.value = "请先输入题干或添加题目图片"
            return
        }
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _analysisError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
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
            errorMessage = null,
            isFavorite = false,
            isEphemeral = !settings.value.saveHistory
        )
        _analysisError.value = null
        _isAnalyzing.value = true
        viewModelScope.launch {
            try {
                repository.insert(question)
                val result = analyzer.analyze(
                    draft = snapshot,
                    apiKey = apiKey,
                    model = settings.value.model,
                    baseUrl = settings.value.baseUrl
                )
                repository.update(
                    question.copy(
                        status = "completed",
                        result = result,
                        errorMessage = null
                    )
                )
                _draft.value = QuestionDraft()
                draftImageSource = null
                _isAnalyzing.value = false
                onSuccess(questionId)
            } catch (e: Exception) {
                val message = e.message ?: "分析失败，请稍后重试"
                runCatching {
                    repository.update(question.copy(status = "failed", errorMessage = message))
                }
                _analysisError.value = message
                _isAnalyzing.value = false
            }
        }
    }

    fun retryAnalysis(id: String, onSuccess: (() -> Unit)? = null) {
        if (id in _retryingIds.value) return
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _analysisError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
        _retryingIds.value = _retryingIds.value + id
        viewModelScope.launch {
            val question = repository.getById(id)
            if (question == null) {
                _retryingIds.value = _retryingIds.value - id
                return@launch
            }
            try {
                repository.update(question.copy(status = "analyzing", errorMessage = null))
                followUpRepository.deleteForQuestion(id)
                val draft = QuestionDraft(
                    questionType = QuestionType.fromStorage(question.questionType),
                    text = question.questionText,
                    images = question.imagePaths.map { path ->
                        DraftImage(id = path, path = path, width = 0, height = 0, sizeBytes = 0)
                    },
                    privacyConfirmed = true
                )
                val result = analyzer.analyze(
                    draft = draft,
                    apiKey = apiKey,
                    model = settings.value.model,
                    baseUrl = settings.value.baseUrl
                )
                repository.update(question.copy(status = "completed", result = result, errorMessage = null))
                onSuccess?.invoke()
            } catch (e: Exception) {
                repository.update(question.copy(status = "failed", errorMessage = e.message ?: "分析失败"))
                _analysisError.value = e.message ?: "分析失败，请稍后重试"
            } finally {
                _retryingIds.value = _retryingIds.value - id
            }
        }
    }

    fun updateQuestionTextAndRetry(id: String, newText: String, newType: QuestionType? = null) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) {
            _analysisError.value = "题目文字不能为空，如需保留图片请至少补充关键词"
            return
        }
        viewModelScope.launch {
            val question = repository.getById(id) ?: return@launch
            repository.update(
                question.copy(
                    questionText = trimmed,
                    questionType = (newType ?: QuestionType.fromStorage(question.questionType)).storageValue
                )
            )
            retryAnalysis(id)
        }
    }

    fun addImagesToQuestion(id: String, uris: List<Uri>, onDone: (String) -> Unit) {
        if (uris.isEmpty()) {
            onDone("没有选择图片")
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
                repository.update(question.copy(imagePaths = question.imagePaths + added))
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

    fun sendFollowUp(questionId: String, text: String, attachImages: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            _followUpError.value = "请输入追问内容"
            return
        }
        if (questionId in _followUpSendingIds.value) return
        val apiKey = settingsRepository.apiKey()
        if (apiKey.isNullOrBlank()) {
            _followUpError.value = "尚未配置 API 密钥，请先到设置中填写"
            return
        }
        _followUpSendingIds.value = _followUpSendingIds.value + questionId
        _followUpError.value = null
        viewModelScope.launch {
            try {
                val question = repository.getById(questionId)
                    ?: throw ApiException("题目不存在或已被删除")
                val userItem = FollowUp(
                    id = UUID.randomUUID().toString(),
                    questionId = questionId,
                    role = "user",
                    content = trimmed,
                    createdAt = System.currentTimeMillis()
                )
                followUpRepository.insert(userItem)
                val previous = followUpRepository.getForQuestion(questionId).filterNot { it.id == userItem.id }
                val answer = analyzer.followUp(
                    question = question,
                    previous = previous,
                    followUpText = trimmed,
                    attachImages = attachImages,
                    apiKey = apiKey,
                    model = settings.value.model,
                    baseUrl = settings.value.baseUrl
                )
                followUpRepository.insert(
                    FollowUp(
                        id = UUID.randomUUID().toString(),
                        questionId = questionId,
                        role = "assistant",
                        content = answer.content,
                        createdAt = System.currentTimeMillis(),
                        usage = answer.usage
                    )
                )
            } catch (e: Exception) {
                _followUpError.value = e.message ?: "追问失败，请稍后重试"
            } finally {
                _followUpSendingIds.value = _followUpSendingIds.value - questionId
            }
        }
    }

    fun consumeFollowUpError() {
        _followUpError.value = null
    }

    fun deleteQuestion(id: String) {
        viewModelScope.launch {
            val question = repository.getById(id)
            followUpRepository.deleteForQuestion(id)
            repository.delete(id)
            question?.imagePaths?.let(imageStore::deleteAll)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
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
            val error = client.verifyKey(settings.value.baseUrl, key)
            onDone(error)
            _isTestingKey.value = false
        }
    }

    fun removeApiKey() {
        settingsRepository.clearApiKey()
    }

    fun saveModel(model: String) {
        settingsRepository.saveModel(model)
    }

    fun saveBaseUrl(url: String): Boolean = settingsRepository.saveBaseUrl(url)

    fun saveHistoryPreference(enabled: Boolean) {
        settingsRepository.setSaveHistory(enabled)
    }
}
