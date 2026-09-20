package com.aifriend.feature.settings

import com.aifriend.core.settings.FontLevel
import com.aifriend.core.settings.SpeechRatePreference
import com.aifriend.core.settings.SpeechVolumePreference
import com.aifriend.core.settings.UserSettings
import com.aifriend.core.settings.UserSettingsRepository
import com.aifriend.core.voice.DialectPackageRegistry
import com.aifriend.contract.model.Consent
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.feature.consent.ConsentRepository
import com.aifriend.feature.personalization.AmbiguousCallChoice
import com.aifriend.feature.personalization.DialogueStyleChoice
import com.aifriend.feature.personalization.PersonalMemoryChoices
import com.aifriend.feature.personalization.PersonalMemoryRepository
import com.aifriend.feature.personalization.PersonalMemorySnapshot
import com.aifriend.feature.wechat.WechatSampleCaptureCoordinator
import com.aifriend.feature.wechat.WechatSampleCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationFingerprintProvider
import com.aifriend.feature.wechat.WechatCalibrationCaptureCoordinator
import com.aifriend.feature.wechat.WechatCalibrationCaptureUiState
import com.aifriend.feature.wechat.WechatCalibrationProfile
import com.aifriend.feature.wechat.WechatCalibrationProfileKey
import com.aifriend.feature.wechat.WechatCalibrationProfileRegistry
import com.aifriend.feature.wechat.WechatCalibrationOrientation
import com.aifriend.feature.wechat.WechatCalibrationTarget
import com.aifriend.feature.wechat.WechatNormalizedCalibrationPoint
import com.aifriend.feature.wechat.WechatRuntimeVersionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 设置状态刷新和音量保存接线测试。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refreshReplacesCapabilityFactsWithoutClaimingWechatExecution() = runTest(dispatcher) {
        val first = capabilityStatus(CapabilityReadState.UNAVAILABLE)
        val second = capabilityStatus(CapabilityReadState.AVAILABLE)
        val reader = MutableCapabilityReader(first)
        val viewModel = SettingsViewModel(
            settingsRepository = FakeSettingsRepository(),
            dialectPackageRegistry = DialectPackageRegistry { null },
            capabilityReader = reader,
            routineCommandDeletionRepository = FakeRoutineCommandDeletionRepository(),
            wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
            wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { null },
            wechatCalibrationFingerprintProvider = WechatCalibrationFingerprintProvider { null },
            wechatCalibrationProfileRegistry = EmptyWechatCalibrationProfileRegistry,
            wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
        )
        runCurrent()
        assertEquals(first, viewModel.uiState.value.capabilities)
        assertEquals(false, viewModel.uiState.value.dialectPackageAvailable)

        reader.status = second
        viewModel.refreshCapabilityStatus()
        runCurrent()

        assertEquals(second, viewModel.uiState.value.capabilities)
    }

    @Test
    fun volumeSelectionPersistsOnlyThroughOwnerSettingsPort() = runTest(dispatcher) {
        val repository = FakeSettingsRepository()
        val viewModel = SettingsViewModel(
            settingsRepository = repository,
            dialectPackageRegistry = DialectPackageRegistry { null },
            capabilityReader = MutableCapabilityReader(capabilityStatus()),
            routineCommandDeletionRepository = FakeRoutineCommandDeletionRepository(),
            wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
            wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { null },
            wechatCalibrationFingerprintProvider = WechatCalibrationFingerprintProvider { null },
            wechatCalibrationProfileRegistry = EmptyWechatCalibrationProfileRegistry,
            wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
        )
        runCurrent()

        viewModel.setSpeechVolume(SpeechVolumePreference.LOUD)
        runCurrent()

        assertEquals(SpeechVolumePreference.LOUD, repository.settings.value.speechVolume)
        assertTrue(repository.volumeUpdates == 1)
    }

    @Test
    fun routineCommandDeletionRequiresSecondConfirmation() = runTest(dispatcher) {
        val deletionRepository = FakeRoutineCommandDeletionRepository(deletedCount = 3)
        val viewModel = SettingsViewModel(
            settingsRepository = FakeSettingsRepository(),
            dialectPackageRegistry = DialectPackageRegistry { null },
            capabilityReader = MutableCapabilityReader(capabilityStatus()),
            routineCommandDeletionRepository = deletionRepository,
            wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
            wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { null },
            wechatCalibrationFingerprintProvider = WechatCalibrationFingerprintProvider { null },
            wechatCalibrationProfileRegistry = EmptyWechatCalibrationProfileRegistry,
            wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
        )
        runCurrent()

        viewModel.confirmRoutineCommandDeletion()
        runCurrent()
        assertEquals(0, deletionRepository.clearCount)

        viewModel.requestRoutineCommandDeletion()
        runCurrent()
        assertEquals(
            RoutineCommandDeletionUiState.Confirming,
            viewModel.uiState.value.routineCommandDeletion,
        )
        viewModel.confirmRoutineCommandDeletion()
        runCurrent()

        assertEquals(1, deletionRepository.clearCount)
        assertEquals(
            RoutineCommandDeletionUiState.Completed(3),
            viewModel.uiState.value.routineCommandDeletion,
        )
    }

    @Test
    fun routineCommandOverviewLoadsCountsAndClearsOnlyAfterServerSuccess() =
        runTest(dispatcher) {
            val overview = RoutineCommandTemplateOverview(
                totalCount = 4,
                sendMessageCount = 2,
                voiceCallCount = 1,
                videoCallCount = 1,
                compatibleCount = 3,
            )
            val repository = FakeRoutineCommandDeletionRepository(
                deletedCount = 4,
                overview = overview,
            )
            val viewModel = SettingsViewModel(
                settingsRepository = FakeSettingsRepository(),
                dialectPackageRegistry = DialectPackageRegistry { null },
                capabilityReader = MutableCapabilityReader(capabilityStatus()),
                routineCommandDeletionRepository = repository,
                wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
                wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { null },
                wechatCalibrationFingerprintProvider =
                    WechatCalibrationFingerprintProvider { null },
                wechatCalibrationProfileRegistry = EmptyWechatCalibrationProfileRegistry,
                wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
            )
            runCurrent()

            assertEquals(
                RoutineCommandListUiState.Ready(overview),
                viewModel.uiState.value.routineCommands,
            )
            viewModel.requestRoutineCommandDeletion()
            viewModel.confirmRoutineCommandDeletion()
            runCurrent()

            assertEquals(
                RoutineCommandListUiState.Ready(RoutineCommandTemplateOverview.Empty),
                viewModel.uiState.value.routineCommands,
            )
            assertEquals(1, repository.listCount)
            assertEquals(1, repository.clearCount)
        }

    @Test
    fun calibrationProfileStateUsesExactCurrentFingerprintAndCanRemoveIt() =
        runTest(dispatcher) {
            val key = WechatCalibrationProfileKey(
                manufacturer = "vivo",
                model = "V2536A",
                androidSdkInt = 36,
                displayWidthPixels = 1260,
                displayHeightPixels = 2800,
                densityDpi = 480,
                fontScalePermille = 1_000,
                orientation = WechatCalibrationOrientation.PORTRAIT,
                wechatVersion = "8.0.76",
            )
            val profile = WechatCalibrationProfile(
                key = key,
                points = WechatCalibrationTarget.entries.associateWith {
                    WechatNormalizedCalibrationPoint(500_000, 500_000)
                },
                updatedAtEpochMillis = 1L,
            )
            val registry = MutableWechatCalibrationProfileRegistry(profile)
            val viewModel = SettingsViewModel(
                settingsRepository = FakeSettingsRepository(),
                dialectPackageRegistry = DialectPackageRegistry { null },
                capabilityReader = MutableCapabilityReader(capabilityStatus()),
                routineCommandDeletionRepository = FakeRoutineCommandDeletionRepository(),
                wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
                wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { "8.0.76" },
                wechatCalibrationFingerprintProvider =
                    WechatCalibrationFingerprintProvider { key },
                wechatCalibrationProfileRegistry = registry,
                wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
            )
            runCurrent()

            assertTrue(viewModel.wechatCalibrationProfiles.value.currentProfileAvailable)
            assertEquals(1, viewModel.wechatCalibrationProfiles.value.profiles.size)

            viewModel.removeWechatCalibrationProfile(key)

            assertEquals(0, viewModel.wechatCalibrationProfiles.value.profiles.size)
            assertEquals(false, viewModel.wechatCalibrationProfiles.value.currentProfileAvailable)
        }

    @Test
    fun personalMemoryRequiresSeparateConsentBeforeSave() = runTest(dispatcher) {
        val memory = FakePersonalMemoryRepository(
            PersonalMemorySnapshot(
                featureEnabled = true,
                consentGranted = false,
                policyVersion = "personal-memory-v1",
                choices = null,
                version = null,
            ),
        )
        val consent = FakeSettingsConsentRepository()
        val viewModel = personalMemoryViewModel(memory, consent)
        runCurrent()

        viewModel.setPersonalMemoryDialogueStyle(DialogueStyleChoice.BRIEF)
        viewModel.setPersonalMemoryAmbiguousCall(AmbiguousCallChoice.VIDEO)
        viewModel.savePersonalMemory()
        runCurrent()
        assertEquals(0, memory.updateCount)

        viewModel.grantPersonalMemoryConsent()
        runCurrent()
        assertEquals(listOf(ConsentDecision.GRANTED), consent.decisions)
        assertTrue(viewModel.personalMemory.value.consentGranted)

        viewModel.savePersonalMemory()
        runCurrent()
        assertEquals(1, memory.updateCount)
        assertEquals(DialogueStyleChoice.BRIEF, memory.lastUpdated?.dialogueStyle)
        assertEquals(AmbiguousCallChoice.VIDEO, memory.lastUpdated?.ambiguousCall)
    }

    @Test
    fun personalMemoryDeletionRequiresSecondConfirmation() = runTest(dispatcher) {
        val memory = FakePersonalMemoryRepository(activePersonalMemorySnapshot())
        val viewModel = personalMemoryViewModel(memory, FakeSettingsConsentRepository())
        runCurrent()

        viewModel.confirmPersonalMemoryDeletion()
        runCurrent()
        assertEquals(0, memory.deleteCount)

        viewModel.requestPersonalMemoryDeletion()
        assertTrue(viewModel.personalMemory.value.deletionConfirmationRequested)
        viewModel.confirmPersonalMemoryDeletion()
        runCurrent()

        assertEquals(1, memory.deleteCount)
        assertEquals(false, viewModel.personalMemory.value.savedChoicesPresent)
    }

    @Test
    fun personalMemoryRevocationRequiresSecondConfirmationAndStopsUse() =
        runTest(dispatcher) {
            val memory = FakePersonalMemoryRepository(activePersonalMemorySnapshot())
            val consent = FakeSettingsConsentRepository()
            val viewModel = personalMemoryViewModel(memory, consent)
            runCurrent()

            viewModel.confirmPersonalMemoryRevocation()
            runCurrent()
            assertTrue(consent.decisions.isEmpty())

            viewModel.requestPersonalMemoryRevocation()
            assertTrue(viewModel.personalMemory.value.revocationConfirmationRequested)
            viewModel.confirmPersonalMemoryRevocation()
            runCurrent()

            assertEquals(listOf(ConsentDecision.REVOKED), consent.decisions)
            assertEquals(false, viewModel.personalMemory.value.consentGranted)
            assertTrue(memory.invalidateCount >= 1)
        }

    private fun personalMemoryViewModel(
        memory: PersonalMemoryRepository,
        consent: ConsentRepository,
    ) = SettingsViewModel(
        settingsRepository = FakeSettingsRepository(),
        dialectPackageRegistry = DialectPackageRegistry { null },
        capabilityReader = MutableCapabilityReader(capabilityStatus()),
        routineCommandDeletionRepository = FakeRoutineCommandDeletionRepository(),
        wechatSampleCaptureCoordinator = FakeWechatSampleCaptureCoordinator(),
        wechatRuntimeVersionProvider = WechatRuntimeVersionProvider { null },
        wechatCalibrationFingerprintProvider = WechatCalibrationFingerprintProvider { null },
        wechatCalibrationProfileRegistry = EmptyWechatCalibrationProfileRegistry,
        wechatCalibrationCaptureCoordinator = FakeWechatCalibrationCaptureCoordinator(),
        personalMemoryRepository = memory,
        consentRepository = consent,
    )

    private fun activePersonalMemorySnapshot() = PersonalMemorySnapshot(
        featureEnabled = true,
        consentGranted = true,
        policyVersion = "personal-memory-v1",
        choices = PersonalMemoryChoices(
            speechRate = SpeechRatePreference.SLOW,
            dialogueStyle = DialogueStyleChoice.BRIEF,
            ambiguousCall = AmbiguousCallChoice.VIDEO,
        ),
        version = 3L,
    )
    private fun capabilityStatus(
        state: CapabilityReadState = CapabilityReadState.UNKNOWN,
    ) = SettingsCapabilityStatus(
        network = state,
        microphone = state,
        notifications = state,
        restrictedWechatAccessibility = state,
        accountSession = state,
        batteryOptimizationExemption = state,
    )
}

