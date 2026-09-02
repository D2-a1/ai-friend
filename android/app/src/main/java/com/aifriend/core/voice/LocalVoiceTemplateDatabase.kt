package com.aifriend.core.voice

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * owner 隔离的本机语音内容模板密文。
 *
 * 仅 [encryptedMaterial] 保存声学模板，原始录音永不进入 Room。
 *
 * @author codex
 * @since 2026-08-13
 */
@Entity(
    tableName = "local_voice_template",
    primaryKeys = ["ownerScope", "templateId"],
    indices = [
        Index(value = ["ownerScope", "category"]),
        Index(value = ["ownerScope", "aliasId"]),
        Index(value = ["ownerScope", "safetyCommandType"]),
    ],
)
data class LocalVoiceTemplateEntity(
    val ownerScope: String,
    val templateId: String,
    val category: String,
    val contactId: String?,
    val aliasId: String?,
    val safetyCommandType: String?,
    val dialectCode: String,
    val dialectPackageVersion: String,
    val modelVersion: String,
    val thresholdVersion: String,
    val compatibility: String,
    val serverUpdatedAt: String,
    val materialSha256: String,
    val encryptedMaterial: ByteArray,
)

/** 所有查询都强制携带 owner 范围，不提供跨 owner 全表读取接口。 */
@Dao
interface LocalVoiceTemplateDao {
    @Query("SELECT * FROM local_voice_template WHERE ownerScope = :ownerScope")
    suspend fun list(ownerScope: String): List<LocalVoiceTemplateEntity>

    @Query(
        "SELECT * FROM local_voice_template " +
            "WHERE ownerScope = :ownerScope AND templateId = :templateId LIMIT 1",
    )
    suspend fun findById(ownerScope: String, templateId: String): LocalVoiceTemplateEntity?

    @Query(
        "SELECT * FROM local_voice_template " +
            "WHERE ownerScope = :ownerScope AND safetyCommandType = :type LIMIT 1",
    )
    suspend fun findSafetyCommand(ownerScope: String, type: String): LocalVoiceTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<LocalVoiceTemplateEntity>)

    @Query("DELETE FROM local_voice_template WHERE ownerScope = :ownerScope AND templateId = :templateId")
    suspend fun delete(ownerScope: String, templateId: String)

    @Query("DELETE FROM local_voice_template WHERE ownerScope = :ownerScope AND aliasId = :aliasId")
    suspend fun deleteAlias(ownerScope: String, aliasId: String)

    @Query("DELETE FROM local_voice_template WHERE ownerScope = :ownerScope AND category = :category")
    suspend fun deleteCategory(ownerScope: String, category: String)

    @Query("DELETE FROM local_voice_template WHERE ownerScope = :ownerScope")
    suspend fun clearOwner(ownerScope: String)
}

/** 本机语音模板数据库；不启用破坏性迁移降级。 */
@Database(entities = [LocalVoiceTemplateEntity::class], version = 1, exportSchema = false)
abstract class LocalVoiceTemplateDatabase : RoomDatabase() {
    abstract fun localVoiceTemplateDao(): LocalVoiceTemplateDao
}
