package com.aifriend.feature.task

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceConfirmationGrammarTest {

    @Test
    fun `页面展示词表与识别词表使用同一契约`() {
        assertEquals(listOf("确认"), TaskConfirmationPhraseCatalog.confirmPhrases)
        assertEquals(
            listOf("否认", "拒绝", "取消", "不确认"),
            TaskConfirmationPhraseCatalog.rejectPhrases,
        )
    }

    @Test
    fun `无语音可重听而识别器异常立即失败关闭`() {
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            confirmationRecognitionFailureDecision(NoSpeechRecognizedException()),
        )
        assertEquals(
            VoiceConfirmationDecision.UNAVAILABLE,
            confirmationRecognitionFailureDecision(IllegalStateException("model failed")),
        )
    }

    @Test
    fun `协程取消不能被转换成普通识别失败`() {
        assertThrows(CancellationException::class.java) {
            confirmationRecognitionFailureDecision(CancellationException("cancelled"))
        }
    }

    @Test
    fun `只接受完整确认短词`() {
        assertEquals(VoiceConfirmationDecision.CONFIRM, VoiceConfirmationGrammar.classify("确认"))
        assertEquals(VoiceConfirmationDecision.CONFIRM, VoiceConfirmationGrammar.classify("确 认"))
    }

    @Test
    fun `明确否认拒绝取消都不会执行动作`() {
        listOf("否认", "拒绝", "取消", "不确认").forEach { transcript ->
            assertEquals(
                VoiceConfirmationDecision.REJECT,
                VoiceConfirmationGrammar.classify(transcript),
            )
        }
    }

    @Test
    fun `确认拒绝词不会被误判为任务纠错`() {
        listOf(
            "确认", "否认", "拒绝", "取消", "不确认",
            "发送消息", "把消息发出去", "拨打电话", "现在打电话",
            "取消这次", "这次不要了", "重新说一遍", "我重新说",
        ).forEach { transcript ->
            assertEquals(false, recognition(transcript).isExplicitTaskRevision())
        }
    }

    @Test
    fun `联系人动作和消息内容纠错会进入同一会话修订`() {
        listOf(
            "不对，是视频通话",
            "联系人不对，是二女儿",
            "内容改成后天回来",
        ).forEach { transcript ->
            assertEquals(true, recognition(transcript).isExplicitTaskRevision())
        }
    }
    @Test
    fun `含糊词和夹带任务内容永远不能确认`() {
        listOf("嗯", "好", "对", "确认发送", "给老大打电话", "").forEach { transcript ->
            assertEquals(
                VoiceConfirmationDecision.UNKNOWN,
                VoiceConfirmationGrammar.classify(transcript),
            )
        }
    }

    @Test
    fun `个人模板与文字识别冲突时必须重听而不能误取消或执行`() {
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.CONFIRM,
                VoiceConfirmationDecision.REJECT,
            ),
        )
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.REJECT,
                VoiceConfirmationDecision.CONFIRM,
            ),
        )
    }

    @Test
    fun `任一可用通道唯一命中时采用结果且双路都故障才不可用`() {
        assertEquals(
            VoiceConfirmationDecision.CONFIRM,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.CONFIRM,
                VoiceConfirmationDecision.UNKNOWN,
            ),
        )
        assertEquals(
            VoiceConfirmationDecision.REJECT,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.UNAVAILABLE,
                VoiceConfirmationDecision.REJECT,
            ),
        )
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.UNKNOWN,
                VoiceConfirmationDecision.UNAVAILABLE,
            ),
        )
        assertEquals(
            VoiceConfirmationDecision.UNAVAILABLE,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.UNAVAILABLE,
                VoiceConfirmationDecision.UNAVAILABLE,
            ),
        )
    }

    @Test
    fun `未听清确认词只在当前确认阶段重听而不能上传成任务纠错`() {
        assertEquals(
            VoiceConfirmationRoute.RETRY_LISTENING,
            VoiceConfirmationDecision.UNKNOWN.confirmationRoute(),
        )
        assertEquals(
            VoiceConfirmationRoute.SUBMIT_EXPECTED,
            VoiceConfirmationDecision.CONFIRM.confirmationRoute(),
        )
        assertEquals(
            VoiceConfirmationRoute.SUBMIT_REJECT,
            VoiceConfirmationDecision.REJECT.confirmationRoute(),
        )
        assertEquals(
            VoiceConfirmationRoute.FAIL_UNAVAILABLE,
            VoiceConfirmationDecision.UNAVAILABLE.confirmationRoute(),
        )
        assertEquals(
            VoiceConfirmationRoute.REPEAT_FULL_TASK,
            VoiceConfirmationDecision.REPEAT.confirmationRoute(),
        )
    }

    @Test
    fun `三路中方言重说唯一命中时进入同一任务且冲突时重听`() {
        assertEquals(
            VoiceConfirmationDecision.REPEAT,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.UNKNOWN,
                VoiceConfirmationDecision.UNAVAILABLE,
                VoiceConfirmationDecision.REPEAT,
            ),
        )
        assertEquals(
            VoiceConfirmationDecision.UNKNOWN,
            fuseConfirmationDecisions(
                VoiceConfirmationDecision.CONFIRM,
                VoiceConfirmationDecision.UNKNOWN,
                VoiceConfirmationDecision.REJECT,
            ),
        )
    }
}

private fun recognition(transcript: String) = LocalTaskRecognition(
    transcript = transcript,
    confidence = 0.9,
    modelVersion = "test-model",
    modelArchiveSha256 = "test-sha256",
    words = emptyList(),
)