private object EmptyWechatCalibrationProfileRegistry : WechatCalibrationProfileRegistry {
    override fun list(): List<WechatCalibrationProfile> = emptyList()

    override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? = null

    override fun upsert(profile: WechatCalibrationProfile): Boolean = false

    override fun remove(key: WechatCalibrationProfileKey): Boolean = true
}

private class FakeWechatCalibrationCaptureCoordinator : WechatCalibrationCaptureCoordinator {
    override val state = MutableStateFlow(WechatCalibrationCaptureUiState())

    override fun start(): Boolean = false

    override fun cancel() = Unit

    override fun failToOpenWechat() = Unit
}

private class MutableWechatCalibrationProfileRegistry(
    profile: WechatCalibrationProfile,
) : WechatCalibrationProfileRegistry {
    private val profiles = linkedMapOf(profile.key to profile)

    override fun list(): List<WechatCalibrationProfile> = profiles.values.toList()

    override fun findExact(key: WechatCalibrationProfileKey): WechatCalibrationProfile? =
        profiles[key]

    override fun upsert(profile: WechatCalibrationProfile): Boolean {
        profiles[profile.key] = profile
        return true
    }

    override fun remove(key: WechatCalibrationProfileKey): Boolean {
        profiles.remove(key)
        return true
    }
}

