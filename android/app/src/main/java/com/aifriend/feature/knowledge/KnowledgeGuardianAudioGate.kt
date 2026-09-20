package com.aifriend.feature.knowledge

import com.aifriend.feature.guardian.GuardianQuestionAudioCoordinator
import javax.inject.Inject

/** 实际守护交接适配器；没有默认成功替身，不启动或停止Android服务。 */
internal class KnowledgeGuardianAudioGate @Inject constructor(
    private val coordinator: GuardianQuestionAudioCoordinator,
) : KnowledgeVoiceAudioGate {
    override suspend fun acquire(): KnowledgeVoiceAudioLease? {
        val lease = coordinator.acquireQuestion() ?: return null
        return object : KnowledgeVoiceAudioLease {
            override suspend fun release() = lease.release()
            override suspend fun retainAfterCleanupFailure() = lease.retainAfterCleanupFailure()
        }
    }
}
