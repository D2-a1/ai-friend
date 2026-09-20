package com.aifriend.feature.task

import com.aifriend.feature.auth.AuthApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ManualTaskSubmissionDiagnosticsTest {

    @Test
    fun unknownTechnicalFailureIsClassifiedBySubmissionStage() = runTest {
        val failure = try {
            manualTaskSubmissionStage(
                stage = ManualTaskSubmissionStage.TASK_CREATION,
                fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
            ) {
                error("HTTP stream was reset")
            }
            fail("expected stage failure")
            return@runTest
        } catch (caught: ManualTaskSubmissionException) {
            caught
        }

        assertEquals(ManualTaskSubmissionStage.TASK_CREATION, failure.stage)
        assertEquals("本机识别或服务器创建任务阶段没有完成，录音已清除", failure.message)
    }

    @Test
    fun controlledChineseFailureRemainsVisible() = runTest {
        val failure = try {
            manualTaskSubmissionStage(
                stage = ManualTaskSubmissionStage.AUDIO_UPLOAD,
                fallback = "任务录音上传阶段没有完成，录音已清除",
            ) {
                error("无法连接音频上传凭证服务，音频没有上传")
            }
            fail("expected stage failure")
            return@runTest
        } catch (caught: ManualTaskSubmissionException) {
            caught
        }

        assertEquals(ManualTaskSubmissionStage.AUDIO_UPLOAD, failure.stage)
        assertEquals("无法连接音频上传凭证服务，音频没有上传", failure.message)
    }

    @Test
    fun cancellationIsNotConvertedIntoTaskFailure() = runTest {
        val cancellation = CancellationException("cancel")
        try {
            manualTaskSubmissionStage(
                stage = ManualTaskSubmissionStage.TASK_CREATION,
                fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
            ) {
                throw cancellation
            }
            fail("expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    @Test
    fun wrappedApiFailureExposesOnlyStatusAndStableErrorCode() = runTest {
        val failure = try {
            manualTaskSubmissionStage(
                stage = ManualTaskSubmissionStage.TASK_CREATION,
                fallback = "服务器创建任务阶段没有完成",
            ) {
                throw AuthApiException(
                    httpStatus = 422,
                    message = "任务已经识别，但没有通过微信执行安全检查；没有操作微信",
                    stableErrorCode = "ACTION_UNSUPPORTED",
                )
            }
            fail("expected stage failure")
            return@runTest
        } catch (caught: ManualTaskSubmissionException) {
            caught
        }

        assertEquals(
            "status=422 code=ACTION_UNSUPPORTED",
            failure.manualTaskDiagnosticSummary(),
        )
    }

    @Test
    fun unsafeServerErrorCodeIsRedactedFromDiagnostics() {
        val failure = AuthApiException(
            httpStatus = 500,
            message = "服务器处理失败",
            stableErrorCode = "secret=value",
        )

        assertEquals("status=500 code=UNKNOWN", failure.manualTaskDiagnosticSummary())
    }
}
