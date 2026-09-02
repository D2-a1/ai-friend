package com.aifriend.core.agent

/**
 * Agent 能力标识。未来能力只保留扩展边界，默认关闭。
 *
 * @author codex
 * @since 0.1.0
 */
enum class AgentCapabilityId {
    COMMUNICATION,
    CANCELLATION,
    CORRECTION,
    CONFIRMATION,
    SEMANTIC_MATCHING,
    QUESTION_ANSWERING,
    REMINDER,
    EXTERNAL_KNOWLEDGE,
    PERSONAL_MEMORY,
    KNOWLEDGE_GRAPH,
    VECTOR_RETRIEVAL,
    CLOUD_MODEL,
}

/**
 * Agent 能力交付阶段。
 */
enum class CapabilityStage {
    IN_DEVELOPMENT,
    AVAILABLE,
    RESERVED_DISABLED,
}

/**
 * Agent 能力描述。
 *
 * @property id 能力标识
 * @property stage 交付阶段
 * @property externallyExposed 是否允许通过 UI 或公开 API 暴露
 * @property reason 当前阶段说明
 */
data class CapabilityDescriptor(
    val id: AgentCapabilityId,
    val stage: CapabilityStage,
    val externallyExposed: Boolean,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(stage != CapabilityStage.RESERVED_DISABLED || !externallyExposed) {
            "reserved capability must not be exposed"
        }
    }

    /** 能力是否已经可调用。 */
    val isAvailable: Boolean = stage == CapabilityStage.AVAILABLE
}

/**
 * 本地能力注册表。
 *
 * 基础能力在完成验收前标记为开发中；未来能力保持关闭，不出现在 UI。
 */
class AgentCapabilityRegistry private constructor(
    private val descriptors: Map<AgentCapabilityId, CapabilityDescriptor>,
) {
    /** 查询指定能力描述。 */
    fun get(capabilityId: AgentCapabilityId): CapabilityDescriptor =
        requireNotNull(descriptors[capabilityId]) { "unregistered capability: $capabilityId" }

    /** 返回不可变能力列表。 */
    fun list(): List<CapabilityDescriptor> = descriptors.values.toList()

    companion object {
        /** 使用安全默认状态创建注册表。 */
        fun safeDefault(): AgentCapabilityRegistry {
            val basic = setOf(
                AgentCapabilityId.COMMUNICATION,
                AgentCapabilityId.CANCELLATION,
                AgentCapabilityId.CORRECTION,
                AgentCapabilityId.CONFIRMATION,
            )
            val descriptors = AgentCapabilityId.entries.associateWith { capabilityId ->
                if (capabilityId in basic) {
                    CapabilityDescriptor(
                        id = capabilityId,
                        stage = CapabilityStage.IN_DEVELOPMENT,
                        externallyExposed = false,
                        reason = "基础能力按当前开发计划实现，完成验收前不对外宣称可用",
                    )
                } else {
                    CapabilityDescriptor(
                        id = capabilityId,
                        stage = CapabilityStage.RESERVED_DISABLED,
                        externallyExposed = false,
                        reason = "未来能力仅保留扩展边界，未进入当前实现范围",
                    )
                }
            }
            return AgentCapabilityRegistry(descriptors)
        }
    }
}
