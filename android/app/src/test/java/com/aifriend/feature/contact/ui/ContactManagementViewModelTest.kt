package com.aifriend.feature.contact.ui

import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.ContactPage
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.PageMetadata
import com.aifriend.contract.model.SafetyCommandType
import com.aifriend.contract.model.VoiceTemplateSummary
import com.aifriend.core.voice.LocalAvailableVoiceTemplate
import com.aifriend.core.voice.LocalTemplateReconciliation
import com.aifriend.core.voice.LocalVoiceTemplateCandidate
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.feature.contact.ContactRepository
import com.aifriend.feature.contact.LocalWechatPageType
import com.aifriend.feature.contact.LocalWechatVerificationEvidence
import com.aifriend.feature.wechat.WechatLocalContactVerificationCoordinator
import com.aifriend.feature.wechat.WechatLocalVerificationCaptureState
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactManagementViewModelTest {

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
    fun loadContactsHidesRevokedBindings() = runTest(dispatcher) {
        val repository = FakeContactRepository(
            contacts = listOf(
                contact(id = "active", status = ContactStatus.ACTIVE_NO_ALIAS),
                contact(id = "revoked", status = ContactStatus.REVOKED),
            ),
        )
        val viewModel = ContactManagementViewModel(repository, FakeLocalVoiceTemplateCoordinator())

        viewModel.loadContacts()
        advanceUntilIdle()

        assertEquals(listOf("active"), viewModel.uiState.value.contacts.map(Contact::id))
        assertEquals(false, viewModel.uiState.value.isInitialLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun existingAliasIsUsedWhenWechatProfileNamesAreMissing() {
        val alias = ContactAlias(
            id = "al_1",
            displayText = "老大",
            dialectCode = "zh-Hans-CN-x-wugang",
            dialectPackageVersion = "basic-experience-v1",
            modelVersion = "mfcc-dtw-basic-v1",
            thresholdVersion = "basic-personal-v1",
            compatibility = com.aifriend.contract.model.AliasCompatibility.COMPATIBLE,
            createdAt = NOW,
        )
        val contact = contact(id = "active").copy(
            remark = null,
            displayName = null,
            aliasCount = 1,
            aliases = listOf(alias),
        )

        assertEquals("老大", contact.userFacingLabel())
    }

    @Test
    fun prepareDemoContactAddsReturnedContactAndShowsNextStep() = runTest(dispatcher) {
        val demo = contact(id = "demo", status = ContactStatus.ACTIVE_NO_ALIAS)
            .copy(remark = "体验联系人")
        val repository = FakeContactRepository(
            contacts = emptyList(),
            demoContact = demo,
        )
        val viewModel = ContactManagementViewModel(repository, FakeLocalVoiceTemplateCoordinator())

        viewModel.prepareDemoContact()
        advanceUntilIdle()

        assertEquals(listOf("demo"), viewModel.uiState.value.contacts.map(Contact::id))
        assertEquals(false, viewModel.uiState.value.isPreparingDemoContact)
        assertEquals(
            "体验联系人已准备好，请先设置称呼",
            viewModel.uiState.value.informationMessage,
        )
    }

    @Test
    fun loadFailureHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = ContactManagementViewModel(
            FakeContactRepository(
                contacts = emptyList(),
                listFailure = IllegalStateException("socket timeout"),
            ),
            FakeLocalVoiceTemplateCoordinator(),
        )

        viewModel.loadContacts()
        advanceUntilIdle()

        assertEquals("联系人加载失败，请稍后重试", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun prepareDemoFailureHidesTechnicalMessage() = runTest(dispatcher) {
        val viewModel = ContactManagementViewModel(
            FakeContactRepository(
                contacts = emptyList(),
                demoFailure = IllegalStateException("HTTP 503"),
            ),
            FakeLocalVoiceTemplateCoordinator(),
        )

        viewModel.prepareDemoContact()
        advanceUntilIdle()

        assertEquals(
            "体验联系人准备失败，请稍后重试",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun unbindRequiresBothConfirmationsAndUsesLoadedVersion() = runTest(dispatcher) {
        val repository = FakeContactRepository(
            contacts = listOf(contact(id = "target", version = 7)),
        )
        val viewModel = ContactManagementViewModel(repository, FakeLocalVoiceTemplateCoordinator())
        viewModel.loadContacts()
        advanceUntilIdle()

        viewModel.requestUnbind("target")
        viewModel.confirmUnbind()
        advanceUntilIdle()
        assertEquals(0, repository.unbindRequests.size)

        viewModel.continueUnbindConfirmation()
        viewModel.confirmUnbind()
        advanceUntilIdle()

        assertEquals(listOf(UnbindRequest("target", 7)), repository.unbindRequests)
        assertEquals(emptyList<Contact>(), viewModel.uiState.value.contacts)
        assertEquals("已解除与二女儿的绑定", viewModel.uiState.value.informationMessage)
    }

    @Test
    fun unbindFailureKeepsContactAndHidesTechnicalMessage() = runTest(dispatcher) {
        val repository = FakeContactRepository(
            contacts = listOf(contact(id = "target")),
            unbindFailure = IllegalStateException("connection reset"),
        )
        val viewModel = ContactManagementViewModel(repository, FakeLocalVoiceTemplateCoordinator())
        viewModel.loadContacts()
        advanceUntilIdle()

        viewModel.requestUnbind("target")
        viewModel.continueUnbindConfirmation()
        viewModel.confirmUnbind()
        advanceUntilIdle()

        assertEquals(listOf("target"), viewModel.uiState.value.contacts.map(Contact::id))
        assertEquals(
            "解除绑定失败，请稍后重试",
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun completeDemoPrerequisitesOpenTaskGate() = runTest(dispatcher) {
        val demo = contact(id = "demo", status = ContactStatus.ACTIVE)
            .copy(
                relationship = DEBUG_DEMO_RELATIONSHIP,
                aliasCount = 1,
            )
        val viewModel = ContactManagementViewModel(
            FakeContactRepository(contacts = listOf(demo)),
            FakeLocalVoiceTemplateCoordinator(SafetyCommandType.entries.toSet()),
        )

        viewModel.loadContacts()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.safetyCommandsReady)
        assertTrue(state.debugDemoReadiness().taskReady)
        assertFalse(state.demoReadinessCheckFailed)
    }

    @Test
    fun missingSafetyCommandKeepsTaskGateClosed() = runTest(dispatcher) {
        val demo = contact(id = "demo", status = ContactStatus.ACTIVE)
            .copy(
                relationship = DEBUG_DEMO_RELATIONSHIP,
                aliasCount = 1,
            )
        val availableTypes = SafetyCommandType.entries
            .filterNot { it == SafetyCommandType.REJECT_RETRY }
            .toSet()
        val viewModel = ContactManagementViewModel(
            FakeContactRepository(contacts = listOf(demo)),
            FakeLocalVoiceTemplateCoordinator(availableTypes),
        )

        viewModel.loadContacts()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.safetyCommandsReady)
        assertFalse(state.debugDemoReadiness().taskReady)
    }

    @Test
    fun contactUpdateClosesAllDemoActions() {
        val readiness = ContactManagementUiState(
            contacts = listOf(
                contact(id = "demo", status = ContactStatus.ACTIVE).copy(
                    relationship = DEBUG_DEMO_RELATIONSHIP,
                    aliasCount = 1,
                ),
            ),
            safetyCommandsReady = true,
        ).debugDemoReadiness()

        val actions = readiness.actionAvailability(
            isContactStateUpdating = true,
            isPreparing = false,
            isCheckingReadiness = false,
        )

        assertFalse(actions.canPrepareContact)
        assertFalse(actions.canManageAlias)
        assertFalse(actions.canManageSafetyCommands)
        assertFalse(actions.canStartTask)
    }

    @Test
    fun idleDemoCardOpensOnlyCurrentStep() {
        val actions = ContactManagementUiState()
            .debugDemoReadiness()
            .actionAvailability(
                isContactStateUpdating = false,
                isPreparing = false,
                isCheckingReadiness = false,
            )

        assertTrue(actions.canPrepareContact)
        assertFalse(actions.canManageAlias)
        assertFalse(actions.canManageSafetyCommands)
        assertFalse(actions.canStartTask)
    }

    @Test
    fun matchingLocalVerificationUsesListedPublicVersionAndOpensAliasState() = runTest(dispatcher) {
        val pending = contact(
            id = "pending",
            status = ContactStatus.PENDING_LOCAL_VERIFY,
            version = 1,
        )
        val verified = pending.copy(status = ContactStatus.ACTIVE_NO_ALIAS, version = 2)
        val repository = FakeContactRepository(
            contacts = listOf(pending),
            verificationResult = verified,
        )
        val coordinator = FakeLocalVerificationCoordinator()
        val viewModel = ContactManagementViewModel(
            repository,
            FakeLocalVoiceTemplateCoordinator(),
            coordinator,
        )
        viewModel.loadContacts()
        advanceUntilIdle()

        viewModel.startLocalVerification(pending.id)
        coordinator.markReady()
        advanceUntilIdle()
        viewModel.confirmLocalVerification()
        advanceUntilIdle()

        assertEquals(listOf(1L), repository.verificationRequests.map {
            it.expectedContactVersion
        })
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, viewModel.uiState.value.contacts.single().status)
        assertNull(viewModel.uiState.value.localVerification)
        assertTrue(viewModel.uiState.value.informationMessage?.contains("设置称呼") == true)
    }

    @Test
    fun localVerificationWindowAutomaticallyRefreshesAfterOneMinute() = runTest(dispatcher) {
        val pending = contact(
            id = "pending",
            status = ContactStatus.PENDING_LOCAL_VERIFY,
            version = 4,
        )
        val coordinator = FakeLocalVerificationCoordinator()
        val viewModel = ContactManagementViewModel(
            FakeContactRepository(contacts = listOf(pending)),
            FakeLocalVoiceTemplateCoordinator(),
            coordinator,
        )
        viewModel.loadContacts()
        advanceUntilIdle()
        viewModel.startLocalVerification(pending.id)

        assertTrue(viewModel.beginLocalVerificationObservation())
        advanceTimeBy(61_000L)
        advanceUntilIdle()

        assertEquals(1, coordinator.refreshCount)
        assertFalse(viewModel.uiState.value.localVerification?.capture?.awaiting == true)
    }

    private data class UnbindRequest(
        val contactId: String,
        val expectedVersion: Long,
    )

    private class FakeContactRepository(
        private val contacts: List<Contact>,
        private val unbindFailure: Throwable? = null,
        private val listFailure: Throwable? = null,
        private val demoFailure: Throwable? = null,
        private val verificationResult: Contact? = null,
        private val demoContact: Contact = contact(
            id = "demo",
            status = ContactStatus.ACTIVE_NO_ALIAS,
        ),
    ) : ContactRepository {
        val unbindRequests = mutableListOf<UnbindRequest>()
        val verificationRequests = mutableListOf<LocalWechatVerificationEvidence>()

        override suspend fun ensureDebugDemoContact(): Contact {
            demoFailure?.let { throw it }
            return demoContact
        }

        override suspend fun list(
            page: Int,
            size: Int,
            status: ContactStatus?,
        ): ContactPage {
            listFailure?.let { throw it }
            return ContactPage(
                items = contacts,
                page = PageMetadata(
                    page = page,
                    propertySize = size,
                    totalElements = contacts.size.toLong(),
                    totalPages = if (contacts.isEmpty()) 0 else 1,
                ),
            )
        }

        override suspend fun verifyLocalWechatContact(
            contactId: String,
            evidence: LocalWechatVerificationEvidence,
        ): Contact {
            verificationRequests += evidence
            return verificationResult ?: error("not used")
        }

        override suspend fun createAlias(
            contactId: String,
            displayText: String,
            phoneticHint: String?,
            firstAudioObjectId: String,
            secondAudioObjectId: String,
            expectedContactVersion: Long,
        ): com.aifriend.contract.model.ContactAlias = error("not used")

        override suspend fun deleteAlias(
            contactId: String,
            aliasId: String,
            expectedContactVersion: Long,
        ): Contact = error("not used")

        override suspend fun unbindContact(
            contactId: String,
            expectedContactVersion: Long,
        ): Contact {
            unbindRequests += UnbindRequest(contactId, expectedContactVersion)
            unbindFailure?.let { throw it }
            return contacts.first { it.id == contactId }.copy(
                status = ContactStatus.REVOKED,
                version = expectedContactVersion + 1,
            )
        }
    }

    private class FakeLocalVerificationCoordinator :
        WechatLocalContactVerificationCoordinator {
        private val mutableState = MutableStateFlow(WechatLocalVerificationCaptureState())
        override val state: StateFlow<WechatLocalVerificationCaptureState> =
            mutableState.asStateFlow()
        var refreshCount = 0

        override fun start() {
            mutableState.value = WechatLocalVerificationCaptureState(
                active = true,
                message = "开始",
            )
        }

        fun markReady() {
            mutableState.value = WechatLocalVerificationCaptureState(
                active = true,
                completedObservations = 3,
                ready = true,
                wechatVersion = "8.0.50",
                message = "已准备",
            )
        }

        override fun beginObservation(): Boolean {
            mutableState.value = mutableState.value.copy(
                awaiting = true,
                message = "等待中",
            )
            return true
        }

        override fun refresh() {
            refreshCount++
            mutableState.value = mutableState.value.copy(
                awaiting = false,
                message = "已超时",
            )
        }

        override fun failToOpenWechat() = Unit

        override fun buildEvidence(expectedContactVersion: Long) =
            LocalWechatVerificationEvidence(
                stableLocator = "wxid_family_123",
                currentRemark = null,
                pageType = LocalWechatPageType.CONTACT_PROFILE,
                friendConfirmed = true,
                locatorObservationCount = 1,
                locatorUnique = true,
                wechatVersion = "8.0.50",
                ruleVersion = "wechat-contact-profile-v1",
                verifiedAt = NOW,
                expectedContactVersion = expectedContactVersion,
            )

        override fun cancel() {
            mutableState.value = WechatLocalVerificationCaptureState()
        }
    }

    private class FakeLocalVoiceTemplateCoordinator(
        private val availableTypes: Set<SafetyCommandType> = emptySet(),
    ) : LocalVoiceTemplateCoordinator {
        override fun prepare(
            firstWav: ByteArray,
            secondWav: ByteArray,
        ): LocalVoiceTemplateCandidate = error("not used")

        override suspend fun persistAlias(
            contactId: String,
            alias: ContactAlias,
            candidate: LocalVoiceTemplateCandidate,
        ) = error("not used")

        override suspend fun deleteAliasMaterial(aliasId: String) = error("not used")

        override suspend fun replaceSafetyCommands(
            summaries: List<VoiceTemplateSummary>,
            candidates: Map<SafetyCommandType, LocalVoiceTemplateCandidate>,
        ) = error("not used")

        override suspend fun reconcile(): LocalTemplateReconciliation = error("not used")

        override suspend fun loadSafetyCommand(
            type: SafetyCommandType,
        ): LocalAvailableVoiceTemplate? {
            if (type !in availableTypes) return null
            return LocalAvailableVoiceTemplate(
                templateId = "template-${type.value}",
                type = type,
                candidate = LocalVoiceTemplateCandidate(
                    dialectCode = "wugang",
                    dialectPackageVersion = "debug",
                    modelVersion = "debug",
                    thresholdVersion = "debug",
                    material = byteArrayOf(1),
                ),
            )
        }
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-08-09T08:00:00Z")

        fun contact(
            id: String,
            status: ContactStatus = ContactStatus.ACTIVE,
            version: Long = 1,
        ): Contact = Contact(
            id = id,
            status = status,
            aliasCount = 0,
            version = version,
            createdAt = NOW.minusDays(1),
            updatedAt = NOW,
            remark = "二女儿",
            aliases = emptyList(),
        )
    }
}