private class FakeWechatSampleCaptureCoordinator : WechatSampleCaptureCoordinator {
    private val mutableState = MutableStateFlow(
        WechatSampleCaptureUiState(visible = false),
    )
    override val state = mutableState

    override fun begin(): Boolean = false

    override fun refresh() = Unit

    override fun failToOpenWechat() = Unit
}

private class MutableCapabilityReader(
    var status: SettingsCapabilityStatus,
) : SettingsCapabilityReader {
    override fun read(): SettingsCapabilityStatus = status
}

private class FakeRoutineCommandDeletionRepository(
    private val deletedCount: Int = 0,
    private val overview: RoutineCommandTemplateOverview =
        RoutineCommandTemplateOverview.Empty,
) : RoutineCommandDeletionRepository {
    var clearCount = 0
    var listCount = 0

    override suspend fun list(): RoutineCommandTemplateOverview {
        listCount++
        return overview
    }

    override suspend fun clear(): Int {
        clearCount++
        return deletedCount
    }
}

private class FakeSettingsRepository : UserSettingsRepository {
    override val settings = MutableStateFlow(UserSettings())
    var volumeUpdates = 0

    override suspend fun current(): UserSettings = settings.value

    override suspend fun updateSpeechRate(value: SpeechRatePreference) {
        settings.value = settings.value.copy(speechRate = value)
    }

