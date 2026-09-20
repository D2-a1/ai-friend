package com.aifriend.feature.guardian

import com.aifriend.feature.task.BasicExperienceTaskContext
import com.aifriend.feature.wechat.WechatSemanticCallContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class GuardianTaskSubmissionTest {

    @Test
    fun guardianContextUsesCurrentWechatAndSemanticRuleVersions() {
        val context = guardianTaskContext(
            manifest = null,
            currentWechatVersion = "8.0.76",
        )

        assertEquals("8.0.76", context.wechatVersion)
        assertEquals(WechatSemanticCallContract.RULE_VERSION, context.ruleVersion)
        assertEquals(BasicExperienceTaskContext.DIALECT_CODE, context.dialectCode)
        assertEquals(BasicExperienceTaskContext.PACKAGE_VERSION, context.dialectPackageVersion)
        assertEquals(BasicExperienceTaskContext.ASR_MODEL_VERSION, context.mandarinAssistVersion)
        assertEquals(BasicExperienceTaskContext.FUSION_RULE_VERSION, context.fusionRuleVersion)
        assertEquals(BasicExperienceTaskContext.ACOUSTIC_MODEL_VERSION, context.templateModelVersion)
        assertEquals(BasicExperienceTaskContext.THRESHOLD_VERSION, context.thresholdVersion)
    }

    @Test
    fun missingWechatVersionUsesExplicitUnverifiedToken() {
        assertEquals(
            "UNVERIFIED",
            guardianTaskContext(manifest = null, currentWechatVersion = null).wechatVersion,
        )
    }

    @Test
    fun unknownTechnicalFailureIsClassifiedBySubmissionStage() = runTest {
        val failure = try {
            submissionStage(
                stage = GuardianTaskSubmissionStage.TASK_CREATION,
                fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
            ) {
                error("HTTP stream was reset")
            }
            fail("expected stage failure")
            return@runTest
        } catch (caught: GuardianTaskSubmissionException) {
            caught
        }

        assertEquals(GuardianTaskSubmissionStage.TASK_CREATION, failure.stage)
        assertEquals("本机识别或服务器创建任务阶段没有完成，录音已清除", failure.message)
    }

    @Test
    fun controlledChineseFailureRemainsVisible() = runTest {
        val failure = try {
            submissionStage(
                stage = GuardianTaskSubmissionStage.AUDIO_UPLOAD,
                fallback = "任务录音上传阶段没有完成，录音已清除",
            ) {
                error("无法连接音频上传凭证服务，音频没有上传")
            }
            fail("expected stage failure")
            return@runTest
        } catch (caught: GuardianTaskSubmissionException) {
            caught
        }

        assertEquals(GuardianTaskSubmissionStage.AUDIO_UPLOAD, failure.stage)
        assertEquals("无法连接音频上传凭证服务，音频没有上传", failure.message)
    }

    @Test
    fun cancellationIsNotConvertedIntoTaskFailure() = runTest {
        val cancellation = CancellationException("cancel")
        try {
            submissionStage(
                stage = GuardianTaskSubmissionStage.TASK_CREATION,
                fallback = "本机识别或服务器创建任务阶段没有完成，录音已清除",
            ) {
                throw cancellation
            }
            fail("expected cancellation")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
