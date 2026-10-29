package com.xjb.medicalquestionai.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import com.xjb.medicalquestionai.hasRepeatedJsonNullPrefix
import com.xjb.medicalquestionai.isJsonNullLiteral
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val questionType: String,
    val source: String,
    val questionText: String,
    val imagePaths: String,
    val status: String,
    val answerJson: String?,
    val model: String,
    val errorMessage: String?,
    val isFavorite: Boolean,
    @ColumnInfo(defaultValue = "0") val isEphemeral: Boolean = false,
    @ColumnInfo(defaultValue = "'standard'") val mode: String = "standard",
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "''") val promptVersion: String = "",
    @ColumnInfo(defaultValue = "''") val baseUrl: String = "",
    @ColumnInfo(defaultValue = "0") val inputRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val answerInputRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val answerRevision: Long = 0
)

@Entity(tableName = "follow_ups")
data class FollowUpEntity(
    @PrimaryKey val id: String,
    val questionId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val failed: Boolean,
    @ColumnInfo(defaultValue = "0") val answerRevision: Long = 0
)

@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE isEphemeral = 0 ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE id = :id")
    fun observeById(id: String): Flow<QuestionEntity?>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun getById(id: String): QuestionEntity?

    @Query("SELECT * FROM questions ORDER BY createdAt DESC")
    suspend fun getAll(): List<QuestionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuestionEntity)

    @Update
    suspend fun update(entity: QuestionEntity)

    @Query("DELETE FROM questions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE questions SET status = 'interrupted', errorMessage = '应用在解析过程中被关闭，请重新解析' WHERE status IN ('analyzing', 'receiving', 'validating')")
    suspend fun markInterrupted()

    @Query("DELETE FROM questions")
    suspend fun deleteAll()
}

@Dao
interface FollowUpDao {
    @Query("SELECT * FROM follow_ups WHERE questionId = :questionId AND answerRevision = :answerRevision ORDER BY createdAt ASC")
    fun observeForQuestion(questionId: String, answerRevision: Long): Flow<List<FollowUpEntity>>

    @Query("SELECT * FROM follow_ups WHERE questionId = :questionId AND answerRevision = :answerRevision ORDER BY createdAt ASC")
    suspend fun getForQuestion(questionId: String, answerRevision: Long): List<FollowUpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FollowUpEntity)

    @Query("DELETE FROM follow_ups WHERE questionId = :questionId")
    suspend fun deleteForQuestion(questionId: String)

    @Query("DELETE FROM follow_ups")
    suspend fun deleteAll()
}

@Database(
    entities = [QuestionEntity::class, FollowUpEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questionDao(): QuestionDao
    abstract fun followUpDao(): FollowUpDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `follow_ups` (
                        `id` TEXT NOT NULL,
                        `questionId` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `promptTokens` INTEGER NOT NULL,
                        `completionTokens` INTEGER NOT NULL,
                        `totalTokens` INTEGER NOT NULL,
                        `failed` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `questions` ADD COLUMN `isEphemeral` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `mode` TEXT NOT NULL DEFAULT 'standard'")
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `promptVersion` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `baseUrl` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `inputRevision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `answerInputRevision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `questions` ADD COLUMN `answerRevision` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `follow_ups` ADD COLUMN `answerRevision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "medical_question.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
                .also { instance = it }
        }
    }
}

class QuestionRepository(private val dao: QuestionDao) {
    fun observeAll(): Flow<List<Question>> = dao.observeAll().mapEntities()

    fun observeById(id: String): Flow<Question?> = dao.observeById(id).mapEntity()

    suspend fun getById(id: String): Question? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<Question> = dao.getAll().map { it.toDomain() }

    suspend fun markInterrupted() = dao.markInterrupted()

    suspend fun insert(question: Question) = dao.insert(question.toEntity())

    suspend fun update(question: Question) = dao.update(question.toEntity())

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun clearAll() = dao.deleteAll()

    private fun Flow<List<QuestionEntity>>.mapEntities(): Flow<List<Question>> =
        map { list -> list.map { it.toDomain() } }

    private fun Flow<QuestionEntity?>.mapEntity(): Flow<Question?> =
        map { it?.toDomain() }
}

class FollowUpRepository(private val dao: FollowUpDao) {
    fun observeForQuestion(questionId: String, answerRevision: Long): Flow<List<FollowUp>> =
        dao.observeForQuestion(questionId, answerRevision).map { list ->
            list.map { it.toDomain() }
                .filterNot { it.content.isJsonNullLiteral() || it.content.hasRepeatedJsonNullPrefix() }
        }

    suspend fun getForQuestion(questionId: String, answerRevision: Long): List<FollowUp> =
        dao.getForQuestion(questionId, answerRevision).map { it.toDomain() }
            .filterNot { it.content.isJsonNullLiteral() || it.content.hasRepeatedJsonNullPrefix() }

    suspend fun insert(followUp: FollowUp) = dao.insert(followUp.toEntity())

    suspend fun deleteForQuestion(questionId: String) = dao.deleteForQuestion(questionId)

    suspend fun clearAll() = dao.deleteAll()
}

fun FollowUpEntity.toDomain(): FollowUp = FollowUp(
    id = id,
    questionId = questionId,
    role = role,
    content = content,
    createdAt = createdAt,
    usage = Usage(promptTokens, completionTokens, totalTokens),
    failed = failed,
    answerRevision = answerRevision
)

fun FollowUp.toEntity(): FollowUpEntity = FollowUpEntity(
    id = id,
    questionId = questionId,
    role = role,
    content = content,
    createdAt = createdAt,
    promptTokens = usage.promptTokens,
    completionTokens = usage.completionTokens,
    totalTokens = usage.totalTokens,
    failed = failed,
    answerRevision = answerRevision
)

fun QuestionEntity.toDomain(): Question = Question(
    id = id,
    createdAt = createdAt,
    questionType = questionType,
    source = source,
    questionText = questionText,
    imagePaths = runCatching {
        val array = org.json.JSONArray(imagePaths)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList()),
    status = status,
    result = answerJson?.let { AnalysisResult.fromJson(it) },
    model = model,
    errorMessage = errorMessage,
    isFavorite = isFavorite,
    isEphemeral = isEphemeral,
    mode = mode,
    durationMs = durationMs,
    promptVersion = promptVersion,
    baseUrl = baseUrl,
    inputRevision = inputRevision,
    answerInputRevision = answerInputRevision,
    answerRevision = answerRevision
)

fun Question.toEntity(): QuestionEntity = QuestionEntity(
    id = id,
    createdAt = createdAt,
    updatedAt = System.currentTimeMillis(),
    questionType = questionType,
    source = source,
    questionText = questionText,
    imagePaths = org.json.JSONArray(imagePaths).toString(),
    status = status,
    // 旧记录必须保留原始 answerJson，避免用当前模型反序列化后再序列化导致字段丢失/变空；
    // 新解析结果带 metrics 时必须写入完整结构（含保存状态/传输信息），否则指标会随 rawJson 丢失。
    answerJson = result?.let { parsed ->
        if (parsed.metrics != null) parsed.toJson()
        else parsed.rawJson.takeIf { it.isNotBlank() } ?: parsed.toJson()
    },
    model = model,
    errorMessage = errorMessage,
    isFavorite = isFavorite,
    isEphemeral = isEphemeral,
    mode = mode,
    durationMs = durationMs,
    promptVersion = promptVersion,
    baseUrl = baseUrl,
    inputRevision = inputRevision,
    answerInputRevision = answerInputRevision,
    answerRevision = answerRevision
)
