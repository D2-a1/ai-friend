package com.aifriend.core.voice

import androidx.room.withTransaction
import com.aifriend.contract.api.VoiceTemplatesApi
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.security.SessionCredentialStore
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接内存模板生成、服务端元数据对账和本机密文持久化。
 *
 * 服务端清单只用于确认模板身份与兼容版本，不包含也不恢复模板正文。
 *
 * @author codex
 * @since 2026-08-13
 */
interface LocalVoiceTemplateCoordinator {
    fun prepare(firstWav: ByteArray, secondWav: ByteArray): LocalVoiceTemplateCandidate

    /** 当前类别必须与已经通过的安全指令类别达到同一声学区分边界。 */
    fun isMutuallyDistinct(
        candidate: LocalVoiceTemplateCandidate,
        existing: Collection<LocalVoiceTemplateCandidate>,
    ): Boolean = false

    suspend fun persistAlias(
        contactId: String,
        alias: ContactAlias,
        candidate: LocalVoiceTemplateCandidate,
    )

    /** 立即停止当前 owner 的指定称呼参与本机匹配。 */
    suspend fun deleteAliasMaterial(aliasId: String)

    suspend fun replaceSafetyCommands(
        summaries: List<VoiceTemplateSummary>,
        candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
    )

    suspend fun reconcile(): LocalTemplateReconciliation

    suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate?

    /** 用本机 Keystore 密文替换当前 owner 的个人“小友”发音模板。 */
    suspend fun replaceWakeWord(candidate: LocalVoiceTemplateCandidate) {
        throw LocalVoiceTemplateException("本机小友唤醒词存储不可用")
    }

    /** 短暂解密当前 owner 的个人“小友”发音模板；调用方使用后必须清零。 */
    suspend fun loadWakeWord(): LocalVoiceTemplateCandidate? = null

    /** 原子替换当前 owner 的“确认/否认”个人发音模板。 */
    suspend fun replaceTaskDecisionTemplates(
        candidates: Map<TaskDecisionTemplateType, LocalVoiceTemplateCandidate>,
    ) {
        throw LocalVoiceTemplateException("本机任务确认词存储不可用")
    }

    /**
     * 短暂解密完整的“确认/否认”模板。任一缺失时清零已解密材料并返回空列表。
     */
    suspend fun loadTaskDecisionTemplates(): List<LocalTaskDecisionVoiceTemplate> = emptyList()

    /**
     * 读取当前 owner 的全部四类个人安全指令；任一缺失时清理已解密材料并返回空列表。
     */
    suspend fun loadAllSafetyCommands(): List<LocalAvailableVoiceTemplate> {
        val templates = mutableListOf<LocalAvailableVoiceTemplate>()
        for (type in REQUIRED_SAFETY_COMMAND_TYPES) {
            val template = loadSafetyCommand(type)
            if (template == null) {
                templates.forEach(LocalAvailableVoiceTemplate::clear)
                return emptyList()
            }
            templates += template
        }
        return templates
    }
}

private val REQUIRED_SAFETY_COMMAND_TYPES = listOf(
    SafetyCommandType.CONFIRM_SEND,
    SafetyCommandType.CONFIRM_CALL,
    SafetyCommandType.CANCEL,
    SafetyCommandType.REJECT_RETRY,
)

/**
 * 核对四类个人安全指令是否都能从当前 owner 的本机密文中安全读取。
 *
 * 该结果只用于界面就绪提示，任务确认仍由既有服务端模板状态和本机匹配双重门禁决定。
 *
 * @return 四类模板均存在、类型一致且可解密时返回 true
 */
suspend fun LocalVoiceTemplateCoordinator.hasCompleteSafetyCommands(): Boolean {
    val requiredTypes = listOf(
        SafetyCommandType.CONFIRM_SEND,
        SafetyCommandType.CONFIRM_CALL,
        SafetyCommandType.CANCEL,
        SafetyCommandType.REJECT_RETRY,
    )
    for (type in requiredTypes) {
        val template = loadSafetyCommand(type) ?: return false
        val typeMatches = template.type == type
        template.clear()
        if (!typeMatches) return false
    }
    return true
}

/** 只允许清除当前 owner 的本机日常指令模板分类。 */
interface LocalRoutineCommandTemplateStore {
    suspend fun clearRoutineCommands()
}

