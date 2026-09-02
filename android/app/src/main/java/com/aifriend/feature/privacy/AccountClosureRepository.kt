package com.aifriend.feature.privacy

/** 注销可靠受理结果；恢复结果表示服务端已受理但本次没有重新返回时间字段。 */
sealed interface AccountClosureAcceptance {
    data object Accepted : AccountClosureAcceptance
    data object Recovered : AccountClosureAcceptance
}

interface AccountClosureRepository {
    suspend fun close(): AccountClosureAcceptance
}
