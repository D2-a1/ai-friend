package com.aifriend.feature.task

import com.aifriend.contract.model.AudioRange
import com.aifriend.contract.model.Intent
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackState
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.OfflineSpeechPort
import com.aifriend.feature.personalization.AmbiguousCallChoice
import com.aifriend.feature.personalization.DialogueStyleChoice
import com.aifriend.feature.personalization.PersonalMemoryChoices
import com.aifriend.feature.personalization.PersonalMemoryRepository
import com.aifriend.feature.personalization.PersonalMemorySnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有效原声切片与完整复述顺序门禁测试。 */
class TaskRehearsalCoordinatorTest {

    @Test
    fun clipperReturnsOnlyTheProvenContinuousRange() {
        val samples = ShortArray(WavPcmCodec.SAMPLE_RATE) { it.toShort() }
        val source = capturedAudio(samples)
        val sourceBefore = source.wavBytes.copyOf()

        val clip = TaskEffectiveAudioClipper().clip(source, AudioRange(100, 200))
        val decoded = WavPcmCodec.decodeMono16(clip)

        assertEquals(100, decoded.durationMs)
        assertEquals(samples[1_600], decoded.samples.first())
        assertEquals(samples[3_199], decoded.samples.last())
        assertArrayEquals(sourceBefore, source.wavBytes)
        decoded.samples.fill(0)
        clip.fill(0)
        source.clear()
    }

    @Test
    fun clipperRejectsRangeOutsideRecordedDuration() {
        runBlocking {
            val source = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE))

            assertRehearsalFailure {
                TaskEffectiveAudioClipper().clip(source, AudioRange(900, 1_001))
            }

