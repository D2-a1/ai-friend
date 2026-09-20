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
import com.aifriend.feature.wechat.WechatRuntimeVersionProvider
import com.aifriend.feature.wechat.WechatSemanticCallContract
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
    private val wechatRuntimeVersionProvider: WechatRuntimeVersionProvider,
    private val inbox: GuardianTaskInbox,
) {
    suspend fun submit(audio: CapturedAudio): TaskSession {
        val manifest = dialectPackageRegistry.activePackage()?.manifest
        val basicExperience = BuildConfig.BASIC_EXPERIENCE_ENABLED &&
            !dialectPackageRegistry.formalPackageAvailable()
        if (manifest == null && !basicExperience) {
            error("当前语音识别暂不可用，本次录音已清除")
        }
        val audioObjectId = submissionStage(
            stage = GuardianTaskSubmissionStage.AUDIO_UPLOAD,
            fallback = "任务录音上传阶段没有完成，录音已清除",
        ) {
            audioUploadRepository.upload(
                purpose = AudioPurpose.TASK,
                mediaType = CreateAudioUploadTicketRequest.MediaType.AUDIO_SLASH_WAV,
                durationMs = audio.durationMs,
                audioContent = audio.wavBytes,
            )
        }
        val currentWechatVersion = runCatching {
            wechatRuntimeVersionProvider.readCurrentVersion()
        }.getOrNull()
        val session = submissionStage(
            stage = GuardianTaskSubmissionStage.TASK_CREATION,
            fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
        ) {
            taskRepository.create(
                audioObjectId = audioObjectId,
                context = guardianTaskContext(manifest, currentWechatVersion),
                basicRecognitionAudio = audio.takeIf { basicExperience },
            )
        }
        val handoffAudio = CapturedAudio(audio.wavBytes.copyOf(), audio.durationMs)
        try {
            inbox.publish(session, handoffAudio)
        } catch (exception: Exception) {
            handoffAudio.clear()
            throw exception
        }
        return session
    }
}
/** 守护提交只记录失败阶段，不携带录音、识别文字、地址或服务端正文。 */
internal enum class GuardianTaskSubmissionStage {
    AUDIO_UPLOAD,
    TASK_CREATION,
}

/** 将未知技术异常收敛为可定位且不泄露用户内容的阶段错误。 */
internal class GuardianTaskSubmissionException(
    val stage: GuardianTaskSubmissionStage,
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal suspend fun <T> submissionStage(
    stage: GuardianTaskSubmissionStage,
    fallback: String,
    block: suspend () -> T,
): T = try {
    block()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: GuardianTaskSubmissionException) {
    throw exception
} catch (exception: Exception) {
    throw GuardianTaskSubmissionException(
        stage = stage,
        message = exception.guardianUserMessage(fallback),
        cause = exception,
    )
}

internal fun guardianTaskContext(
    manifest: com.aifriend.core.voice.DialectPackageManifest?,
    currentWechatVersion: String?,
): TaskClientContext = TaskClientContext(
    appVersion = BuildConfig.VERSION_NAME,
    wechatVersion = currentWechatVersion ?: "UNVERIFIED",
    ruleVersion = WechatSemanticCallContract.RULE_VERSION,
    dialectCode = manifest?.dialectCode ?: BasicExperienceTaskContext.DIALECT_CODE,
    dialectPackageVersion = manifest?.packageVersion ?: BasicExperienceTaskContext.PACKAGE_VERSION,
    mandarinAssistVersion = manifest?.mandarinAssistVersion
        ?: BasicExperienceTaskContext.ASR_MODEL_VERSION,
    fusionRuleVersion = manifest?.fusionRuleVersion
        ?: BasicExperienceTaskContext.FUSION_RULE_VERSION,
    templateModelVersion = manifest?.acousticModelVersion
        ?: BasicExperienceTaskContext.ACOUSTIC_MODEL_VERSION,
    thresholdVersion = manifest?.thresholdVersion ?: BasicExperienceTaskContext.THRESHOLD_VERSION,
)
private fun Throwable.guardianUserMessage(fallback: String): String {
    val detail = message?.trim().orEmpty()
    return detail.takeIf { value ->
        value.isNotEmpty() &&
            value.length <= 160 &&
            value.any { character ->
                character in '㐀'..'䶿' || character in '一'..'鿿'
            } &&
            value.none { character ->
                character in 'A'..'Z' ||
                    character in 'a'..'z' ||
                    character.isISOControl()
            }
    } ?: fallback
}