/** 元数据对账结果；missing 表示服务端存在但本机没有正文，必须重新录制。 */
data class LocalTemplateReconciliation(
    val availableTemplateIds: Set<String>,
    val missingTemplateIds: Set<String>,
    val removedLocalTemplateIds: Set<String>,
    /** 服务端当前确实存在的安全指令类型；不代表本机材料可用。 */
    val serverSafetyCommandTypes: Set<SafetyCommandType> = emptySet(),
    /** 服务端清单判定可用且与本机当前方言包四项版本一致的安全指令类型。 */
    val compatibleServerSafetyCommandTypes: Set<SafetyCommandType> = emptySet(),
    /** 对账后仍保留在本机的安全指令类型；最终可用性仍需逐条解密校验。 */
    val availableSafetyCommandTypes: Set<SafetyCommandType> = emptySet(),
)

/** 当前任务播报后的个人决定词；只表示短语类别，不表示说话人身份。 */
enum class TaskDecisionTemplateType(val spokenText: String) {
    CONFIRM("确认"),
    REJECT("否认"),
}

/** 仅在一次匹配调用内短暂存在的任务决定模板。 */
class LocalTaskDecisionVoiceTemplate(
    val type: TaskDecisionTemplateType,
    val candidate: LocalVoiceTemplateCandidate,
) {
    fun clear() = candidate.clear()
}

/** 仅供后续本机匹配端口使用的短暂解密模板。 */
class LocalAvailableVoiceTemplate(
    val templateId: String,
    val type: SafetyCommandType,
    val candidate: LocalVoiceTemplateCandidate,
) {
    fun clear() = candidate.clear()
}

/** 只读服务端模板元数据；401 时刷新并最多重试一次。 */
@Singleton
class VoiceTemplateMetadataRepository @Inject constructor(
    private val voiceTemplatesApi: VoiceTemplatesApi,
    private val authSessionRepository: AuthSessionRepository,
) {
    suspend fun list(): List<VoiceTemplateSummary> {
        var response = voiceTemplatesApi.listMyVoiceTemplates()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = voiceTemplatesApi.listMyVoiceTemplates()
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "语音模板元数据对账失败")
    }
}

