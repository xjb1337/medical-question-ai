package com.xjb.medicalquestionai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnalysisCheckpointPersistenceTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun validatingStatusTransitionKeepsPersistedCheckpoint() = runBlocking {
        val dao = db.questionDao()
        val checkpoint = checkpoint("解析中的题干")
        dao.insert(question())
        dao.updateCheckpoint(QUESTION_ID, checkpoint.toJson())

        dao.updateStatus(QUESTION_ID, "validating")

        val stored = dao.getById(QUESTION_ID)
        assertEquals("validating", stored?.status)
        val checkpointJson = stored?.checkpointJson
        assertNotNull(checkpointJson)
        val restored = AnalysisResult.fromJson(checkpointJson!!)
        assertEquals(checkpoint.recognizedQuestion, restored.recognizedQuestion)
        assertEquals(checkpoint.optionsSeen, restored.optionsSeen)
    }

    @Test
    fun failedWriteWithoutCheckpointArgumentKeepsPersistedCheckpoint() = runBlocking {
        val dao = db.questionDao()
        val checkpoint = checkpoint("失败前的题干")
        dao.insert(question())
        dao.updateCheckpoint(QUESTION_ID, checkpoint.toJson())

        dao.updateAnalysisFailure(
            id = QUESTION_ID,
            status = "failed",
            errorMessage = "连接中断",
            durationMs = 1234L,
            checkpointJson = null
        )

        val stored = dao.getById(QUESTION_ID)
        assertEquals("failed", stored?.status)
        assertEquals("连接中断", stored?.errorMessage)
        assertEquals(1234L, stored?.durationMs)
        val restored = AnalysisResult.fromJson(stored!!.checkpointJson!!)
        assertEquals(checkpoint.recognizedQuestion, restored.recognizedQuestion)
        assertEquals(checkpoint.answer, restored.answer)
    }

    @Test
    fun failedWritePersistsNewestCheckpoint() = runBlocking {
        val dao = db.questionDao()
        dao.insert(question())
        dao.updateCheckpoint(QUESTION_ID, checkpoint("旧题干").toJson())
        val newest = checkpoint("最新题干").copy(answer = listOf("C"))

        dao.updateAnalysisFailure(
            id = QUESTION_ID,
            status = "failed",
            errorMessage = "超时",
            durationMs = 2000L,
            checkpointJson = newest.toJson()
        )

        val restored = AnalysisResult.fromJson(dao.getById(QUESTION_ID)!!.checkpointJson!!)
        assertEquals("最新题干", restored.recognizedQuestion)
        assertEquals(listOf("C"), restored.answer)
    }

    private fun question(): QuestionEntity = Question(
        id = QUESTION_ID,
        createdAt = 1L,
        questionType = "single",
        source = "text",
        questionText = "虚构题干",
        imagePaths = emptyList(),
        status = "analyzing",
        result = null,
        model = "deepseek-flash",
        errorMessage = null,
        isFavorite = false
    ).toEntity()

    private fun checkpoint(question: String): AnalysisResult = AnalysisResult(
        status = "needs_confirmation",
        questionType = "single",
        recognizedQuestion = question,
        optionsSeen = listOf(
            SeenOption("A", "选项原文 A"),
            SeenOption("B", "选项原文 B"),
            SeenOption("C", "选项原文 C")
        ),
        answer = listOf("B"),
        answerText = "心电图",
        confidence = "low",
        warnings = listOf("解析尚未完成")
    )

    private companion object {
        const val QUESTION_ID = "checkpoint-test"
    }
}
