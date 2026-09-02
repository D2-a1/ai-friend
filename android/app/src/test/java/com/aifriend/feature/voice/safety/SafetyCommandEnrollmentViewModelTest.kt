package com.aifriend.feature.voice.safety

import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.audio.AudioCapturePort
import com.aifriend.core.audio.AudioCaptureException
import com.aifriend.core.audio.AudioCaptureFailure
import com.aifriend.core.audio.AudioCaptureState
import com.aifriend.core.audio.AudioPlaybackPort
import com.aifriend.core.audio.AudioPlaybackState
import com.aifriend.core.audio.CapturedAudio
import com.aifriend.core.audio.WavPcmCodec
import com.aifriend.core.audio.VoiceTemplateRecordingNormalizer
import com.aifriend.core.voice.LocalAvailableVoiceTemplate
import com.aifriend.core.voice.LocalTemplateReconciliation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.LocalVoiceTemplateException
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.auth.AuthApiException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安全指令授权、八段双录与失败清理测试。
 *
 * @author codex
 * @since 2026-08-13
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyCommandEnrollmentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @Test
    fun definitionsOfferTwoPresetActionPhrasesWithoutAddingCommandTypes() {
        assertEquals(
            listOf(
                listOf("发送消息", "把消息发出去"),
                listOf("拨打电话", "现在打电话"),
                listOf("取消这次", "这次不要了"),
                listOf("重新说一遍", "我重新说"),
            ),
            SafetyCommandDefinition.entries.map(SafetyCommandDefinition::phraseOptions),
        )
        assertEquals(4, SafetyCommandDefinition.entries.size)
    }

    @Test
    fun alternatePhraseCanOnlyBeSelectedBeforeFirstTake() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeAudioCapturePort(ArrayDeque(listOf(sineAudio(1_300, 7_000)))),
            FakeAudioUploadRepository(),
            FakeConsentRepository(granted = true),
        )
        viewModel.open()
        runCurrent()

        viewModel.selectCurrentPhrase(1)
        assertEquals("把消息发出去", viewModel.uiState.value.commands[0].selectedSpokenText)

        recordOnce(viewModel)
        viewModel.selectCurrentPhrase(0)
        assertEquals("把消息发出去", viewModel.uiState.value.commands[0].selectedSpokenText)
    }

    @Test
    fun microphoneDenialOffersSettingsRecoveryAndSuccessfulStartClearsIt() =
        runTest(dispatcher) {
            val viewModel = viewModel(
                FakeAudioCapturePort(ArrayDeque(listOf(sineAudio(1_300, 7_000)))),
                FakeAudioUploadRepository(),
                FakeConsentRepository(granted = true),
            )
            viewModel.open()
            runCurrent()

            viewModel.onMicrophonePermissionDenied()
            assertTrue(viewModel.uiState.value.microphonePermissionRecoveryRequired)

            viewModel.startRecording()
            runCurrent()
            assertFalse(viewModel.uiState.value.microphonePermissionRecoveryRequired)
        }

    @Test
    fun microphoneBusyDuringStartReturnsToReadyWithoutSettingsRecovery() =
        runTest(dispatcher) {
            val capturePort = FakeAudioCapturePort(
                recordings = ArrayDeque(),
                startFailure = AudioCaptureException(
                    AudioCaptureFailure.AUDIO_FOCUS_DENIED,
                    "audio focus denied",
                ),
            )
            val viewModel = viewModel(
                capturePort,
                FakeAudioUploadRepository(),
                FakeConsentRepository(granted = true),
            )
            viewModel.open()
            runCurrent()

            viewModel.startRecording()
            runCurrent()

            assertEquals(
                SafetyCommandEnrollmentStage.READY_FIRST,
                viewModel.uiState.value.stage,
            )
            assertFalse(viewModel.uiState.value.microphonePermissionRecoveryRequired)
            assertEquals(
                "麦克风正在被通话或其他应用使用，请稍后再试",
                viewModel.uiState.value.errorMessage,
            )
        }

    @Test
    fun interruptedRecordingReturnsToCurrentCommandWithoutUploading() =
        runTest(dispatcher) {
            val capturePort = FakeAudioCapturePort(
                recordings = ArrayDeque(),
                stopFailure = AudioCaptureException(
                    AudioCaptureFailure.NO_ACTIVE_RECORDING,
                    "recording disappeared",
                ),
            )
            val uploadRepository = FakeAudioUploadRepository()
            val viewModel = viewModel(
                capturePort,
                uploadRepository,
                FakeConsentRepository(granted = true),
            )
            viewModel.open()
            runCurrent()

            viewModel.startRecording()
            runCurrent()
            viewModel.finishRecording()
            runCurrent()

            assertEquals(
                SafetyCommandEnrollmentStage.READY_FIRST,
                viewModel.uiState.value.stage,
            )
            assertEquals(
                "录音已被系统中断，请重新录制",
                viewModel.uiState.value.errorMessage,
            )
            assertFalse(viewModel.uiState.value.microphonePermissionRecoveryRequired)
            assertTrue(uploadRepository.requests.isEmpty())
        }

    @Test
    fun playbackFailureKeepsCurrentRecordingAndDoesNotUpload() =
        runTest(dispatcher) {
            val playbackPort = FakeAudioPlaybackPort(
                failure = IllegalStateException("audio device failed"),
            )
            val uploadRepository = FakeAudioUploadRepository()
            val viewModel = viewModel(
                capture = FakeAudioCapturePort(
                    ArrayDeque(listOf(sineAudio(1_300, 7_000))),
                ),
                uploads = uploadRepository,
                consent = FakeConsentRepository(granted = true),
                playbackPort = playbackPort,
            )
            viewModel.open()
            runCurrent()
            recordOnce(viewModel)

            viewModel.play(SafetyRecordingSlot(0, SafetyRecordingTake.FIRST))
            runCurrent()

            assertEquals(
                SafetyCommandEnrollmentStage.FIRST_RECORDED,
                viewModel.uiState.value.stage,
            )
            assertEquals("录音没有播放完整，请重新试听", viewModel.uiState.value.errorMessage)
            assertEquals(null, viewModel.uiState.value.playingRecording)
            assertTrue(uploadRepository.requests.isEmpty())
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun consentStatusFailureHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeAudioCapturePort(ArrayDeque()),
            FakeAudioUploadRepository(),
            FakeConsentRepository(
                granted = false,
                listFailure = IllegalStateException("TLS handshake failed"),
            ),
        )

        viewModel.open()
        runCurrent()

        assertEquals("无法读取语音模板授权", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun consentUpdateFailureHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = viewModel(
            FakeAudioCapturePort(ArrayDeque()),
            FakeAudioUploadRepository(),
            FakeConsentRepository(
                granted = false,
                updateFailure = IllegalStateException("HTTP 500"),
            ),
        )

        viewModel.open()
        runCurrent()
        viewModel.grantVoiceTemplateConsent()
        runCurrent()

        assertEquals("语音模板授权没有保存", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun requiresSeparateConsentBeforeRecording() = runTest(dispatcher) {
        val consent = FakeConsentRepository(granted = false)
        val capture = FakeAudioCapturePort(ArrayDeque())
        val viewModel = viewModel(capture, FakeAudioUploadRepository(), consent)

        viewModel.open()
        runCurrent()
        assertEquals(SafetyCommandEnrollmentStage.CONSENT_REQUIRED, viewModel.uiState.value.stage)
        viewModel.startRecording()
        runCurrent()
        assertEquals(0, capture.startCount)

        viewModel.grantVoiceTemplateConsent()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
        assertEquals(ConsentType.VOICE_TEMPLATE, consent.updates.single().first)
        assertEquals("voice-template-v1", consent.updates.single().second)
    }

    @Test
    fun existingCompleteSetRequiresExplicitFullReplacement() = runTest(dispatcher) {
        val coordinator = FakeLocalVoiceTemplateCoordinator(existingTypes = requiredSafetyTypes)
        val viewModel = viewModel(
            capture = FakeAudioCapturePort(ArrayDeque()),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            localCoordinator = coordinator,
        )

        viewModel.open()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.EXISTING_COMPLETE, viewModel.uiState.value.stage)
        assertEquals(requiredSafetyTypes, viewModel.uiState.value.existingServerTemplateTypes)
        assertTrue(viewModel.uiState.value.existingLocalTemplatesReady)
        assertEquals(1, coordinator.reconcileCount)
        assertEquals(requiredSafetyTypes.toList(), coordinator.loadedTypes)
        assertTrue(coordinator.loadedMaterials.all { material -> material.all { it == 0.toByte() } })

        viewModel.startFullReplacement()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
    }

    @Test
    fun serverCompleteSetRemainsVisibleWhenLocalMaterialIsMissing() = runTest(dispatcher) {
        val coordinator = FakeLocalVoiceTemplateCoordinator(
            existingTypes = emptySet(),
            serverTypes = requiredSafetyTypes,
        )
        val viewModel = viewModel(
            capture = FakeAudioCapturePort(ArrayDeque()),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            localCoordinator = coordinator,
        )

        viewModel.open()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.EXISTING_COMPLETE, viewModel.uiState.value.stage)
        assertEquals(requiredSafetyTypes, viewModel.uiState.value.existingServerTemplateTypes)
        assertFalse(viewModel.uiState.value.existingLocalTemplatesReady)

        viewModel.startFullReplacement()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
    }

    @Test
    fun incompleteLocalSetStartsFullRecording() = runTest(dispatcher) {
        val coordinator = FakeLocalVoiceTemplateCoordinator(
            existingTypes = requiredSafetyTypes - SafetyCommandType.REJECT_RETRY,
        )
        val viewModel = viewModel(
            capture = FakeAudioCapturePort(ArrayDeque()),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            localCoordinator = coordinator,
        )

        viewModel.open()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
    }

    @Test
    fun eightRecordingsUploadOnlyAfterFinalExplicitConfirmation() = runTest(dispatcher) {
        val audio = List(8) { sineAudio(1_200, 7_000 + it * 100) }
        val capture = FakeAudioCapturePort(ArrayDeque(audio))
        val uploads = FakeAudioUploadRepository()
        val enrollment = FakeEnrollmentRepository()
        val viewModel = viewModel(
            capture = capture,
            uploads = uploads,
            consent = FakeConsentRepository(granted = true),
            enrollment = enrollment,
        )
        viewModel.open()
        runCurrent()

        repeat(4) {
            recordOnce(viewModel)
            recordOnce(viewModel)
            assertEquals(SafetyCommandEnrollmentStage.REVIEW_COMMAND, viewModel.uiState.value.stage)
            viewModel.confirmCurrentCommand()
        }
        assertEquals(SafetyCommandEnrollmentStage.REVIEW_ALL, viewModel.uiState.value.stage)
        assertTrue(uploads.requests.isEmpty())

        viewModel.confirmAndSubmitAll()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.COMPLETED, viewModel.uiState.value.stage)
        assertEquals(8, uploads.requests.size)
        assertTrue(uploads.requests.all { it.purpose == AudioPurpose.SAFETY_COMMAND_ENROLLMENT })
        assertEquals(4, enrollment.commands.single().size)
        assertTrue(audio.all { captured -> captured.wavBytes.all { it == 0.toByte() } })
    }

    @Test
    fun inconsistentCurrentCommandKeepsPreviouslyApprovedCommandsAndOnlyRetakesSecond() =
        runTest(dispatcher) {
            val audio = List(9) { sineAudio(1_200, 7_000 + it * 50) }
            val uploads = FakeAudioUploadRepository()
            val coordinator = FakeLocalVoiceTemplateCoordinator(
                failPrepareCalls = mutableSetOf(2),
            )
            val viewModel = viewModel(
                capture = FakeAudioCapturePort(ArrayDeque(audio)),
                uploads = uploads,
                consent = FakeConsentRepository(granted = true),
                localCoordinator = coordinator,
            )
            viewModel.open()
            runCurrent()

            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
            assertTrue(viewModel.uiState.value.commands[0].reviewed)

            recordOnce(viewModel)
            recordOnce(viewModel)

            assertEquals(
                SafetyCommandEnrollmentStage.FIRST_RECORDED,
                viewModel.uiState.value.stage,
            )
            assertTrue(viewModel.uiState.value.commands[0].reviewed)
            assertEquals(null, viewModel.uiState.value.commands[1].secondDurationMs)
            assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("两遍发音不一致"))
            assertTrue(uploads.requests.isEmpty())

            recordOnce(viewModel)
            assertEquals(
                SafetyCommandEnrollmentStage.REVIEW_COMMAND,
                viewModel.uiState.value.stage,
            )
            viewModel.confirmCurrentCommand()
            repeat(2) {
                recordOnce(viewModel)
                recordOnce(viewModel)
                viewModel.confirmCurrentCommand()
            }
            assertEquals(SafetyCommandEnrollmentStage.REVIEW_ALL, viewModel.uiState.value.stage)

            viewModel.confirmAndSubmitAll()
            runCurrent()

            assertEquals(SafetyCommandEnrollmentStage.COMPLETED, viewModel.uiState.value.stage)
            assertEquals(8, uploads.requests.size)
            assertEquals(5, coordinator.prepareCount)
        }

    @Test
    fun crossCommandSimilarityDoesNotRejectAConsistentCurrentCommand() =
        runTest(dispatcher) {
            val audio = List(4) { sineAudio(1_200, 7_000 + it * 100) }
            val coordinator = FakeLocalVoiceTemplateCoordinator()
            val viewModel = viewModel(
                capture = FakeAudioCapturePort(ArrayDeque(audio)),
                uploads = FakeAudioUploadRepository(),
                consent = FakeConsentRepository(granted = true),
                localCoordinator = coordinator,
            )
            viewModel.open()
            runCurrent()

            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
            recordOnce(viewModel)
            recordOnce(viewModel)

            assertEquals(SafetyCommandEnrollmentStage.REVIEW_COMMAND, viewModel.uiState.value.stage)
            assertEquals(1, viewModel.uiState.value.currentCommandIndex)
            assertTrue(viewModel.uiState.value.commands[0].reviewed)
            assertTrue(viewModel.uiState.value.commands[1].firstDurationMs != null)
            assertTrue(viewModel.uiState.value.commands[1].secondDurationMs != null)
            assertEquals(null, viewModel.uiState.value.errorMessage)
            assertTrue(coordinator.preparedCandidates[0].material.any { it != 0.toByte() })
            assertTrue(coordinator.preparedCandidates[1].material.any { it != 0.toByte() })
        }

    @Test
    fun uploadFailureClearsAllEightAndDoesNotAutoRetry() = runTest(dispatcher) {
        val audio = List(8) { sineAudio(1_200, 7_000) }
        val capture = FakeAudioCapturePort(ArrayDeque(audio))
        val uploads = FakeAudioUploadRepository(failOnRequest = 5)
        val enrollment = FakeEnrollmentRepository()
        val viewModel = viewModel(
            capture = capture,
            uploads = uploads,
            consent = FakeConsentRepository(granted = true),
            enrollment = enrollment,
        )
        viewModel.open()
        runCurrent()
        repeat(4) {
            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
        }

        viewModel.confirmAndSubmitAll()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
        assertEquals(5, uploads.requests.size)
        assertTrue(enrollment.commands.isEmpty())
        assertEquals(
            "安全指令没有保存；八段本地录音已清理，请全部重新录制",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue(audio.all { captured -> captured.wavBytes.all { it == 0.toByte() } })
    }

    @Test
    fun acousticConflictKeepsAllRecordingsForTargetedRetake() = runTest(dispatcher) {
        val audio = List(8) { sineAudio(1_200, 7_000 + it * 100) }
        val enrollment = FakeEnrollmentRepository(
            failure = AuthApiException(422, "四类指令不容易区分，请重新录制"),
        )
        val coordinator = FakeLocalVoiceTemplateCoordinator()
        val viewModel = viewModel(
            capture = FakeAudioCapturePort(ArrayDeque(audio)),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            enrollment = enrollment,
            localCoordinator = coordinator,
        )
        viewModel.open()
        runCurrent()
        repeat(4) {
            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
        }

        viewModel.confirmAndSubmitAll()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.REVIEW_ALL, viewModel.uiState.value.stage)
        assertEquals(1, enrollment.commands.size)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("八段录音已保留"))
        assertEquals(4, coordinator.preparedCandidates.size)
        assertTrue(
            coordinator.preparedCandidates.all { candidate ->
                candidate.material.any { it != 0.toByte() }
            },
        )
    }

    @Test
    fun localPersistenceFailureAfterServerCommitReconcilesStaleTemplates() = runTest(dispatcher) {
        val audio = List(8) { sineAudio(1_200, 7_000 + it * 100) }
        val coordinator = FakeLocalVoiceTemplateCoordinator(failReplacement = true)
        val enrollment = FakeEnrollmentRepository()
        val viewModel = viewModel(
            capture = FakeAudioCapturePort(ArrayDeque(audio)),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            enrollment = enrollment,
            localCoordinator = coordinator,
        )
        viewModel.open()
        runCurrent()
        repeat(4) {
            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
        }

        viewModel.confirmAndSubmitAll()
        runCurrent()

        assertEquals(SafetyCommandEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
        assertEquals(1, enrollment.commands.size)
        assertEquals(2, coordinator.reconcileCount)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("服务端已保存"))
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("旧本机模板已停用"))
        assertTrue(audio.all { captured -> captured.wavBytes.all { it == 0.toByte() } })
    }

    @Test
    fun leavingAfterServerCommitStillReconcilesStaleTemplates() = runTest(dispatcher) {
        val coordinator = FakeLocalVoiceTemplateCoordinator()
        lateinit var viewModel: SafetyCommandEnrollmentViewModel
        val enrollment = FakeEnrollmentRepository(afterCommit = { viewModel.leave() })
        viewModel = viewModel(
            capture = FakeAudioCapturePort(
                ArrayDeque(List(8) { sineAudio(1_200, 7_000 + it * 100) }),
            ),
            uploads = FakeAudioUploadRepository(),
            consent = FakeConsentRepository(granted = true),
            enrollment = enrollment,
            localCoordinator = coordinator,
        )
        viewModel.open()
        runCurrent()
        repeat(4) {
            recordOnce(viewModel)
            recordOnce(viewModel)
            viewModel.confirmCurrentCommand()
        }

        viewModel.confirmAndSubmitAll()
        runCurrent()

        assertEquals(1, enrollment.commands.size)
        assertEquals(2, coordinator.reconcileCount)
        assertEquals(SafetyCommandEnrollmentStage.IDLE, viewModel.uiState.value.stage)
    }

    private fun viewModel(
        capture: AudioCapturePort,
        uploads: AudioUploadRepository,
        consent: ConsentRepository,
        enrollment: SafetyCommandEnrollmentRepository = FakeEnrollmentRepository(),
        localCoordinator: LocalVoiceTemplateCoordinator = FakeLocalVoiceTemplateCoordinator(),
        playbackPort: AudioPlaybackPort = FakeAudioPlaybackPort(),
    ) = SafetyCommandEnrollmentViewModel(
        audioCapturePort = capture,
        audioPlaybackPort = playbackPort,
        recordingNormalizer = VoiceTemplateRecordingNormalizer(),
        audioUploadRepository = uploads,
        consentRepository = consent,
        enrollmentRepository = enrollment,
        localVoiceTemplateCoordinator = localCoordinator,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.recordOnce(
        viewModel: SafetyCommandEnrollmentViewModel,
    ) {
        viewModel.startRecording()
        runCurrent()
        viewModel.finishRecording()
        runCurrent()
    }

    private class FakeAudioCapturePort(
        private val recordings: ArrayDeque<CapturedAudio>,
        private val startFailure: Exception? = null,
        private val stopFailure: Exception? = null,
    ) : AudioCapturePort {
        private val mutableState = MutableStateFlow(AudioCaptureState.STOPPED)
        override val state: StateFlow<AudioCaptureState> = mutableState
        var startCount = 0

        override suspend fun start(maxDurationMs: Int) {
            startCount++
            startFailure?.let { throw it }
            mutableState.value = AudioCaptureState.CAPTURING
        }

        override suspend fun stop(): CapturedAudio {
            mutableState.value = AudioCaptureState.STOPPED
            stopFailure?.let { throw it }
            return recordings.removeFirst()
        }

        override suspend fun cancel() {
            mutableState.value = AudioCaptureState.STOPPED
        }
    }

    private class FakeAudioPlaybackPort(
        private val failure: Exception? = null,
    ) : AudioPlaybackPort {
        private val mutableState = MutableStateFlow(AudioPlaybackState.STOPPED)
        override val state: StateFlow<AudioPlaybackState> = mutableState

        override suspend fun play(wavBytes: ByteArray) {
            mutableState.value = AudioPlaybackState.PLAYING
            failure?.let {
                mutableState.value = AudioPlaybackState.FAILED
                throw it
            }
            mutableState.value = AudioPlaybackState.STOPPED
        }

        override suspend fun stop() = Unit

        override fun stopImmediately() = Unit
    }

    private data class UploadRequest(val purpose: AudioPurpose)

    private class FakeAudioUploadRepository(
        private val failOnRequest: Int? = null,
    ) : AudioUploadRepository {
        val requests = mutableListOf<UploadRequest>()

        override suspend fun upload(
            purpose: AudioPurpose,
            mediaType: CreateAudioUploadTicketRequest.MediaType,
            durationMs: Int,
            audioContent: ByteArray,
        ): String {
            requests += UploadRequest(purpose)
            if (requests.size == failOnRequest) error("upload client crashed")
            return "au_${requests.size}"
        }
    }

    private class FakeConsentRepository(
        granted: Boolean,
        private val listFailure: Throwable? = null,
        private val updateFailure: Throwable? = null,
    ) : ConsentRepository {
        private var isGranted = granted
        val updates = mutableListOf<Pair<ConsentType, String>>()

        override suspend fun listCurrent(): List<Consent> {
            listFailure?.let { throw it }
            return if (isGranted) {
                listOf(consent())
            } else {
                emptyList()
            }
        }

        override suspend fun update(
            type: ConsentType,
            decision: ConsentDecision,
            policyVersion: String,
        ): Consent {
            updateFailure?.let { throw it }
            updates += type to policyVersion
            isGranted = decision == ConsentDecision.GRANTED
            return consent()
        }

        private fun consent() = Consent(
            type = ConsentType.VOICE_TEMPLATE,
            decision = ConsentDecision.GRANTED,
            policyVersion = "voice-template-v1",
            decidedAt = OffsetDateTime.parse("2026-08-13T01:00:00Z"),
        )
    }

    private class FakeEnrollmentRepository(
        private val afterCommit: () -> Unit = {},
        private val failure: Throwable? = null,
    ) : SafetyCommandEnrollmentRepository {
        val commands = mutableListOf<List<SafetyCommandAudioObjects>>()

        override suspend fun enroll(
            commands: List<SafetyCommandAudioObjects>,
            consentPolicyVersion: String,
        ): List<VoiceTemplateSummary> {
            this.commands += commands
            failure?.let { throw it }
            val summaries = commands.mapIndexed { index, command ->
                VoiceTemplateSummary(
                    templateId = "vt_$index",
                    category = VoiceTemplateSummary.Category.SAFETY_COMMAND,
                    dialectCode = "zh-Hans-CN-x-wugang",
                    dialectPackageVersion = "1.0.0",
                    modelVersion = "mfcc-dtw-1",
                    thresholdVersion = "test-1",
                    compatibility = AliasCompatibility.COMPATIBLE,
                    updatedAt = OffsetDateTime.parse("2026-08-13T01:00:00Z"),
                    safetyCommandType = command.type,
                )
            }
            afterCommit()
            return summaries
        }
    }

    private class FakeLocalVoiceTemplateCoordinator(
        private val existingTypes: Set<SafetyCommandType> = emptySet(),
        private val serverTypes: Set<SafetyCommandType> = existingTypes,
        private val failReplacement: Boolean = false,
        private val failPrepareCalls: MutableSet<Int> = mutableSetOf(),
    ) : LocalVoiceTemplateCoordinator {
        val loadedTypes = mutableListOf<SafetyCommandType>()
        val loadedMaterials = mutableListOf<ByteArray>()
        val preparedCandidates = mutableListOf<LocalVoiceTemplateCandidate>()
        var reconcileCount = 0
        var prepareCount = 0

        override fun prepare(firstWav: ByteArray, secondWav: ByteArray): LocalVoiceTemplateCandidate {
            prepareCount++
            if (prepareCount in failPrepareCalls) {
                throw LocalVoiceTemplateException("两遍发音不一致，请重新录制")
            }
            return LocalVoiceTemplateCandidate(
                "zh-Hans-CN-x-wugang",
                "1.0.0",
                "mfcc-dtw-1",
                "test-1",
                byteArrayOf(1, 2, 3),
            ).also(preparedCandidates::add)
        }

        override suspend fun persistAlias(
            contactId: String,
            alias: com.aifriend.contract.model.ContactAlias,
            candidate: LocalVoiceTemplateCandidate,
        ) = Unit

        override suspend fun deleteAliasMaterial(aliasId: String) = Unit

        override suspend fun replaceSafetyCommands(
            summaries: List<VoiceTemplateSummary>,
            candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
        ) {
            if (failReplacement) error("本机模板写入失败")
        }

        override suspend fun reconcile(): LocalTemplateReconciliation {
            reconcileCount++
            return LocalTemplateReconciliation(
                availableTemplateIds = emptySet(),
                missingTemplateIds = emptySet(),
                removedLocalTemplateIds = emptySet(),
                serverSafetyCommandTypes = serverTypes,
                availableSafetyCommandTypes = existingTypes,
            )
        }

        override suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate? {
            loadedTypes += type
            if (type !in existingTypes) return null
            val material = byteArrayOf(1, 2, 3)
            loadedMaterials += material
            return LocalAvailableVoiceTemplate(
                templateId = "existing_${type.name}",
                type = type,
                candidate = LocalVoiceTemplateCandidate(
                    "zh-Hans-CN-x-wugang",
                    "1.0.0",
                    "mfcc-dtw-1",
                    "test-1",
                    material,
                ),
            )
        }
    }

    private companion object {
        val requiredSafetyTypes = linkedSetOf(
            SafetyCommandType.CONFIRM_SEND,
            SafetyCommandType.CONFIRM_CALL,
            SafetyCommandType.CANCEL,
            SafetyCommandType.REJECT_RETRY,
        )

        fun sineAudio(durationMs: Int, amplitude: Int): CapturedAudio {
            val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                    .toInt()
                    .toShort()
            }
            val pcmBytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            val wav = WavPcmCodec.encodeMono16(pcmBytes)
            pcmBytes.fill(0)
            return CapturedAudio(wav, durationMs)
        }
    }
}
