package com.aifriend.feature.task

import com.aifriend.contract.api.TasksApi
import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.ChannelResultRequest
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.CreateTaskSessionRequest
import com.aifriend.contract.model.RecentTaskResult
import com.aifriend.contract.model.TaskClientRecognitionEvidence
import com.aifriend.contract.model.TaskClientContext
import com.aifriend.contract.model.TaskRecognizedWordEvidence
import com.aifriend.contract.model.TaskRevisionMode
import com.aifriend.contract.model.TaskRevisionRequest
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.contract.model.TaskConfirmationRequest
import com.aifriend.contract.model.TaskSelectionRequest
import com.aifriend.contract.model.TaskSession
import com.aifriend.contract.model.WechatActionPlan
import com.aifriend.core.network.ApiErrorCodeReader
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.io.IOException
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * 使用生成 Tasks API 的默认任务仓库；401 时同幂等键与同正文刷新一次。
 *
 * 其他失败不重试，避免旧确认、旧音频或不明渠道结果离线补执行。
 *
 * @author codex
 * @since 2026-08-13
 */
@Singleton
class DefaultTaskRepository @Inject constructor(
    private val tasksApi: TasksApi,
    private val authSessionRepository: AuthSessionRepository,
    private val localTaskRecognizer: LocalTaskRecognizer,
    private val errorCodeReader: ApiErrorCodeReader,
) : TaskRepository {

    override suspend fun listRecentResults(): List<RecentTaskResult> {
        var response = tasksApi.listRecentTaskResults()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = tasksApi.listRecentTaskResults()
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "最近任务结果加载失败，请稍后重试")
    }

    override suspend fun create(
        audioObjectId: String,
        context: TaskClientContext,
        previousConfirmedContactId: String?,
        basicRecognitionAudio: CapturedAudio?,
    ): TaskSession {
        val localRecognition = basicRecognitionAudio?.let { localTaskRecognizer.recognize(it) }
        val key = UUID.randomUUID().toString()
        val request = CreateTaskSessionRequest(
            clientTaskId = UUID.randomUUID().toString(),
            audioObjectId = audioObjectId,
            clientContext = context,
            previousConfirmedContactId = previousConfirmedContactId,
            basicRecognition = localRecognition?.let { recognition ->
                TaskClientRecognitionEvidence(
                    transcript = recognition.transcript,
                    confidence = recognition.confidence,
                    modelVersion = recognition.modelVersion,
                    modelArchiveSha256 = recognition.modelArchiveSha256,
                    words = recognition.words.map { word ->
                        TaskRecognizedWordEvidence(
                            text = word.text,
                            startMs = word.startMs,
                            endMs = word.endMs,
                            confidence = word.confidence,
                        )
                    },
                )
            },
        )
        var response = taskApiCall { tasksApi.createTaskSession(key, request) }
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = taskApiCall { tasksApi.createTaskSession(key, request) }
        }
        val errorCode = if (response.isSuccessful) null else errorCodeReader.read(response)
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(
                response.code(),
                taskFailureMessage(response.code(), errorCode),
                stableErrorCode = errorCode,
            )
    }

    private suspend fun <T> taskApiCall(block: suspend () -> T): T = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: SerializationException) {
        throw TaskTransportException(
            "任务服务响应与当前应用不兼容，本次任务未创建",
            exception,
        )
    } catch (exception: IOException) {
        throw TaskTransportException(
            "任务服务连接失败，本次任务未创建",
            exception,
        )
    }
    override suspend fun select(
        sessionId: String,
        candidateId: String,
        expectedVersion: Long,
    ): TaskSession {
        val key = UUID.randomUUID().toString()
        val request = TaskSelectionRequest(candidateId, expectedVersion)
        var response = tasksApi.selectTaskCandidate(sessionId, key, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = tasksApi.selectTaskCandidate(sessionId, key, request)
        }
        val errorCode = if (response.isSuccessful) null else errorCodeReader.read(response)
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(
                response.code(),
                taskFailureMessage(response.code(), errorCode),
                stableErrorCode = errorCode,
            )
    }

    override suspend fun revise(
        session: TaskSession,
        audioObjectId: String,
        mode: TaskRevisionMode,
        basicRecognitionAudio: CapturedAudio?,
    ): TaskSession {
        val localRecognition = basicRecognitionAudio?.let { localTaskRecognizer.recognize(it) }
        val key = UUID.randomUUID().toString()
        val request = TaskRevisionRequest(
            audioObjectId = audioObjectId,
            expectedVersion = session.sessionVersion,
            mode = mode,
            basicRecognition = localRecognition?.let { recognition ->
                TaskClientRecognitionEvidence(
                    transcript = recognition.transcript,
                    confidence = recognition.confidence,
                    modelVersion = recognition.modelVersion,
                    modelArchiveSha256 = recognition.modelArchiveSha256,
                    words = recognition.words.map { word ->
                        TaskRecognizedWordEvidence(
                            text = word.text,
                            startMs = word.startMs,
                            endMs = word.endMs,
                            confidence = word.confidence,
                        )
                    },
                )
            },
        )
        var response = taskApiCall {
            tasksApi.reviseTaskSession(session.sessionId, key, request)
        }
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = taskApiCall {
                tasksApi.reviseTaskSession(session.sessionId, key, request)
            }
        }
        val errorCode = if (response.isSuccessful) null else errorCodeReader.read(response)
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(
                response.code(),
                taskFailureMessage(response.code(), errorCode),
                stableErrorCode = errorCode,
            )
    }
    override suspend fun confirm(
        session: TaskSession,
        action: ConfirmationAction,
        confirmedAt: OffsetDateTime,
    ): TaskConfirmationOutcome {
        val summaryHash = session.summaryHash
            ?: throw AuthApiException(409, "确认摘要已经失效，请重新说")
        val key = UUID.randomUUID().toString()
        val request = TaskConfirmationRequest(
            action = action,
            expectedVersion = session.sessionVersion,
            summaryHash = summaryHash,
            confirmedAt = confirmedAt,
        )
        var response = tasksApi.confirmTaskSession(session.sessionId, key, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = tasksApi.confirmTaskSession(session.sessionId, key, request)
        }
        val errorCode = if (response.isSuccessful) null else errorCodeReader.read(response)
        val result = response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(
                response.code(),
                taskFailureMessage(response.code(), errorCode),
                stableErrorCode = errorCode,
            )
        return TaskConfirmationOutcome(result.session, result.actionPlan)
    }

    override suspend fun reportChannelResult(
        sessionId: String,
        plan: WechatActionPlan,
        result: ChannelResult,
        parts: List<ChannelPartResult>,
        occurredAt: OffsetDateTime,
    ): TaskSession {
        require(sessionId.isNotBlank()) { "任务编号不能为空" }
        val key = UUID.randomUUID().toString()
        val request = channelResultRequest(plan, result, parts, occurredAt)
        var response = tasksApi.reportTaskChannelResult(sessionId, key, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = tasksApi.reportTaskChannelResult(sessionId, key, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), channelResultFailure(response.code()))
    }

    private fun channelResultFailure(status: Int): String = when (status) {
        409 -> "本次微信操作已经失效，请重新说"
        410 -> "本次任务已结束，请重新说"
        422 -> "微信操作结果无法确认，请重新说"
        else -> "微信操作结果没有保存，请不要重复发送"
    }
}

