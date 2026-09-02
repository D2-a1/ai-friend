package com.aifriend.feature.settings

/** 已可靠学习的日常动作模板聚合，不包含原话、联系人或消息正文。 */
data class RoutineCommandTemplateOverview(
    val totalCount: Int,
    val sendMessageCount: Int,
    val voiceCallCount: Int,
    val videoCallCount: Int,
    val compatibleCount: Int,
) {
    val incompatibleCount: Int get() = totalCount - compatibleCount

    companion object {
        val Empty = RoutineCommandTemplateOverview(
            totalCount = 0,
            sendMessageCount = 0,
            voiceCallCount = 0,
            videoCallCount = 0,
            compatibleCount = 0,
        )
    }
}

/** 当前账号日常指令模板清除仓库。 */
interface RoutineCommandDeletionRepository {
    /** 查询服务端已可靠学习模板，并仅返回动作类别计数。 */
    suspend fun list(): RoutineCommandTemplateOverview

    /** 服务端删除成功后清除当前 owner 的本机对应分类。 */
    suspend fun clear(): Int
}
