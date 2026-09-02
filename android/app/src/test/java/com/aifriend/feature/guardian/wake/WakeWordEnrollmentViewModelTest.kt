package com.aifriend.feature.guardian.wake

import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioCaptureState
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackState
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.voice.LocalAvailableVoiceTemplate
import com.aifriend.core.voice.LocalTemplateReconciliation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 个人“小友”双录只保存本机模板并及时清理原始录音。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordEnrollmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun twoConsistentTakesRequireExplicitSaveAndClearRawAudio() = runTest(dispatcher) {
        val first = sineAudio(1_200, 7_000)
        val second = sineAudio(1_250, 7_100)
        val coordinator = FakeCoordinator()
        val viewModel = viewModel(ArrayDeque(listOf(first, second)), coordinator)

        viewModel.open()
        runCurrent()
        recordOnce(viewModel)
        recordOnce(viewModel)

        assertEquals(WakeWordEnrollmentStage.REVIEW, viewModel.uiState.value.stage)
        assertEquals(0, coordinator.savedMaterials.size)

        viewModel.confirmSave()
        runCurrent()

        assertEquals(WakeWordEnrollmentStage.COMPLETED, viewModel.uiState.value.stage)
        assertEquals(1, coordinator.savedMaterials.size)
        assertTrue(coordinator.savedMaterials.single().any { it != 0.toByte() })
        assertTrue(first.wavBytes.all { it == 0.toByte() })
        assertTrue(second.wavBytes.all { it == 0.toByte() })
    }

    @Test
    fun inconsistentSecondTakeKeepsFirstTakeForTargetedRetry() = runTest(dispatcher) {
        val coordinator = FakeCoordinator(failPrepare = true)
        val viewModel = viewModel(
            ArrayDeque(listOf(sineAudio(1_200, 7_000), sineAudio(1_250, 7_100))),
            coordinator,
        )

        viewModel.open()
        runCurrent()
        recordOnce(viewModel)
        recordOnce(viewModel)

        assertEquals(WakeWordEnrollmentStage.FIRST_RECORDED, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.firstDurationMs != null)
        assertEquals(null, viewModel.uiState.value.secondDurationMs)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("两遍发音不一致"))
        assertTrue(coordinator.savedMaterials.isEmpty())
    }

    private fun viewModel(
        recordings: ArrayDeque<CapturedAudio>,
        coordinator: FakeCoordinator,
    ) = WakeWordEnrollmentViewModel(
        audioCapturePort = FakeCapture(recordings),
        audioPlaybackPort = FakePlayback(),
        recordingNormalizer = VoiceTemplateRecordingNormalizer(),
        coordinator = coordinator,
    )

    private fun TestScope.recordOnce(viewModel: WakeWordEnrollmentViewModel) {
        viewModel.startRecording()
        runCurrent()
        viewModel.finishRecording()
        runCurrent()
    }

    private class FakeCapture(
        private val recordings: ArrayDeque<CapturedAudio>,
    ) : AudioCapturePort {
        private val mutableState = MutableStateFlow(AudioCaptureState.STOPPED)
        override val state: StateFlow<AudioCaptureState> = mutableState
        override suspend fun start(maxDurationMs: Int) {
            mutableState.value = AudioCaptureState.CAPTURING
        }
        override suspend fun stop(): CapturedAudio {
            mutableState.value = AudioCaptureState.STOPPED
            return recordings.removeFirst()
        }
        override suspend fun cancel() {
            mutableState.value = AudioCaptureState.STOPPED
        }
    }

    private class FakePlayback : AudioPlaybackPort {
        private val mutableState = MutableStateFlow(AudioPlaybackState.STOPPED)
        override val state: StateFlow<AudioPlaybackState> = mutableState
        override suspend fun play(wavBytes: ByteArray) = Unit
        override suspend fun stop() = Unit
        override fun stopImmediately() = Unit
    }

    private class FakeCoordinator(
        private val failPrepare: Boolean = false,
    ) : LocalVoiceTemplateCoordinator {
        val savedMaterials = mutableListOf<ByteArray>()

        override fun prepare(firstWav: ByteArray, secondWav: ByteArray): LocalVoiceTemplateCandidate {
            if (failPrepare) throw LocalVoiceTemplateException("两遍发音不一致，请重新录制")
            return candidate()
        }

        override suspend fun replaceWakeWord(candidate: LocalVoiceTemplateCandidate) {
            savedMaterials += candidate.material.copyOf()
        }

        override suspend fun persistAlias(
            contactId: String,
            alias: ContactAlias,
            candidate: LocalVoiceTemplateCandidate,
        ) = Unit
        override suspend fun deleteAliasMaterial(aliasId: String) = Unit
        override suspend fun replaceSafetyCommands(
            summaries: List<VoiceTemplateSummary>,
            candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
        ) = Unit
        override suspend fun reconcile() = LocalTemplateReconciliation(
            emptySet(),
            emptySet(),
            emptySet(),
        )
        override suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate? = null
    }

    private companion object {
        fun candidate() = LocalVoiceTemplateCandidate(
            dialectCode = "zh-Hans-CN-x-wugang",
            dialectPackageVersion = "1.0.0",
            modelVersion = "mfcc-dtw-1",
            thresholdVersion = "test-1",
            material = byteArrayOf(1, 2, 3),
        )

        fun sineAudio(durationMs: Int, amplitude: Int): CapturedAudio {
            val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                    .toInt()
                    .toShort()
            }
            val pcm = ByteArray(samples.size * Short.SIZE_BYTES)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            val wav = WavPcmCodec.encodeMono16(pcm)
            pcm.fill(0)
            samples.fill(0)
            return CapturedAudio(wav, durationMs)
        }
    }
}
