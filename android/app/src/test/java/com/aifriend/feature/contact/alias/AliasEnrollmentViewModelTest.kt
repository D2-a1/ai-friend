package com.aifriend.feature.contact.alias

import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.AudioPurpose
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.ContactPage
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.contract.model.CreateAudioUploadTicketRequest
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
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.feature.audio.AudioUploadRepository
import com.aifriend.feature.contact.ContactRepository
import com.aifriend.feature.contact.LocalWechatVerificationEvidence
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
 * 称呼双录状态机和一次性上传编排测试。
 *
 * @author codex
 * @since 2026-08-12
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AliasEnrollmentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @Test
    fun missingVoiceTemplateConsentBlocksRecordingUntilExplicitGrant() =
        runTest(dispatcher) {
            val consent = FakeConsentRepository(granted = false)
            val capturePort = FakeAudioCapturePort(ArrayDeque())
            val viewModel = viewModel(
                capturePort = capturePort,
                uploadRepository = FakeAudioUploadRepository(),
                contactRepository = FakeContactRepository(),
                consentRepository = consent,
            )

            viewModel.open(contact())
            runCurrent()

            assertEquals(AliasEnrollmentStage.CONSENT_REQUIRED, viewModel.uiState.value.stage)
            viewModel.startRecording()
            runCurrent()
            assertEquals(0, capturePort.startCount)

            viewModel.grantVoiceTemplateConsent()
            runCurrent()

            assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
            assertEquals(
                listOf(ConsentType.VOICE_TEMPLATE to "voice-template-v1"),
                consent.updates,
            )
        }

    @Test
    fun staleVoiceTemplatePolicyRequiresFreshExplicitGrant() =
        runTest(dispatcher) {
            val consent = FakeConsentRepository(
                granted = true,
                policyVersion = "voice-template-v0",
            )
            val viewModel = viewModel(
                capturePort = FakeAudioCapturePort(ArrayDeque()),
                uploadRepository = FakeAudioUploadRepository(),
                contactRepository = FakeContactRepository(),
                consentRepository = consent,
            )

            viewModel.open(contact())
            runCurrent()

            assertEquals(AliasEnrollmentStage.CONSENT_REQUIRED, viewModel.uiState.value.stage)
        }

    @Test
    fun microphoneDenialOffersSettingsRecoveryAndSuccessfulStartClearsIt() =
        runTest(dispatcher) {
            val viewModel = viewModel(
                FakeAudioCapturePort(ArrayDeque(listOf(sineAudio(1_300, 7_000)))),
                FakeAudioUploadRepository(),
                FakeContactRepository(),
            )
            viewModel.open(contact())
            runCurrent()

            viewModel.onMicrophonePermissionDenied()
            assertTrue(viewModel.uiState.value.microphonePermissionRecoveryRequired)

            viewModel.startRecording()
            runCurrent()
            assertFalse(viewModel.uiState.value.microphonePermissionRecoveryRequired)
        }

    @Test
    fun permissionRevokedDuringStartReturnsToReadyAndOffersSettingsRecovery() =
        runTest(dispatcher) {
            val capturePort = FakeAudioCapturePort(
                recordings = ArrayDeque(),
                startFailure = AudioCaptureException(
                    AudioCaptureFailure.PERMISSION_DENIED,
                    "permission revoked",
                ),
            )
            val viewModel = viewModel(
                capturePort,
                FakeAudioUploadRepository(),
                FakeContactRepository(),
            )
            viewModel.open(contact())
            runCurrent()

            viewModel.startRecording()
            runCurrent()

            assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
            assertTrue(viewModel.uiState.value.microphonePermissionRecoveryRequired)
            assertEquals(
                "麦克风权限已关闭，请先在系统设置中允许",
                viewModel.uiState.value.errorMessage,
            )
        }

    @Test
    fun recordingReadFailureReturnsToReadyWithoutUploadingPartialAudio() =
        runTest(dispatcher) {
            val capturePort = FakeAudioCapturePort(
                recordings = ArrayDeque(),
                stopFailure = AudioCaptureException(
                    AudioCaptureFailure.READ_FAILED,
                    "native read failed",
                ),
            )
            val uploadRepository = FakeAudioUploadRepository()
            val viewModel = viewModel(
                capturePort,
                uploadRepository,
                FakeContactRepository(),
            )
            viewModel.open(contact())
            runCurrent()

            viewModel.startRecording()
            runCurrent()
            viewModel.finishRecording()
            runCurrent()

            assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
            assertEquals("录音被中断，请重新录制", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.microphonePermissionRecoveryRequired)
            assertTrue(uploadRepository.requests.isEmpty())
        }

    @Test
    fun playbackFailureKeepsRecordingAndReturnsToRetryableState() =
        runTest(dispatcher) {
            val playbackPort = FakeAudioPlaybackPort(
                failure = IllegalStateException("native playback failed"),
            )
            val uploadRepository = FakeAudioUploadRepository()
            val viewModel = viewModel(
                capturePort = FakeAudioCapturePort(
                    ArrayDeque(listOf(sineAudio(1_300, 7_000))),
                ),
                uploadRepository = uploadRepository,
                contactRepository = FakeContactRepository(),
                playbackPort = playbackPort,
            )
            viewModel.open(contact())
            runCurrent()
            recordOnce(viewModel)

            viewModel.play(AliasRecordingSlot.FIRST)
            runCurrent()

            assertEquals(AliasEnrollmentStage.FIRST_RECORDED, viewModel.uiState.value.stage)
            assertEquals("录音没有播放完整，请重新试听", viewModel.uiState.value.errorMessage)
            assertEquals(null, viewModel.uiState.value.playingRecording)
            assertTrue(uploadRepository.requests.isEmpty())
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun twoValidRecordingsRequireExplicitSubmitBeforeCreatingAlias() = runTest(dispatcher) {
        val first = sineAudio(1_300, 7_000)
        val second = sineAudio(1_400, 7_500)
        val capturePort = FakeAudioCapturePort(ArrayDeque(listOf(first, second)))
        val uploadRepository = FakeAudioUploadRepository()
        val contactRepository = FakeContactRepository()
        val viewModel = viewModel(capturePort, uploadRepository, contactRepository)

        viewModel.open(contact())
        runCurrent()
        viewModel.updateDisplayText("二女儿")
        recordOnce(viewModel)
        assertEquals(AliasEnrollmentStage.FIRST_RECORDED, viewModel.uiState.value.stage)
        recordOnce(viewModel)
        assertEquals(AliasEnrollmentStage.REVIEW, viewModel.uiState.value.stage)
        assertTrue(contactRepository.createRequests.isEmpty())

        viewModel.confirmAndSubmit()
        runCurrent()

        assertEquals(AliasEnrollmentStage.COMPLETED, viewModel.uiState.value.stage)
        assertEquals(2, uploadRepository.requests.size)
        assertEquals(listOf("au_1", "au_2"), contactRepository.createRequests.single().audioObjectIds)
        assertEquals(7L, contactRepository.createRequests.single().expectedVersion)
        assertTrue(uploadRepository.requests.all { request ->
            request.bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF"
        })
        assertTrue(first.wavBytes.all { it == 0.toByte() })
        assertTrue(second.wavBytes.all { it == 0.toByte() })
    }

    @Test
    fun inconsistentSecondTakeKeepsFirstAndOnlyRequiresSecondTakeAgain() =
        runTest(dispatcher) {
            val recordings = ArrayDeque(
                listOf(
                    sineAudio(1_300, 7_000),
                    sineAudio(1_300, 7_200),
                    sineAudio(1_300, 7_100),
                ),
            )
            val uploads = FakeAudioUploadRepository()
            val coordinator = FakeLocalVoiceTemplateCoordinator(
                failPrepareCalls = mutableSetOf(1),
            )
            val viewModel = viewModel(
                FakeAudioCapturePort(recordings),
                uploads,
                FakeContactRepository(),
                coordinator,
            )
            viewModel.open(contact())
            runCurrent()

            recordOnce(viewModel)
            recordOnce(viewModel)

            assertEquals(AliasEnrollmentStage.FIRST_RECORDED, viewModel.uiState.value.stage)
            assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("两遍发音不一致"))
            assertTrue(uploads.requests.isEmpty())

            recordOnce(viewModel)

            assertEquals(AliasEnrollmentStage.REVIEW, viewModel.uiState.value.stage)
            assertEquals(2, coordinator.prepareCount)
        }

    @Test
    fun savedAliasUpdatesVersionAndCanContinueWithAnotherAlias() = runTest(dispatcher) {
        val recordings = ArrayDeque(
            listOf(
                sineAudio(1_300, 7_000),
                sineAudio(1_400, 7_500),
                sineAudio(1_300, 7_000),
                sineAudio(1_400, 7_500),
            ),
        )
        val contactRepository = FakeContactRepository()
        val viewModel = viewModel(
            FakeAudioCapturePort(recordings),
            FakeAudioUploadRepository(),
            contactRepository,
        )

        viewModel.open(contact())
        runCurrent()
        viewModel.updateDisplayText("二女儿")
        recordOnce(viewModel)
        recordOnce(viewModel)
        viewModel.confirmAndSubmit()
        runCurrent()

        assertEquals(listOf("二女儿"), viewModel.uiState.value.existingAliases.map { it.displayText })
        assertTrue(viewModel.uiState.value.canAddAnotherAlias)
        viewModel.continueAfterCompletion()
        assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.displayText.isEmpty())

        viewModel.updateDisplayText("满妹")
        recordOnce(viewModel)
        recordOnce(viewModel)
        viewModel.confirmAndSubmit()
        runCurrent()

        assertEquals(listOf(7L, 8L), contactRepository.createRequests.map { it.expectedVersion })
        assertEquals(listOf("二女儿", "满妹"), viewModel.uiState.value.existingAliases.map { it.displayText })
    }

    @Test
    fun secondUploadFailureClearsBothRecordingsAndNeverAutoRetries() = runTest(dispatcher) {
        val first = sineAudio(1_200, 7_000)
        val second = sineAudio(1_200, 7_000)
        val capturePort = FakeAudioCapturePort(ArrayDeque(listOf(first, second)))
        val uploadRepository = FakeAudioUploadRepository(failOnRequestNumber = 2)
        val contactRepository = FakeContactRepository()
        val viewModel = viewModel(capturePort, uploadRepository, contactRepository)

        viewModel.open(contact())
        runCurrent()
        viewModel.updateDisplayText("二女儿")
        recordOnce(viewModel)
        recordOnce(viewModel)
        viewModel.confirmAndSubmit()
        runCurrent()

        assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
        assertEquals(2, uploadRepository.requests.size)
        assertTrue(contactRepository.createRequests.isEmpty())
        assertEquals(
            "称呼没有保存；本次本地录音已清理",
            viewModel.uiState.value.errorMessage,
        )
        assertTrue(first.wavBytes.all { it == 0.toByte() })
        assertTrue(second.wavBytes.all { it == 0.toByte() })
    }

    @Test
    fun localPersistenceFailureAfterServerCommitRequiresDeletingServerAlias() = runTest(dispatcher) {
        val first = sineAudio(1_200, 7_000)
        val second = sineAudio(1_200, 7_000)
        val capturePort = FakeAudioCapturePort(ArrayDeque(listOf(first, second)))
        val contactRepository = FakeContactRepository()
        val viewModel = viewModel(
            capturePort = capturePort,
            uploadRepository = FakeAudioUploadRepository(),
            contactRepository = contactRepository,
            localCoordinator = FakeLocalVoiceTemplateCoordinator(failAliasPersistence = true),
        )

        viewModel.open(contact())
        runCurrent()
        viewModel.updateDisplayText("二女儿")
        recordOnce(viewModel)
        recordOnce(viewModel)
        viewModel.confirmAndSubmit()
        runCurrent()

        assertEquals(1, contactRepository.createRequests.size)
        assertTrue(viewModel.uiState.value.errorMessage.orEmpty().contains("请先删除该称呼"))
        assertTrue(first.wavBytes.all { it == 0.toByte() })
        assertTrue(second.wavBytes.all { it == 0.toByte() })
    }

    @Test
    fun leavingPageCancelsCaptureAndClearsRecordedBytes() = runTest(dispatcher) {
        val first = sineAudio(1_200, 7_000)
        val capturePort = FakeAudioCapturePort(ArrayDeque(listOf(first)))
        val viewModel = viewModel(
            capturePort,
            FakeAudioUploadRepository(),
            FakeContactRepository(),
        )

        viewModel.open(contact())
        runCurrent()
        recordOnce(viewModel)
        viewModel.leave()
        runCurrent()

        assertEquals(AliasEnrollmentStage.IDLE, viewModel.uiState.value.stage)
        assertTrue(first.wavBytes.all { it == 0.toByte() })
        assertTrue(capturePort.cancelCount >= 2)
    }

    @Test
    fun contactWithoutLocalVerificationFailsClosedBeforeRecording() = runTest(dispatcher) {
        val capturePort = FakeAudioCapturePort(ArrayDeque())
        val viewModel = viewModel(
            capturePort,
            FakeAudioUploadRepository(),
            FakeContactRepository(),
        )

        viewModel.open(contact(status = ContactStatus.PENDING_LOCAL_VERIFY))
        runCurrent()
        viewModel.startRecording()
        runCurrent()

        assertEquals(AliasEnrollmentStage.UNAVAILABLE, viewModel.uiState.value.stage)
        assertEquals(0, capturePort.startCount)
    }

    @Test
    fun incompatibleServerAliasIsExposedToTheManagementScreen() = runTest(dispatcher) {
        val oldContact = contactWithAlias()
        val oldAliases = oldContact.aliases.orEmpty().map { alias ->
            alias.copy(compatibility = AliasCompatibility.INCOMPATIBLE)
        }
        val viewModel = viewModel(
            FakeAudioCapturePort(ArrayDeque()),
            FakeAudioUploadRepository(),
            FakeContactRepository(),
        )

        viewModel.open(oldContact.copy(aliases = oldAliases))
        runCurrent()

        assertFalse(viewModel.uiState.value.existingAliases.single().compatible)
    }

    @Test
    fun deletingAliasRequiresConfirmationAndStopsLocalMatchingBeforeServerDeletion() =
        runTest(dispatcher) {
            val events = mutableListOf<String>()
            val contactRepository = FakeContactRepository(events)
            val localCoordinator = FakeLocalVoiceTemplateCoordinator(deletionEvents = events)
            val viewModel = viewModel(
                FakeAudioCapturePort(ArrayDeque()),
                FakeAudioUploadRepository(),
                contactRepository,
                localCoordinator,
            )

            viewModel.open(contactWithAlias())
            runCurrent()
            viewModel.requestAliasDeletion("al_1")

            assertEquals("al_1", viewModel.uiState.value.pendingAliasDeletion?.id)
            assertTrue(events.isEmpty())
            viewModel.confirmAliasDeletion()
            runCurrent()

            assertEquals(listOf("local:al_1", "server:al_1"), events)
            assertTrue(viewModel.uiState.value.existingAliases.isEmpty())
            assertEquals(AliasEnrollmentStage.READY_FIRST, viewModel.uiState.value.stage)
            assertTrue(viewModel.uiState.value.informationMessage.orEmpty().contains("已删除"))
        }

    @Test
    fun localAliasDeletionFailureNeverCallsServer() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val contactRepository = FakeContactRepository(events)
        val viewModel = viewModel(
            FakeAudioCapturePort(ArrayDeque()),
            FakeAudioUploadRepository(),
            contactRepository,
            FakeLocalVoiceTemplateCoordinator(
                failAliasDeletion = true,
                deletionEvents = events,
            ),
        )

        viewModel.open(contactWithAlias())
        runCurrent()
        viewModel.requestAliasDeletion("al_1")
        viewModel.confirmAliasDeletion()
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(listOf("al_1"), viewModel.uiState.value.existingAliases.map { it.id })
        assertEquals("称呼没有删除，请稍后重试", viewModel.uiState.value.errorMessage)
    }

    private fun viewModel(
        capturePort: AudioCapturePort,
        uploadRepository: AudioUploadRepository,
        contactRepository: ContactRepository,
        localCoordinator: LocalVoiceTemplateCoordinator = FakeLocalVoiceTemplateCoordinator(),
        playbackPort: AudioPlaybackPort = FakeAudioPlaybackPort(),
        consentRepository: ConsentRepository = FakeConsentRepository(granted = true),
    ): AliasEnrollmentViewModel = AliasEnrollmentViewModel(
        audioCapturePort = capturePort,
        audioPlaybackPort = playbackPort,
        recordingNormalizer = VoiceTemplateRecordingNormalizer(),
        audioUploadRepository = uploadRepository,
        consentRepository = consentRepository,
        contactRepository = contactRepository,
        localVoiceTemplateCoordinator = localCoordinator,
    )

    private class FakeConsentRepository(
        granted: Boolean,
        private var policyVersion: String = "voice-template-v1",
        private val listFailure: Throwable? = null,
        private val updateFailure: Throwable? = null,
    ) : ConsentRepository {
        private var isGranted = granted
        val updates = mutableListOf<Pair<ConsentType, String>>()

        override suspend fun listCurrent(): List<Consent> {
            listFailure?.let { throw it }
            return if (isGranted) listOf(consent()) else emptyList()
        }

        override suspend fun update(
            type: ConsentType,
            decision: ConsentDecision,
            policyVersion: String,
        ): Consent {
            updateFailure?.let { throw it }
            updates += type to policyVersion
            this.policyVersion = policyVersion
            isGranted = decision == ConsentDecision.GRANTED
            return consent()
        }

        private fun consent() = Consent(
            type = ConsentType.VOICE_TEMPLATE,
            decision = ConsentDecision.GRANTED,
            policyVersion = policyVersion,
            decidedAt = OffsetDateTime.parse("2026-09-06T00:00:00Z"),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.recordOnce(
        viewModel: AliasEnrollmentViewModel,
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
        var cancelCount = 0

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
            cancelCount++
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

        override suspend fun stop() {
            mutableState.value = AudioPlaybackState.STOPPED
        }

        override fun stopImmediately() {
            mutableState.value = AudioPlaybackState.STOPPED
        }
    }

    private data class UploadRequest(
        val purpose: AudioPurpose,
        val mediaType: CreateAudioUploadTicketRequest.MediaType,
        val durationMs: Int,
        val bytes: ByteArray,
    )

    private class FakeAudioUploadRepository(
        private val failOnRequestNumber: Int? = null,
    ) : AudioUploadRepository {
        val requests = mutableListOf<UploadRequest>()

        override suspend fun upload(
            purpose: AudioPurpose,
            mediaType: CreateAudioUploadTicketRequest.MediaType,
            durationMs: Int,
            audioContent: ByteArray,
        ): String {
            requests += UploadRequest(purpose, mediaType, durationMs, audioContent.copyOf())
            if (requests.size == failOnRequestNumber) error("upload client crashed")
            return "au_${requests.size}"
        }
    }

    private data class CreateAliasCall(
        val audioObjectIds: List<String>,
        val expectedVersion: Long,
    )

    private class FakeContactRepository(
        private val deletionEvents: MutableList<String>? = null,
    ) : ContactRepository {
        val createRequests = mutableListOf<CreateAliasCall>()

        override suspend fun ensureDebugDemoContact(): Contact = error("not used")

        override suspend fun list(page: Int, size: Int, status: ContactStatus?): ContactPage =
            error("not used")

        override suspend fun verifyLocalWechatContact(
            contactId: String,
            evidence: LocalWechatVerificationEvidence,
        ): Contact = error("not used")

        override suspend fun createAlias(
            contactId: String,
            displayText: String,
            phoneticHint: String?,
            firstAudioObjectId: String,
            secondAudioObjectId: String,
            expectedContactVersion: Long,
        ): ContactAlias {
            createRequests += CreateAliasCall(
                audioObjectIds = listOf(firstAudioObjectId, secondAudioObjectId),
                expectedVersion = expectedContactVersion,
            )
            return ContactAlias(
                id = "al_${createRequests.size}",
                displayText = displayText,
                dialectCode = "zh-Hans-CN-x-wugang",
                dialectPackageVersion = "1.0.0",
                modelVersion = "mfcc-dtw-1",
                thresholdVersion = "test-1",
                compatibility = AliasCompatibility.COMPATIBLE,
                createdAt = NOW,
            )
        }

        override suspend fun deleteAlias(
            contactId: String,
            aliasId: String,
            expectedContactVersion: Long,
        ): Contact {
            deletionEvents?.add("server:$aliasId")
            return contact(status = ContactStatus.ACTIVE_NO_ALIAS).copy(
                id = contactId,
                version = expectedContactVersion + 1,
            )
        }

        override suspend fun unbindContact(
            contactId: String,
            expectedContactVersion: Long,
        ): Contact = error("not used")
    }

    private class FakeLocalVoiceTemplateCoordinator(
        private val failAliasPersistence: Boolean = false,
        private val failAliasDeletion: Boolean = false,
        private val deletionEvents: MutableList<String>? = null,
        private val failPrepareCalls: MutableSet<Int> = mutableSetOf(),
    ) : LocalVoiceTemplateCoordinator {
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
            )
        }

        override suspend fun persistAlias(
            contactId: String,
            alias: ContactAlias,
            candidate: LocalVoiceTemplateCandidate,
        ) {
            if (failAliasPersistence) error("Room unavailable")
        }

        override suspend fun deleteAliasMaterial(aliasId: String) {
            if (failAliasDeletion) error("Room delete failed")
            deletionEvents?.add("local:$aliasId")
        }

        override suspend fun replaceSafetyCommands(
            summaries: List<VoiceTemplateSummary>,
            candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
        ) = Unit

        override suspend fun reconcile() = LocalTemplateReconciliation(emptySet(), emptySet(), emptySet())

        override suspend fun loadSafetyCommand(type: SafetyCommandType): LocalAvailableVoiceTemplate? = null
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-12T08:00:00Z")

        fun contact(status: ContactStatus = ContactStatus.ACTIVE_NO_ALIAS): Contact = Contact(
            id = "ct_0123456789abcdef0123456789abcdef",
            status = status,
            aliasCount = 0,
            version = 7,
            createdAt = NOW.minusDays(1),
            updatedAt = NOW,
            remark = "二女儿",
            aliases = emptyList(),
        )

        fun contactWithAlias(): Contact = contact(status = ContactStatus.ACTIVE).copy(
            aliasCount = 1,
            aliases = listOf(
                ContactAlias(
                    id = "al_1",
                    displayText = "二女儿",
                    dialectCode = "zh-Hans-CN-x-wugang",
                    dialectPackageVersion = "1.0.0",
                    modelVersion = "mfcc-dtw-1",
                    thresholdVersion = "test-1",
                    compatibility = AliasCompatibility.COMPATIBLE,
                    createdAt = NOW,
                ),
            ),
        )

        fun sineAudio(durationMs: Int, amplitude: Int): CapturedAudio {
            val samples = ShortArray(WavPcmCodec.SAMPLE_RATE * durationMs / 1_000) { index ->
                (sin(2.0 * PI * 220.0 * index / WavPcmCodec.SAMPLE_RATE) * amplitude)
                    .toInt()
                    .toShort()
            }
            val pcmBytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(pcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(samples)
            val wavBytes = WavPcmCodec.encodeMono16(pcmBytes)
            pcmBytes.fill(0)
            return CapturedAudio(wavBytes, durationMs)
        }
    }
}