/**
 * 将服务端稳定错误码收敛为不包含响应正文或用户数据的明确提示。
 *
 * HTTP 状态只作为旧服务兼容回落；当前服务端同一个 409 可能分别表示模板不完整、
 * 模板版本不兼容或任务并发冲突，不能再合并成同一句话要求用户盲目重录。
 */
internal fun taskFailureMessage(status: Int, errorCode: String?): String = when (errorCode) {
    "AUTH_REQUIRED" -> "登录状态已经失效，请重新登录"
    "VALIDATION_FAILED" ->
        "任务请求没有通过服务器参数校验，请停止重试并核对应用与服务器版本"
    "RATE_LIMITED" -> "操作太频繁，请稍后再试"
    "CONSENT_REQUIRED" -> "请先完成当前语音用途授权"
    "SAFETY_COMMAND_REQUIRED" -> "服务端没有完整四类安全指令，请打开录制安全指令页面核对"
    "TEMPLATE_INCOMPATIBLE" ->
        "服务端当前四类安全指令与本机不兼容；请打开录制安全指令页面核对保存结果，不要重复发起联系任务"
    "CONTACT_ALIAS_INCOMPATIBLE" ->
        "联系人称呼模板与当前版本不兼容；请到联系人管理删除标为不可用的称呼并重新录制，安全指令无需重录"
    "SESSION_CONFLICT" -> "当前任务与服务器状态冲突，请返回首页后重新开始"
    "AUDIO_INVALID" -> "本次任务录音已经失效，请重新说"
    "NO_CONTACT_MATCH" -> "没有可靠匹配到联系人，请重新说清联系人称呼"
    "ACTION_UNSUPPORTED" ->
        "任务已经识别，但没有通过微信执行安全检查；没有操作微信"
    "AUDIO_SEGMENT_UNCERTAIN" -> "现在没有听清完整需求，请重新说"
    "ASR_UNAVAILABLE" -> "当前语音识别服务不可用，请稍后再试"
    "SEMANTIC_MODEL_UNAVAILABLE" ->
        "已听到录音，但语义理解服务暂时不可用，请稍后再试"
    "SEMANTIC_MODEL_PROTOCOL_INVALID" ->
        "已听到录音，但语义理解结果异常，本次不会执行，请稍后再试"
    "INTERNAL_ERROR" -> "服务器处理任务时发生内部错误，请稍后再试"
    else -> when (status) {
        400 -> "任务请求没有通过服务器参数校验，请停止重试并核对应用与服务器版本"
        401 -> "登录状态已经失效，请重新登录"
        403 -> "请先完成当前语音用途授权"
        409 -> "当前任务与服务器状态已经变化，请返回后重新开始"
        410 -> "本次任务已结束，请重新说"
        422 -> "没有可靠匹配到联系人或当前动作不受支持"
        429 -> "操作太频繁，请稍后再试"
        503 -> "现在听不清，请稍后重新说"
        500, 502, 504 -> "服务器处理任务时发生内部错误，请稍后再试"
        else -> "任务没有完成，请重新说"
    }
}

/** 只把当前计划和本次有限渠道结果映射为既有 OpenAPI 请求。 */
internal fun channelResultRequest(
    plan: WechatActionPlan,
    result: ChannelResult,
    parts: List<ChannelPartResult>,
    occurredAt: OffsetDateTime,
): ChannelResultRequest {
    require(parts.isNotEmpty()) { "渠道结果至少包含一个部分" }
    return ChannelResultRequest(
        planId = plan.planId,
        summaryHash = plan.summaryHash,
        result = result,
        ruleVersion = plan.minimumRuleVersion,
        occurredAt = occurredAt,
        parts = parts.toList(),
    )
}
internal class TaskTransportException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
