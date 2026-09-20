package com.aifriend.feature.task

import com.aifriend.feature.auth.AuthApiException
import kotlinx.coroutines.CancellationException

/** 手动任务提交的可公开失败阶段；不携带录音、识别文字、地址或响应正文。 */
internal enum class ManualTaskSubmissionStage {
    AUDIO_UPLOAD,
    TASK_CREATION,
}

/** 将未知技术异常收敛为可定位且不泄露用户内容的阶段错误。 */
internal class ManualTaskSubmissionException(
    val stage: ManualTaskSubmissionStage,
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal suspend fun <T> manualTaskSubmissionStage(
    stage: ManualTaskSubmissionStage,
    fallback: String,
    block: suspend () -> T,
): T = try {
    block()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    throw ManualTaskSubmissionException(
        stage = stage,
        message = exception.manualTaskUserMessage(fallback),
        cause = exception,
    )
}

/** 只透传受控中文业务提示；技术异常、地址和响应正文统一替换为阶段文案。 */
private fun Throwable.manualTaskUserMessage(fallback: String): String {
    val value = message?.trim().orEmpty()
    val containsCjk = value.any { character ->
        character in '㐀'..'䶿' || character in '一'..'鿿'
    }
    val containsAsciiLetter = value.any { character ->
        character in 'A'..'Z' || character in 'a'..'z'
    }
    val containsControl = value.any(Char::isISOControl)
    return value.takeIf {
        it.length in 1..160 && containsCjk && !containsAsciiLetter && !containsControl
    } ?: fallback
}

/** 仅返回 HTTP 状态和稳定错误码；不读取或记录响应正文、地址、录音及业务字段。 */
internal fun Throwable.manualTaskDiagnosticSummary(): String {
    var current: Throwable? = this
    repeat(MAXIMUM_CAUSE_DEPTH) {
        val failure = current as? AuthApiException
        if (failure != null) {
            val code = failure.stableErrorCode
                ?.takeIf { value -> STABLE_ERROR_CODE.matches(value) }
                ?: UNKNOWN_ERROR_CODE
            return "status=${failure.httpStatus.coerceIn(0, 999)} code=$code"
        }
        current = current?.cause
    }
    return "status=0 code=$UNKNOWN_ERROR_CODE"
}

private val STABLE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
private const val UNKNOWN_ERROR_CODE = "UNKNOWN"
private const val MAXIMUM_CAUSE_DEPTH = 8
