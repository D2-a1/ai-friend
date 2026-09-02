package com.aifriend.feature.privacy

import com.aifriend.contract.model.TaskHistoryDeletion

/** 当前账号任务历史清除网络仓库。 */
interface TaskHistoryDeletionRepository {
    suspend fun clear(): TaskHistoryDeletion
    suspend fun get(): TaskHistoryDeletion
}
