package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.AppSettings
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class HistoryResults(val total: Int, val matches: List<Question>)

/**
 * Owns history query/filter/export state so MainViewModel can stay focused on task orchestration.
 */
class HistoryCoordinator(
    private val repository: QuestionRepository,
    private val settings: StateFlow<AppSettings>,
    scope: CoroutineScope
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val history: StateFlow<HistoryResults?> = combine(
        repository.observeAll(),
        _query,
        settings.map { it.saveHistory }.distinctUntilChanged()
    ) { all, rawQuery, saveHistory ->
        val visible = if (saveHistory) all else all.filter { it.isFavorite }
        val query = rawQuery.trim()
        val matches = if (query.isEmpty()) visible else visible.filter { question ->
            question.questionText.contains(query, ignoreCase = true) ||
                question.result?.answer?.joinToString().orEmpty().contains(query, ignoreCase = true) ||
                question.result?.answerText.orEmpty().contains(query, ignoreCase = true) ||
                question.result?.recognizedQuestion.orEmpty().contains(query, ignoreCase = true) ||
                question.result?.keyPoints?.joinToString().orEmpty().contains(query, ignoreCase = true)
        }
        HistoryResults(visible.size, matches)
    }.flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    val hasStoredQuestions = repository.observeHasStoredQuestions()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun exportText(): String? = withContext(Dispatchers.Default) {
        val keepHistory = settings.value.saveHistory
        val records = repository.getAll().filter { !it.isEphemeral && (keepHistory || it.isFavorite) }
        if (records.isEmpty()) return@withContext null
        buildString {
            appendLine("医学拍题助手 · 历史记录导出")
            appendLine("共 ${records.size} 条")
            records.forEach { question ->
                appendLine()
                appendLine("【${question.typeLabel}】${question.shortQuestion}")
                appendLine("状态：${question.status} · ${question.modeLabel} · ${question.modelLabel}")
                question.result?.let {
                    if (question.isAnswerStale) appendLine("旧答案（题目已修改，待重新解析）：${it.answerDisplay}")
                    else appendLine("答案：${it.answerDisplay}")
                }
            }
        }
    }

    fun setQuery(query: String) {
        _query.value = query
    }
}