    override suspend fun updateSpeechVolume(value: SpeechVolumePreference) {
        volumeUpdates++
        settings.value = settings.value.copy(speechVolume = value)
    }

    override suspend fun updateFontLevel(value: FontLevel) {
        settings.value = settings.value.copy(fontLevel = value)
    }

    override suspend fun updateHighContrast(enabled: Boolean) {
        settings.value = settings.value.copy(highContrast = enabled)
    }

    override suspend fun clearAll() {
        settings.value = UserSettings()
    }
}
private class FakePersonalMemoryRepository(
    initial: PersonalMemorySnapshot,
) : PersonalMemoryRepository {
    private var snapshot = initial
    var updateCount = 0
    var deleteCount = 0
    var invalidateCount = 0
    var lastUpdated: PersonalMemoryChoices? = null

    override suspend fun read(): PersonalMemorySnapshot = snapshot

    override suspend fun update(
        choices: PersonalMemoryChoices,
        expectedVersion: Long,
    ): PersonalMemorySnapshot {
        updateCount++
        lastUpdated = choices
        snapshot = snapshot.copy(
            consentGranted = true,
            choices = choices,
            version = (snapshot.version ?: 0L) + 1L,
        )
        return snapshot
    }

    override suspend fun delete(expectedVersion: Long): PersonalMemorySnapshot {
        deleteCount++
        snapshot = snapshot.copy(choices = null, version = expectedVersion + 1L)
        return snapshot
    }

    override fun currentPromptChoices(): PersonalMemoryChoices =
        snapshot.activeChoicesOrNull() ?: PersonalMemoryChoices.SafeDefault

    override fun invalidate() {
        invalidateCount++
    }
}

private class FakeSettingsConsentRepository : ConsentRepository {
    val decisions = mutableListOf<ConsentDecision>()

    override suspend fun listCurrent(): List<Consent> = emptyList()

    override suspend fun update(
        type: ConsentType,
        decision: ConsentDecision,
        policyVersion: String,
    ): Consent {
        decisions += decision
        return Consent(
            type = type,
            decision = decision,
            policyVersion = policyVersion,
            decidedAt = java.time.OffsetDateTime.parse("2026-09-04T00:00:00Z"),
        )
    }
}