package com.aifriend.feature.guardian

import com.aifriend.BuildConfig
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.contract.model.TaskClientContext
import com.aifriend.contract.model.TaskSession
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.voice.DialectPackageRegistry
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.task.TaskRepository
import com.aifriend.feature.task.BasicExperienceTaskContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 守护任务交接的当前进程元数据。 */
data class GuardianTaskHandoff(
    val session: TaskSession,
    val guardianResumeEligible: Boolean,
    val taskAudio: CapturedAudio,
)

/** 只向页面导航层暴露公开会话编号，不暴露原始音频。 */
data class GuardianTaskSignal(
    val sessionId: String,
)

/** 双唤醒任务创建结果的当前进程内交接箱，不持久化音频、摘要或候选。 */
@Singleton
class GuardianTaskInbox @Inject constructor() {
    private var currentHandoff: GuardianTaskHandoff? = null
    private val mutableSignal = MutableStateFlow<GuardianTaskSignal?>(null)
    val signal: StateFlow<GuardianTaskSignal?> = mutableSignal.asStateFlow()

    @Synchronized
    fun publish(session: TaskSession, taskAudio: CapturedAudio) {
        currentHandoff?.taskAudio?.clear()
        currentHandoff = GuardianTaskHandoff(
            session = session,
            guardianResumeEligible = true,
            taskAudio = taskAudio,
        )
        mutableSignal.value = GuardianTaskSignal(session.sessionId)
    }

    @Synchronized
    fun take(sessionId: String): GuardianTaskHandoff? {
        val current = currentHandoff?.takeIf { it.session.sessionId == sessionId }
        if (current != null) {
            currentHandoff = null
            mutableSignal.value = null
        }
        return current
    }

    @Synchronized
    fun clear() {
        currentHandoff?.taskAudio?.clear()
        currentHandoff = null
        mutableSignal.value = null
    }
}

/**
 * 将双唤醒后的内存 WAV 沿用既有受限上传与 Tasks 契约创建为任务会话。
 *
 * 正式方言包不存在时可按自用 MVP 基础体验开关使用本机普通话参考识别；
 * 不重试失败任务，不持久化原始音频或正文。
 */
@Singleton
class GuardianTaskSubmission @Inject constructor(
    private val audioUploadRepository: AudioUploadRepository,
    private val taskRepository: TaskRepository,
    private val dialectPackageRegistry: DialectPackageRegistry,
    private val inbox: GuardianTaskInbox,
) {
    suspend fun submit(audio: CapturedAudio): TaskSession {
        val manifest = dialectPackageRegistry.activePackage()?.manifest
        val basicExperience = BuildConfig.BASIC_EXPERIENCE_ENABLED &&
            !dialectPackageRegistry.formalPackageAvailable()
        if (manifest == null && !basicExperience) {
            error("当前语音识别暂不可用，本次录音已清除")
        }
        val audioObjectId = audioUploadRepository.upload(
            purpose = AudioPurpose.TASK,
            mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
            durationMs = audio.durationMs,
            audioContent = audio.wavBytes,
        )
        val session = taskRepository.create(
            audioObjectId = audioObjectId,
            context = TaskClientContext(
                appVersion = BuildConfig.VERSION_NAME,
                wechatVersion = WECHAT_VERSION_UNVERIFIED,
                ruleVersion = WECHAT_RULE_VERSION,
                dialectCode = manifest?.dialectCode ?: BasicExperienceTaskContext.DIALECT_CODE,
                dialectPackageVersion = manifest?.packageVersion
                    ?: BasicExperienceTaskContext.PACKAGE_VERSION,
                mandarinAssistVersion = manifest?.mandarinAssistVersion
                    ?: BasicExperienceTaskContext.ASR_MODEL_VERSION,
                fusionRuleVersion = manifest?.fusionRuleVersion
                    ?: BasicExperienceTaskContext.FUSION_RULE_VERSION,
                templateModelVersion = manifest?.acousticModelVersion
                    ?: BasicExperienceTaskContext.ACOUSTIC_MODEL_VERSION,
                thresholdVersion = manifest?.thresholdVersion
                    ?: BasicExperienceTaskContext.THRESHOLD_VERSION,
            ),
            basicRecognitionAudio = audio.takeIf { basicExperience },
        )
        val handoffAudio = CapturedAudio(audio.wavBytes.copyOf(), audio.durationMs)
        try {
            inbox.publish(session, handoffAudio)
        } catch (exception: Exception) {
            handoffAudio.clear()
            throw exception
        }
        return session
    }

    private companion object {
        const val WECHAT_VERSION_UNVERIFIED = "UNVERIFIED"
        const val WECHAT_RULE_VERSION = "RESERVED_DISABLED"
    }
}
