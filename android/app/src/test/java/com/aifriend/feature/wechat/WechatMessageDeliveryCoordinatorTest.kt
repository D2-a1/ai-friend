package com.aifriend.feature.wechat

import com.aifriend.contract.model.ChannelPartResult
import com.aifriend.contract.model.ChannelResult
import com.aifriend.core.audio.CapturedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 原声优先、文字补充且不冒充发送成功的纯状态机测试。 */
class WechatMessageDeliveryCoordinatorTest {
    @Test
    fun unavailableCalibratedMessagePortClosesAudioWithoutTryingText() {
        val report = unsupportedWechatMessageDeliveryReport()

        assertEquals(ChannelResult.UNSUPPORTED, report.result)
        assertEquals(1, report.parts.size)
        assertEquals(ChannelPartResult.Part.AUDIO, report.parts.single().part)
        assertEquals(ChannelPartResult.Result.UNSUPPORTED, report.parts.single().result)
        assertEquals(
            "CALIBRATED_MESSAGE_SHARE_UNAVAILABLE",
            report.parts.single().evidenceCode,
        )
    }

    private val coordinator = WechatMessageDeliveryCoordinator()

    @Test
    fun handsOffAudioBeforeReferenceTextAndNeverReportsSent() = runTest {
        val port = RecordingPort()

        val report = coordinator.deliver(audio(), "明天上午来看我", port)

        assertEquals(listOf("audio", "text:AI好友转写，仅供参考：明天上午来看我"), port.calls)
        assertEquals(ChannelResult.HANDED_TO_WECHAT, report.result)
        assertFalse(report.result == ChannelResult.SENT)
        assertEquals(
            listOf(ChannelPartResult.Part.AUDIO, ChannelPartResult.Part.TEXT),
            report.parts.map(ChannelPartResult::part),
        )
    }

    @Test
    fun audioUnsupportedStopsBeforeText() = runTest {
        val port = RecordingPort(audioOutcome = WechatMessageHandoffOutcome.UNSUPPORTED)

        val report = coordinator.deliver(audio(), "不用发送", port)

        assertEquals(listOf("audio"), port.calls)
        assertEquals(ChannelResult.UNSUPPORTED, report.result)
        assertEquals(ChannelPartResult.Result.UNSUPPORTED, report.parts.single().result)
    }

    @Test
    fun audioFailureStopsBeforeText() = runTest {
        val port = RecordingPort(audioOutcome = WechatMessageHandoffOutcome.FAILED)

        val report = coordinator.deliver(audio(), "不用发送", port)

        assertEquals(listOf("audio"), port.calls)
        assertEquals(ChannelResult.FAILED, report.result)
    }

    @Test
    fun textFailureReportsPartialAndDoesNotRepeatAudio() = runTest {
        val port = RecordingPort(textOutcome = WechatMessageHandoffOutcome.FAILED)

        val report = coordinator.deliver(audio(), "下午回电话", port)

        assertEquals(listOf("audio", "text:AI好友转写，仅供参考：下午回电话"), port.calls)
        assertEquals(ChannelResult.PARTIAL, report.result)
        assertEquals(ChannelPartResult.Result.HANDED_TO_WECHAT, report.parts[0].result)
        assertEquals(ChannelPartResult.Result.FAILED, report.parts[1].result)
    }

    @Test
    fun transportExceptionBecomesFiniteFailureWithoutTextAttempt() = runTest {
        val port = RecordingPort(throwOnAudio = true)

        val report = coordinator.deliver(audio(), "不用发送", port)

        assertEquals(listOf("audio"), port.calls)
        assertEquals(ChannelResult.FAILED, report.result)
    }

    @Test(expected = CancellationException::class)
    fun coroutineCancellationIsNotReportedAsChannelFailure() = runTest {
        coordinator.deliver(audio(), "不用发送", RecordingPort(cancelOnAudio = true))
    }

    @Test
    fun inputAudioRemainsOwnedByCaller() = runTest {
        val audio = audio()

        coordinator.deliver(audio, "请来一下", RecordingPort())

        assertTrue(audio.wavBytes.any { it.toInt() != 0 })
        audio.clear()
        assertTrue(audio.wavBytes.all { it.toInt() == 0 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reportCannotContainUnverifiedSentPart() {
        WechatMessageDeliveryReport(
            result = ChannelResult.HANDED_TO_WECHAT,
            parts = listOf(
                ChannelPartResult(
                    part = ChannelPartResult.Part.AUDIO,
                    result = ChannelPartResult.Result.SENT,
                    evidenceCode = "sdk-callback",
                ),
            ),
        )
    }

    private fun audio(): CapturedAudio = CapturedAudio(byteArrayOf(1, 2, 3), 300)

    private class RecordingPort(
        private val audioOutcome: WechatMessageHandoffOutcome =
            WechatMessageHandoffOutcome.HANDED_TO_WECHAT,
        private val textOutcome: WechatMessageHandoffOutcome =
            WechatMessageHandoffOutcome.HANDED_TO_WECHAT,
        private val throwOnAudio: Boolean = false,
        private val cancelOnAudio: Boolean = false,
    ) : WechatMessageHandoffPort {
        val calls = mutableListOf<String>()

        override suspend fun handoffAudio(audio: CapturedAudio): WechatMessageHandoffOutcome {
            calls += "audio"
            if (cancelOnAudio) throw CancellationException("cancel")
            if (throwOnAudio) error("sdk detail")
            return audioOutcome
        }

        override suspend fun handoffText(text: String): WechatMessageHandoffOutcome {
            calls += "text:$text"
            return textOutcome
        }
    }
}