            source.clear()
        }
    }

    @Test
    fun messagePlaysEffectiveAudioBeforeOfflineSummary() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(events)
            val speech = FakeSpeech(events)
            val source = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE) { it.toShort() })
            val sourceBefore = source.wavBytes.copyOf()
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())

            val retained = coordinator.rehearse(
                source = source,
                intent = Intent.SEND_MESSAGE,
                ranges = listOf(AudioRange(100, 200)),
                spokenSummary = "给女儿发消息，内容如下",
            )

            assertEquals(listOf("prepare", "play", "speak"), events)
            assertEquals(100, WavPcmCodec.decodeMono16(checkNotNull(playback.played)).durationMs)
            assertEquals(100, WavPcmCodec.decodeMono16(checkNotNull(retained).wavBytes).durationMs)
            assertArrayEquals(sourceBefore, source.wavBytes)
            retained.clear()
            source.clear()
            playback.played?.fill(0)
        }
    }

    @Test
    fun callUsesOfflineSummaryWithoutMessageAudio() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(events)
            val speech = FakeSpeech(events)
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())

            val retained = coordinator.rehearse(
                source = null,
                intent = Intent.VOICE_CALL,
                ranges = emptyList(),
                spokenSummary = "给女儿打微信语音电话",
            )

            assertEquals(listOf("prepare", "speak"), events)
            assertEquals(listOf("给女儿打微信语音电话。请说确认、否认；如果不对，请直接说要改的内容"), speech.spokenTexts)
            assertTrue(playback.played == null)
            assertTrue(retained == null)
        }
    }

    @Test
    fun replayUsesRetainedMessageAudioWithoutChangingIt() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(events)
            val speech = FakeSpeech(events)
            val retained = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE / 10) { it.toShort() })
            val before = retained.wavBytes.copyOf()
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())

            coordinator.replay(
                retainedMessageAudio = retained,
                intent = Intent.SEND_MESSAGE,
                spokenSummary = "给女儿发消息，内容如下",
            )

            assertEquals(listOf("prepare", "play", "speak"), events)
            assertArrayEquals(before, retained.wavBytes)
            assertArrayEquals(before, playback.played)
            retained.clear()
            before.fill(0)
            playback.played?.fill(0)
        }
    }

    @Test
    fun replayRejectsMessageWhenRetainedAudioIsMissing() {
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator = TaskRehearsalCoordinator(
                FakePlayback(events),
                FakeSpeech(events),
                TaskEffectiveAudioClipper(),
            )

            assertRehearsalFailure {
                coordinator.replay(null, Intent.SEND_MESSAGE, "给女儿发消息")
            }

            assertEquals(listOf("prepare"), events)
        }
    }

    @Test
    fun missingOfflineVoiceFailsBeforeAnyAudioPlayback() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(events)
            val speech = FakeSpeech(events, prepareResult = false)
            val source = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE))
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())

            assertRehearsalFailure {
                coordinator.rehearse(
                    source = source,
                    intent = Intent.SEND_MESSAGE,
                    ranges = listOf(AudioRange(100, 200)),
                    spokenSummary = "给女儿发消息",
                )
            }

            assertEquals(listOf("prepare"), events)
            assertTrue(playback.played == null)
            source.clear()
        }
    }

    @Test
    fun multipleMessageRangesFailWithoutPlaybackOrSpeech() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(events)
            val speech = FakeSpeech(events)
            val source = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE))
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())

            assertRehearsalFailure {
                coordinator.rehearse(
                    source = source,
                    intent = Intent.SEND_MESSAGE,
                    ranges = listOf(AudioRange(100, 200), AudioRange(300, 400)),
                    spokenSummary = "给女儿发消息",
                )
            }

            assertEquals(listOf("prepare"), events)
            assertTrue(playback.played == null)
            source.clear()
        }
    }

    @Test
    fun playbackFailureStopsBeforeOfflineSummary() {
        runBlocking {
            val events = mutableListOf<String>()
            val playback = FakePlayback(
                events = events,
                failure = IllegalStateException("native playback failed"),
            )
            val speech = FakeSpeech(events)
            val source = capturedAudio(ShortArray(WavPcmCodec.SAMPLE_RATE) { it.toShort() })
            val coordinator = TaskRehearsalCoordinator(playback, speech, TaskEffectiveAudioClipper())
            var failed = false

            try {
                coordinator.rehearse(
                    source = source,
                    intent = Intent.SEND_MESSAGE,
                    ranges = listOf(AudioRange(100, 200)),
                    spokenSummary = "给女儿发消息",
                )
            } catch (_: IllegalStateException) {
                failed = true
            }

            assertTrue(failed)
            assertEquals(listOf("prepare", "play"), events)
            source.clear()
            playback.played?.fill(0)
        }
    }

    @Test
    fun briefPreferenceKeepsConfirmationAndCorrectionChoices() = runBlocking {
        val speech = FakeSpeech(mutableListOf())
        val coordinator = TaskRehearsalCoordinator(
            FakePlayback(mutableListOf()),
            speech,
            TaskEffectiveAudioClipper(),
            FixedPersonalMemoryRepository(
                PersonalMemoryChoices(dialogueStyle = DialogueStyleChoice.BRIEF),
            ),
        )

        coordinator.rehearse(
            source = null,
            intent = Intent.VOICE_CALL,
            ranges = emptyList(),
            spokenSummary = "给女儿打微信语音电话",
        )

        assertEquals(
            "给女儿打微信语音电话。请说确认、否认；不对请直接纠正",
            speech.spokenTexts.single(),
        )
    }

    @Test
    fun ambiguousCallClarificationGivesConcreteVoiceAndVideoExamples() = runBlocking {
        val speech = FakeSpeech(mutableListOf())
        val coordinator = TaskRehearsalCoordinator(
            FakePlayback(mutableListOf()),
            speech,
            TaskEffectiveAudioClipper(),
            FixedPersonalMemoryRepository(
                PersonalMemoryChoices(ambiguousCall = AmbiguousCallChoice.VIDEO),
            ),
        )

        assertTrue(
            coordinator.promptTaskRevision(
                contentOnly = false,
                correction = false,
                allowAmbiguousCallHint = true,
            ),
        )

        assertTrue(speech.spokenTexts.single().contains("请说打电话或视频通话"))
        assertTrue(speech.spokenTexts.single().contains("没听清").not())
        assertTrue(speech.spokenTexts.single().contains("不用再唤醒").not())
        assertTrue(speech.spokenTexts.single().contains("不需要再次呼唤小友"))
    }

    @Test
    fun ambiguousCallHintIsHiddenOutsideEligibleClarification() = runBlocking {
        val speech = FakeSpeech(mutableListOf())
        val coordinator = TaskRehearsalCoordinator(
            FakePlayback(mutableListOf()),
            speech,
            TaskEffectiveAudioClipper(),
            FixedPersonalMemoryRepository(
                PersonalMemoryChoices(ambiguousCall = AmbiguousCallChoice.VIDEO),
            ),
        )

        coordinator.promptTaskRevision(
            contentOnly = false,
            correction = false,
            allowAmbiguousCallHint = false,
        )

        assertTrue(speech.spokenTexts.single().contains("平时偏向").not())
    }
    private fun capturedAudio(samples: ShortArray): CapturedAudio {
        val pcm = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach(pcm::putShort)
        val wav = WavPcmCodec.encodeMono16(pcm.array())
        pcm.array().fill(0)
        return CapturedAudio(wav, samples.size * 1_000 / WavPcmCodec.SAMPLE_RATE)
    }

    private suspend fun assertRehearsalFailure(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: TaskRehearsalException) {
            failed = true
        }
        assertTrue("Expected TaskRehearsalException", failed)
    }

    private class FixedPersonalMemoryRepository(
        private val choices: PersonalMemoryChoices,
    ) : PersonalMemoryRepository {
        override suspend fun read(): PersonalMemorySnapshot = PersonalMemorySnapshot(
            featureEnabled = true,
            consentGranted = true,
            policyVersion = "personal-memory-v1",
            choices = choices,
            version = 1L,
        )

        override suspend fun update(
            choices: PersonalMemoryChoices,
            expectedVersion: Long,
        ): PersonalMemorySnapshot = error("unused")

        override suspend fun delete(expectedVersion: Long): PersonalMemorySnapshot =
            error("unused")

        override fun currentPromptChoices(): PersonalMemoryChoices = choices

        override fun invalidate() = Unit
    }
    private class FakePlayback(
        private val events: MutableList<String>,
        private val failure: IllegalStateException? = null,
    ) : AudioPlaybackPort {
        override val state: StateFlow<AudioPlaybackState> =
            MutableStateFlow(AudioPlaybackState.STOPPED)
        var played: ByteArray? = null

        override suspend fun play(wavBytes: ByteArray) {
            events += "play"
            played = wavBytes.copyOf()
            failure?.let { throw it }
        }

        override suspend fun stop() {
            events += "stop"
        }

        override fun stopImmediately() {
            events += "stopImmediately"
        }
    }

    private class FakeSpeech(
        private val events: MutableList<String>,
        private val prepareResult: Boolean = true,
    ) : OfflineSpeechPort {
        override suspend fun prepare(): Boolean {
            events += "prepare"
            return prepareResult
        }

        val spokenTexts = mutableListOf<String>()

        override suspend fun speak(text: String): Boolean {
            events += "speak"
            spokenTexts += text
            return true
        }

        override fun close() = Unit
    }
}