/** Room + Keystore 的默认本机模板协调器。 */
@Singleton
class DefaultLocalVoiceTemplateCoordinator @Inject constructor(
    private val engine: LocalVoiceTemplateEngine,
    private val dialectPackageRegistry: DialectPackageRegistry,
    private val database: LocalVoiceTemplateDatabase,
    private val cipher: VoiceTemplateCipher,
    private val sessionCredentialStore: SessionCredentialStore,
    private val metadataRepository: VoiceTemplateMetadataRepository,
) : LocalVoiceTemplateCoordinator, LocalRoutineCommandTemplateStore {

    private val dao get() = database.localVoiceTemplateDao()

    override fun prepare(
        firstWav: ByteArray,
        secondWav: ByteArray,
    ): LocalVoiceTemplateCandidate = engine.enroll(firstWav, secondWav)

    override fun isMutuallyDistinct(
        candidate: LocalVoiceTemplateCandidate,
        existing: Collection<LocalVoiceTemplateCandidate>,
    ): Boolean = engine.isMutuallyDistinct(candidate, existing)

    override suspend fun persistAlias(
        contactId: String,
        alias: ContactAlias,
        candidate: LocalVoiceTemplateCandidate,
    ) {
        val ownerScope = currentOwnerScope()
        val remote = metadataRepository.list()
        val summary = remote.singleOrNull { item ->
            item.category == VoiceTemplateSummary.Category.CONTACT_ALIAS &&
                item.contactId == contactId &&
                item.aliasId == alias.id
        } ?: throw LocalVoiceTemplateException(
            "称呼已在服务端保存，但本机模板元数据不可用，请删除后重新录制",
        )
        requireCompatible(summary, candidate)
        val entity = encrypt(ownerScope, summary, candidate)
        database.withTransaction {
            dao.upsertAll(listOf(entity))
            reconcileLocked(ownerScope, remote)
        }
    }

    override suspend fun deleteAliasMaterial(aliasId: String) {
        val ownerScope = currentOwnerScope()
        database.withTransaction {
            dao.deleteAlias(ownerScope, aliasId)
        }
    }

    override suspend fun replaceSafetyCommands(
        summaries: List<VoiceTemplateSummary>,
        candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
    ) {
        require(candidates.keys == REQUIRED_SAFETY_TYPES) { "本机安全指令模板不完整" }
        val safetySummaries = summaries.filter {
            it.category == VoiceTemplateSummary.Category.SAFETY_COMMAND
        }
        require(
            safetySummaries.size == REQUIRED_SAFETY_TYPES.size &&
                safetySummaries.mapNotNull { it.safetyCommandType }.toSet() == REQUIRED_SAFETY_TYPES,
        ) { "服务端安全指令模板不完整" }
        val ownerScope = currentOwnerScope()
        val entities = safetySummaries.map { summary ->
            val type = requireNotNull(summary.safetyCommandType)
            val candidate = requireNotNull(candidates[type])
            requireCompatible(summary, candidate)
            encrypt(ownerScope, summary, candidate)
        }
        database.withTransaction {
            dao.deleteCategory(ownerScope, VoiceTemplateSummary.Category.SAFETY_COMMAND.value)
            dao.upsertAll(entities)
        }
    }

    override suspend fun reconcile(): LocalTemplateReconciliation {
        val ownerScope = currentOwnerScope()
        val remote = metadataRepository.list()
        return database.withTransaction { reconcileLocked(ownerScope, remote) }
    }

    override suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate? {
        val ownerScope = currentOwnerScope()
        val entity = dao.findSafetyCommand(ownerScope, type.value) ?: return null
        if (entity.compatibility != AliasCompatibility.COMPATIBLE.value ||
            !isCompatibleWithActivePackage(entity)
        ) {
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        val material = runCatching {
            cipher.decrypt(entity.encryptedMaterial, associatedData(entity))
        }.getOrElse {
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        if (!MessageDigest.isEqual(
                sha256(material).encodeToByteArray(),
                entity.materialSha256.encodeToByteArray(),
            )
        ) {
            material.fill(0)
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        return LocalAvailableVoiceTemplate(
            templateId = entity.templateId,
            type = type,
            candidate = LocalVoiceTemplateCandidate(
                dialectCode = entity.dialectCode,
                dialectPackageVersion = entity.dialectPackageVersion,
                modelVersion = entity.modelVersion,
                thresholdVersion = entity.thresholdVersion,
                material = material,
            ),
        )
    }

    override suspend fun replaceWakeWord(candidate: LocalVoiceTemplateCandidate) {
        val ownerScope = currentOwnerScope()
        val shell = LocalVoiceTemplateEntity(
            ownerScope = ownerScope,
            templateId = LOCAL_WAKE_WORD_TEMPLATE_ID,
            category = LOCAL_WAKE_WORD_CATEGORY,
            contactId = null,
            aliasId = null,
            safetyCommandType = null,
            dialectCode = candidate.dialectCode,
            dialectPackageVersion = candidate.dialectPackageVersion,
            modelVersion = candidate.modelVersion,
            thresholdVersion = candidate.thresholdVersion,
            compatibility = AliasCompatibility.COMPATIBLE.value,
            serverUpdatedAt = LOCAL_WAKE_WORD_SCHEMA,
            materialSha256 = sha256(candidate.material),
            encryptedMaterial = byteArrayOf(1),
        )
        val entity = shell.copy(
            encryptedMaterial = cipher.encrypt(candidate.material, associatedData(shell)),
        )
        database.withTransaction {
            dao.deleteCategory(ownerScope, LOCAL_WAKE_WORD_CATEGORY)
            dao.upsertAll(listOf(entity))
        }
    }

    override suspend fun loadWakeWord(): LocalVoiceTemplateCandidate? {
        val ownerScope = currentOwnerScope()
        val entity = dao.findById(ownerScope, LOCAL_WAKE_WORD_TEMPLATE_ID) ?: return null
        if (entity.category != LOCAL_WAKE_WORD_CATEGORY ||
            entity.compatibility != AliasCompatibility.COMPATIBLE.value ||
            !isCompatibleWithActivePackage(entity)
        ) {
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        val material = runCatching {
            cipher.decrypt(entity.encryptedMaterial, associatedData(entity))
        }.getOrElse {
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        if (!MessageDigest.isEqual(
                sha256(material).encodeToByteArray(),
                entity.materialSha256.encodeToByteArray(),
            )
        ) {
            material.fill(0)
            dao.delete(ownerScope, entity.templateId)
            return null
        }
        return LocalVoiceTemplateCandidate(
            dialectCode = entity.dialectCode,
            dialectPackageVersion = entity.dialectPackageVersion,
            modelVersion = entity.modelVersion,
            thresholdVersion = entity.thresholdVersion,
            material = material,
        )
    }

    override suspend fun replaceTaskDecisionTemplates(
        candidates: Map<TaskDecisionTemplateType, LocalVoiceTemplateCandidate>,
    ) {
        val required = TaskDecisionTemplateType.entries.toSet()
        require(candidates.keys == required) { "确认与否认模板必须同时录制" }
        val confirm = requireNotNull(candidates[TaskDecisionTemplateType.CONFIRM])
        val reject = requireNotNull(candidates[TaskDecisionTemplateType.REJECT])
        require(engine.isMutuallyDistinct(confirm, listOf(reject))) {
            "确认与否认发音太相近，请全部重新录制"
        }
        val ownerScope = currentOwnerScope()
        val entities = TaskDecisionTemplateType.entries.map { type ->
            encryptLocal(
                ownerScope = ownerScope,
                templateId = taskDecisionTemplateId(type),
                category = LOCAL_TASK_DECISION_CATEGORY,
                discriminator = type.name,
                schema = LOCAL_TASK_DECISION_SCHEMA,
                candidate = requireNotNull(candidates[type]),
            )
        }
        database.withTransaction {
            dao.deleteCategory(ownerScope, LOCAL_TASK_DECISION_CATEGORY)
            dao.upsertAll(entities)
        }
    }

    override suspend fun loadTaskDecisionTemplates(): List<LocalTaskDecisionVoiceTemplate> {
        val ownerScope = currentOwnerScope()
        val loaded = mutableListOf<LocalTaskDecisionVoiceTemplate>()
        for (type in TaskDecisionTemplateType.entries) {
            val entity = dao.findById(ownerScope, taskDecisionTemplateId(type))
            if (entity == null ||
                entity.category != LOCAL_TASK_DECISION_CATEGORY ||
                entity.safetyCommandType != type.name ||
                entity.compatibility != AliasCompatibility.COMPATIBLE.value ||
                !isCompatibleWithActivePackage(entity)
            ) {
                loaded.forEach(LocalTaskDecisionVoiceTemplate::clear)
                return emptyList()
            }
            val candidate = decryptLocalCandidate(entity)
            if (candidate == null) {
                loaded.forEach(LocalTaskDecisionVoiceTemplate::clear)
                dao.deleteCategory(ownerScope, LOCAL_TASK_DECISION_CATEGORY)
                return emptyList()
            }
            loaded += LocalTaskDecisionVoiceTemplate(type, candidate)
        }
        return loaded
    }

    override suspend fun clearRoutineCommands() {
        val ownerScope = currentOwnerScope()
        database.withTransaction {
            dao.deleteCategory(
                ownerScope,
                VoiceTemplateSummary.Category.ROUTINE_COMMAND.value,
            )
        }
    }

    private suspend fun reconcileLocked(
        ownerScope: String,
        remote: List<VoiceTemplateSummary>,
    ): LocalTemplateReconciliation {
        val remoteById = remote.associateBy { it.templateId }
        val local = dao.list(ownerScope)
        val removed = linkedSetOf<String>()
        val available = linkedSetOf<String>()
        local.forEach { entity ->
            if (entity.category == LOCAL_WAKE_WORD_CATEGORY ||
                entity.category == LOCAL_TASK_DECISION_CATEGORY
            ) return@forEach
            val summary = remoteById[entity.templateId]
            if (summary == null || !metadataMatches(entity, summary)) {
                dao.delete(ownerScope, entity.templateId)
                removed += entity.templateId
            } else {
                available += entity.templateId
            }
        }
        val remoteTemplatesRequiringLocalMaterial = remoteById.values
            .filter { it.category != VoiceTemplateSummary.Category.ROUTINE_COMMAND }
            .mapTo(linkedSetOf()) { it.templateId }
        val serverSafetyCommandTypes = remote.asSequence()
            .filter { it.category == VoiceTemplateSummary.Category.SAFETY_COMMAND }
            .mapNotNull { it.safetyCommandType }
            .toSet()
        val compatibleServerSafetyCommandTypes = remote.asSequence()
            .filter { it.category == VoiceTemplateSummary.Category.SAFETY_COMMAND }
            .filter(::isCompatibleWithActivePackage)
            .mapNotNull { it.safetyCommandType }
            .toSet()
        val availableSafetyCommandTypes = local.asSequence()
            .filter { it.templateId in available }
            .filter { it.category == VoiceTemplateSummary.Category.SAFETY_COMMAND.value }
            .mapNotNull { entity ->
                SafetyCommandType.entries.singleOrNull { type ->
                    type.value == entity.safetyCommandType
                }
            }
            .toSet()
        return LocalTemplateReconciliation(
            availableTemplateIds = available,
            missingTemplateIds = remoteTemplatesRequiringLocalMaterial - available,
            removedLocalTemplateIds = removed,
            serverSafetyCommandTypes = serverSafetyCommandTypes,
            compatibleServerSafetyCommandTypes = compatibleServerSafetyCommandTypes,
            availableSafetyCommandTypes = availableSafetyCommandTypes,
        )
    }

    private fun encrypt(
        ownerScope: String,
        summary: VoiceTemplateSummary,
        candidate: LocalVoiceTemplateCandidate,
    ): LocalVoiceTemplateEntity {
        val shell = LocalVoiceTemplateEntity(
            ownerScope = ownerScope,
            templateId = summary.templateId,
            category = summary.category.value,
            contactId = summary.contactId,
            aliasId = summary.aliasId,
            safetyCommandType = summary.safetyCommandType?.value,
            dialectCode = summary.dialectCode,
            dialectPackageVersion = summary.dialectPackageVersion,
            modelVersion = summary.modelVersion,
            thresholdVersion = summary.thresholdVersion,
            compatibility = summary.compatibility.value,
            serverUpdatedAt = summary.updatedAt.toString(),
            materialSha256 = sha256(candidate.material),
            encryptedMaterial = byteArrayOf(1),
        )
        return shell.copy(
            encryptedMaterial = cipher.encrypt(candidate.material, associatedData(shell)),
        )
    }

    private fun encryptLocal(
        ownerScope: String,
        templateId: String,
        category: String,
        discriminator: String?,
        schema: String,
        candidate: LocalVoiceTemplateCandidate,
    ): LocalVoiceTemplateEntity {
        val shell = LocalVoiceTemplateEntity(
            ownerScope = ownerScope,
            templateId = templateId,
            category = category,
            contactId = null,
            aliasId = null,
            safetyCommandType = discriminator,
            dialectCode = candidate.dialectCode,
            dialectPackageVersion = candidate.dialectPackageVersion,
            modelVersion = candidate.modelVersion,
            thresholdVersion = candidate.thresholdVersion,
            compatibility = AliasCompatibility.COMPATIBLE.value,
            serverUpdatedAt = schema,
            materialSha256 = sha256(candidate.material),
            encryptedMaterial = byteArrayOf(1),
        )
        return shell.copy(
            encryptedMaterial = cipher.encrypt(candidate.material, associatedData(shell)),
        )
    }

    private suspend fun decryptLocalCandidate(
        entity: LocalVoiceTemplateEntity,
    ): LocalVoiceTemplateCandidate? {
        val material = runCatching {
            cipher.decrypt(entity.encryptedMaterial, associatedData(entity))
        }.getOrElse {
            dao.delete(entity.ownerScope, entity.templateId)
            return null
        }
        if (!MessageDigest.isEqual(
                sha256(material).encodeToByteArray(),
                entity.materialSha256.encodeToByteArray(),
            )
        ) {
            material.fill(0)
            dao.delete(entity.ownerScope, entity.templateId)
            return null
        }
        return LocalVoiceTemplateCandidate(
            dialectCode = entity.dialectCode,
            dialectPackageVersion = entity.dialectPackageVersion,
            modelVersion = entity.modelVersion,
            thresholdVersion = entity.thresholdVersion,
            material = material,
        )
    }

    private fun taskDecisionTemplateId(type: TaskDecisionTemplateType): String =
        "local-task-decision-v1-" + type.name.lowercase()

    private fun requireCompatible(
        summary: VoiceTemplateSummary,
        candidate: LocalVoiceTemplateCandidate,
    ) {
        if (summary.compatibility != AliasCompatibility.COMPATIBLE ||
            summary.dialectCode != candidate.dialectCode ||
            summary.dialectPackageVersion != candidate.dialectPackageVersion ||
            summary.modelVersion != candidate.modelVersion ||
            summary.thresholdVersion != candidate.thresholdVersion
        ) {
            throw LocalVoiceTemplateException(
                "服务端模板版本与本机语音参数不一致，请重新录制",
            )
        }
    }

    private fun metadataMatches(
        entity: LocalVoiceTemplateEntity,
        summary: VoiceTemplateSummary,
    ): Boolean = entity.category == summary.category.value &&
        entity.contactId == summary.contactId &&
        entity.aliasId == summary.aliasId &&
        entity.safetyCommandType == summary.safetyCommandType?.value &&
        entity.dialectCode == summary.dialectCode &&
        entity.dialectPackageVersion == summary.dialectPackageVersion &&
        entity.modelVersion == summary.modelVersion &&
        entity.thresholdVersion == summary.thresholdVersion &&
        entity.compatibility == summary.compatibility.value &&
        sameServerUpdateInstant(entity.serverUpdatedAt, summary.updatedAt) &&
        summary.compatibility == AliasCompatibility.COMPATIBLE &&
        isCompatibleWithActivePackage(summary)

    private fun isCompatibleWithActivePackage(summary: VoiceTemplateSummary): Boolean {
        val manifest = dialectPackageRegistry.activePackage()?.manifest ?: return false
        return summary.compatibility == AliasCompatibility.COMPATIBLE &&
            summary.dialectCode == manifest.dialectCode &&
            summary.dialectPackageVersion == manifest.packageVersion &&
            summary.modelVersion == manifest.acousticModelVersion &&
            summary.thresholdVersion == manifest.thresholdVersion
    }

    private fun isCompatibleWithActivePackage(entity: LocalVoiceTemplateEntity): Boolean {
        val manifest = dialectPackageRegistry.activePackage()?.manifest ?: return false
        return entity.dialectCode == manifest.dialectCode &&
            entity.dialectPackageVersion == manifest.packageVersion &&
            entity.modelVersion == manifest.acousticModelVersion &&
            entity.thresholdVersion == manifest.thresholdVersion
    }

    private fun associatedData(entity: LocalVoiceTemplateEntity): ByteArray = listOf(
        entity.ownerScope,
        entity.templateId,
        entity.category,
        entity.dialectCode,
        entity.dialectPackageVersion,
        entity.modelVersion,
        entity.thresholdVersion,
        entity.serverUpdatedAt,
    ).joinToString("\u001f").encodeToByteArray()

    private fun currentOwnerScope(): String {
        val userId = sessionCredentialStore.session.value?.userId
            ?: throw LocalVoiceTemplateException("登录会话不可用，不能保存本机语音模板")
        return sha256("voice-template-owner-v1\u0000$userId".encodeToByteArray())
    }

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOCAL_WAKE_WORD_TEMPLATE_ID = "local-wake-word-v1"
        const val LOCAL_WAKE_WORD_CATEGORY = "LOCAL_WAKE_WORD"
        const val LOCAL_WAKE_WORD_SCHEMA = "local-wake-word-v1"
        const val LOCAL_TASK_DECISION_CATEGORY = "LOCAL_TASK_DECISION"
        const val LOCAL_TASK_DECISION_SCHEMA = "local-task-decision-v1"
        val REQUIRED_SAFETY_TYPES = setOf(
            SafetyCommandType.CONFIRM_SEND,
            SafetyCommandType.CONFIRM_CALL,
            SafetyCommandType.CANCEL,
            SafetyCommandType.REJECT_RETRY,
        )
    }
}

/**
 * MySQL `DATETIME(3)` 会把服务端保存响应中的纳秒截为毫秒；同一条模板不能因此被误判为已替换。
 */
internal fun sameServerUpdateInstant(
    storedValue: String,
    remoteValue: OffsetDateTime,
): Boolean = runCatching {
    OffsetDateTime.parse(storedValue).toInstant().toEpochMilli() ==
        remoteValue.toInstant().toEpochMilli()
}.getOrDefault(false)
