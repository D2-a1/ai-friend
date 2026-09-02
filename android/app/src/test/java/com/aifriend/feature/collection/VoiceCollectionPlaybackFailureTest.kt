package com.aifriend.feature.collection

import com.aifriend.contract.api.VoiceCollectionApi
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.contract.model.CreateVoiceCollectionSampleRequest
import com.aifriend.contract.model.DeleteVoiceCollectionSampleRequest
import com.aifriend.contract.model.UpdateVoiceCollectionTrainingAuthorizationRequest
import com.aifriend.contract.model.VoiceCollectionDeletionResponse
import com.aifriend.contract.model.VoiceCollectionSampleListResponse
import com.aifriend.contract.model.VoiceCollectionSampleResponse
import com.aifriend.contract.model.VoiceCollectionTrainingAuthorizationResponse
import com.aifriend.core.audio.AliasRecordingQualityAnalyzer
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioCaptureState
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackState
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import com.aifriend.feature.consent.ConsentRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** 封闭测试采集试听失败门禁测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCollectionPlaybackFailureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun playbackFailureKeepsReviewBlockedAndDoesNotUpload() = runTest(dispatcher) {
        val capture = FakeAudioCapturePort(sineAudio(1_300, 7_000))
        val uploads = FakeAudioUploadRepository()
        val viewModel = VoiceCollectionViewModel(
            audioCapturePort = capture,
            audioPlaybackPort = FailingAudioPlaybackPort(),
            qualityAnalyzer = AliasRecordingQualityAnalyzer(),
            audioUploadRepository = uploads,
            consentRepository = FakeConsentRepository(),
            collectionRepository = VoiceCollectionRepository(
                UnusedVoiceCollectionApi(),
                FakeAuthSessionRepository(),
            ),
        )

        viewModel.open()
        runCurrent()
        viewModel.grantConsent()
        runCurrent()
        viewModel.startRecording()
        runCurrent()
        viewModel.finishRecording()
        runCurrent()
        assertEquals(VoiceCollectionStage.REVIEW, viewModel.uiState.value.stage)

        viewModel.play()
        runCurrent()

        assertEquals("录音没有播放完整，请重新试听", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isPlaying)
        assertFalse(viewModel.uiState.value.reviewPlaybackCompleted)

        viewModel.submit()
        runCurrent()
        assertEquals("请先完整试听当前录音", viewModel.uiState.value.errorMessage)
        assertEquals(0, uploads.uploadCount)

        viewModel.leave()
        runCurrent()
    }

    @Test
    fun uploadFailureClearsRecordingAndHidesTechnicalMessage() = runTest(dispatcher) {
        val capture = FakeAudioCapturePort(sineAudio(1_300, 7_000))
        val uploads = FakeAudioUploadRepository(
            failure = IllegalStateException("upload client crashed"),
        )
        val viewModel = VoiceCollectionViewModel(
            audioCapturePort = capture,
            audioPlaybackPort = FailingAudioPlaybackPort(failure = null),
            qualityAnalyzer = AliasRecordingQualityAnalyzer(),
            audioUploadRepository = uploads,
            consentRepository = FakeConsentRepository(),
            collectionRepository = VoiceCollectionRepository(
                UnusedVoiceCollectionApi(),
                FakeAuthSessionRepository(),
            ),
        )

        viewModel.open()
        runCurrent()
        viewModel.grantConsent()
        runCurrent()
        viewModel.startRecording()
        runCurrent()
        viewModel.finishRecording()
        runCurrent()
        viewModel.play()
        runCurrent()
        viewModel.submit()
        runCurrent()

        assertEquals(VoiceCollectionStage.READY, viewModel.uiState.value.stage)
        assertEquals(
            "样本没有提交；本地录音已清理，请重新录制",
            viewModel.uiState.value.errorMessage,
        )
        assertFalse(viewModel.uiState.value.reviewPlaybackCompleted)
        assertEquals(1, uploads.uploadCount)

        viewModel.leave()
        runCurrent()
    }

    @Test
    fun consentUpdateFailureHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = VoiceCollectionViewModel(
            audioCapturePort = FakeAudioCapturePort(sineAudio(1_300, 7_000)),
            audioPlaybackPort = FailingAudioPlaybackPort(failure = null),
            qualityAnalyzer = AliasRecordingQualityAnalyzer(),
            audioUploadRepository = FakeAudioUploadRepository(),
            consentRepository = FakeConsentRepository(
                updateFailure = IllegalStateException("request body failed"),
            ),
            collectionRepository = VoiceCollectionRepository(
                UnusedVoiceCollectionApi(),
                FakeAuthSessionRepository(),
            ),
        )

        viewModel.open()
        runCurrent()
        viewModel.grantConsent()
        runCurrent()

        assertEquals("采集授权没有保存", viewModel.uiState.value.errorMessage)
        viewModel.leave()
        runCurrent()
    }

    private class FakeAudioCapturePort(
        private val audio: CapturedAudio,
    ) : AudioCapturePort {
        private val mutableState = MutableStateFlow(AudioCaptureState.STOPPED)
        override val state: StateFlow<AudioCaptureState> = mutableState

        override suspend fun start(maxDurationMs: Int) {
            mutableState.value = AudioCaptureState.CAPTURING
        }

        override suspend fun stop(): CapturedAudio {
            mutableState.value = AudioCaptureState.STOPPED
            return audio
        }

        override suspend fun cancel() {
            mutableState.value = AudioCaptureState.STOPPED
        }
    }

    private class FailingAudioPlaybackPort(
        private val failure: Exception? = IllegalStateException("native playback failed"),
    ) : AudioPlaybackPort {
        private val mutableState = MutableStateFlow(AudioPlaybackState.STOPPED)
        override val state: StateFlow<AudioPlaybackState> = mutableState

        override suspend fun play(wavBytes: ByteArray) {
            failure?.let {
                mutableState.value = AudioPlaybackState.FAILED
                throw it
            }
            mutableState.value = AudioPlaybackState.STOPPED
        }

        override suspend fun stop() {
            mutableState.value = AudioPlaybackState.STOPPED
        }

        override fun stopImmediately() {
            mutableState.value = AudioPlaybackState.STOPPED
        }
    }

    private class FakeAudioUploadRepository(
        private val failure: Exception? = null,
    ) : AudioUploadRepository {
        var uploadCount = 0

        override suspend fun upload(
            purpose: AudioPurpose,
            mediaType: CreateAudioUploadTicketRequest.MediaType,
            durationMs: Int,
            audioContent: ByteArray,
        ): String {
            uploadCount++
            failure?.let { throw it }
            return "ao_unused"
        }
    }

    private class FakeConsentRepository(
        private val updateFailure: Throwable? = null,
    ) : ConsentRepository {
        override suspend fun listCurrent(): List<Consent> = emptyList()

        override suspend fun update(
            type: ConsentType,
            decision: ConsentDecision,
            policyVersion: String,
        ): Consent {
            updateFailure?.let { throw it }
            return Consent(type, decision, policyVersion, NOW)
        }
    }

    private class UnusedVoiceCollectionApi : VoiceCollectionApi {
        override suspend fun createVoiceCollectionSample(
            idempotencyKey: String,
            createVoiceCollectionSampleRequest: CreateVoiceCollectionSampleRequest,
        ): Response<VoiceCollectionSampleResponse> = error("未使用")

        override suspend fun deleteVoiceCollectionSample(
            sampleId: String,
            idempotencyKey: String,
            deleteVoiceCollectionSampleRequest: DeleteVoiceCollectionSampleRequest,
        ): Response<VoiceCollectionDeletionResponse> = error("未使用")

        override suspend fun listVoiceCollectionSamples(): Response<VoiceCollectionSampleListResponse> =
            error("未使用")

        override suspend fun updateVoiceCollectionTrainingAuthorization(
            sampleId: String,
            idempotencyKey: String,
            updateVoiceCollectionTrainingAuthorizationRequest:
                UpdateVoiceCollectionTrainingAuthorizationRequest,
        ): Response<VoiceCollectionTrainingAuthorizationResponse> = error("未使用")
    }

    private class FakeAuthSessionRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)

        override suspend fun restore(): AuthSession? = null

        override suspend fun loginWithWechatCode(
            code: String,
            device: WechatLoginDevice,
        ): AuthSession = error("未使用")

        override suspend fun refresh(): AuthSession = error("未使用")

        override suspend fun clearLocalSession() = Unit
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-29T01:00:00Z")

        fun sineAudio(durationMs: Int, amplitude: Int): CapturedAudio {
            val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                    .toInt()
                    .toShort()
            }
            val pcmBytes = ByteArray(samples.size * Short.SIZE_BYTES)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            val wavBytes = WavPcmCodec.encodeMono16(pcmBytes)
            pcmBytes.fill(0)
            return CapturedAudio(wavBytes, durationMs)
        }
    }
}
