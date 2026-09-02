package com.aifriend.feature.contact.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactStatus
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.core.voice.hasCompleteSafetyCommands
import com.aifriend.feature.contact.ContactRepository
import com.aifriend.feature.wechat.WechatLocalContactVerificationCoordinator
import com.aifriend.feature.wechat.WechatLocalVerificationCaptureState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 联系人列表加载、刷新和解除绑定确认编排。
 *
 * @author codex
 * @since 2026-08-09
 */
@HiltViewModel
class ContactManagementViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val localVoiceTemplateCoordinator: LocalVoiceTemplateCoordinator,
    private val localVerificationCoordinator: WechatLocalContactVerificationCoordinator =
        DisabledWechatLocalContactVerificationCoordinator,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow(
        ContactManagementUiState(isInitialLoading = true),
    )
    val uiState: StateFlow<ContactManagementUiState> = mutableUiState.asStateFlow()

    private var hasLoaded = false
    private var loadInProgress = false

    init {
        viewModelScope.launch {
            localVerificationCoordinator.state.collect { capture ->
                val current = mutableUiState.value
                val pending = current.localVerification ?: return@collect
                mutableUiState.value = current.copy(
                    localVerification = pending.copy(capture = capture),
                )
            }
        }
    }

    /**
     * 首次进入页面时加载联系人。重复进入会重新读取，避免展示上一登录会话的缓存。
     */
    fun loadContacts() {
        loadContacts(clearCurrentContacts = true)
    }

    /**
     * 用户主动刷新联系人。刷新期间保留当前列表，失败时也不清空已展示数据。
     */
    fun refreshContacts() {
        loadContacts(clearCurrentContacts = false)
    }

    /** 选择等待本机验证的真实联系人；尚不打开微信或发起网络请求。 */
    fun startLocalVerification(contactId: String) {
        val state = mutableUiState.value
        if (loadInProgress || state.localVerification != null || state.unbindingContactId != null) {
            return
        }
        val contact = state.contacts.firstOrNull {
            it.id == contactId && it.status in LOCAL_VERIFICATION_STATUSES
        } ?: return
        localVerificationCoordinator.start()
        mutableUiState.value = state.copy(
            errorMessage = null,
            informationMessage = null,
            localVerification = PendingContactLocalVerification(
                contactId = contact.id,
                contactLabel = contact.userFacingLabel(),
                sourceContactVersion = contact.version,
                capture = localVerificationCoordinator.state.value,
            ),
        )
    }

    /** 为下一遍验证打开一次一分钟只读窗口。 */
    fun beginLocalVerificationObservation(): Boolean {
        val pending = mutableUiState.value.localVerification ?: return false
        if (pending.isSubmitting || pending.capture.awaiting || pending.capture.ready) return false
        val started = localVerificationCoordinator.beginObservation()
        if (started) {
            viewModelScope.launch {
                delay(LOCAL_VERIFICATION_TIMEOUT_REFRESH_MILLIS)
                localVerificationCoordinator.refresh()
            }
        }
        return started
    }

    /** 从微信返回前台时只检查窗口是否超时，不自动重新打开或提交。 */
    fun refreshLocalVerification() {
        if (mutableUiState.value.localVerification == null) return
        localVerificationCoordinator.refresh()
    }

    /** 微信无法启动时关闭当前观察窗口并给出中文恢复提示。 */
    fun failToOpenWechatForLocalVerification() {
        if (mutableUiState.value.localVerification == null) return
        localVerificationCoordinator.failToOpenWechat()
    }

    /**
     * 一次资料页确认后由用户明确提交。失败不自动重试，并保留本轮证据供用户明确重试。
     */
    fun confirmLocalVerification() {
        val state = mutableUiState.value
        val pending = state.localVerification
            ?.takeIf { it.capture.ready && !it.isSubmitting }
            ?: return
        val contact = state.contacts.firstOrNull {
            it.id == pending.contactId &&
                it.version == pending.sourceContactVersion &&
                it.status in LOCAL_VERIFICATION_STATUSES
        }
        if (contact == null) {
            cancelLocalVerification()
            mutableUiState.value = mutableUiState.value.copy(
                errorMessage = "联系人状态已经变化，请刷新后重试",
            )
            return
        }
        // Contact.version is already the public optimistic-lock version returned by the server.
        // The server compares it with binding.version + 1, so incrementing it again would make
        // every real local-verification submission fail with SESSION_CONFLICT.
        val evidence = localVerificationCoordinator.buildEvidence(contact.version)
        if (evidence == null) {
            mutableUiState.value = state.copy(
                errorMessage = "联系人确认信息不完整，请重新打开微信确认",
            )
            return
        }
        mutableUiState.value = state.copy(
            errorMessage = null,
            informationMessage = null,
            localVerification = pending.copy(isSubmitting = true),
        )
        viewModelScope.launch {
            runCatching {
                contactRepository.verifyLocalWechatContact(contact.id, evidence)
            }.onSuccess { result ->
                if (result.status != ContactStatus.ACTIVE_NO_ALIAS) {
                    mutableUiState.value = mutableUiState.value.copy(
                        localVerification = mutableUiState.value.localVerification?.copy(
                            isSubmitting = false,
                        ),
                        errorMessage = "本机验证结果无法确认，请刷新联系人状态",
                    )
                    return@onSuccess
                }
                val current = mutableUiState.value
                mutableUiState.value = current.copy(
                    contacts = current.contacts.map { existing ->
                        if (existing.id == result.id) result else existing
                    },
                    localVerification = null,
                    informationMessage = "已完成与${pending.contactLabel}的本机验证，请继续设置称呼",
                )
                localVerificationCoordinator.cancel()
            }.onFailure { throwable ->
                val current = mutableUiState.value
                mutableUiState.value = current.copy(
                    localVerification = current.localVerification?.copy(isSubmitting = false),
                    errorMessage = throwable.toChineseUserMessage("本机验证失败，请稍后重试"),
                )
            }
        }
    }

    /** 取消只清除当前进程的瞬时证据，不修改联系人状态。 */
    fun cancelLocalVerification() {
        mutableUiState.value = mutableUiState.value.copy(localVerification = null)
        localVerificationCoordinator.cancel()
    }

    /**
     * 明确请求后端准备唯一体验联系人。只由 Android Debug 页面调用。
     */
    fun prepareDemoContact() {
        val state = mutableUiState.value
        if (loadInProgress || state.isPreparingDemoContact
            || state.unbindingContactId != null || state.localVerification != null
        ) {
            return
        }
        mutableUiState.value = state.copy(
            isPreparingDemoContact = true,
            errorMessage = null,
            informationMessage = null,
        )
        viewModelScope.launch {
            runCatching { contactRepository.ensureDebugDemoContact() }
                .onSuccess { contact ->
                    val current = mutableUiState.value
                    val contacts = listOf(contact) + current.contacts.filterNot {
                        it.id == contact.id
                    }
                    mutableUiState.value = current.copy(
                        contacts = contacts,
                        isPreparingDemoContact = false,
                        informationMessage = if (contact.aliasCount == 0) {
                            "体验联系人已准备好，请先设置称呼"
                        } else {
                            "体验联系人已准备好，可以继续录制安全指令或测试任务"
                        },
                    )
                    refreshDemoReadiness()
                }
                .onFailure { throwable ->
                    mutableUiState.value = mutableUiState.value.copy(
                        isPreparingDemoContact = false,
                        errorMessage = throwable.toChineseUserMessage(
                            "体验联系人准备失败，请稍后重试",
                        ),
                    )
                }
        }
    }

    /**
     * 开始解除绑定确认，只允许选择当前列表中的非撤销联系人。
     *
     * @param contactId 当前页面联系人 ID
     */
    fun requestUnbind(contactId: String) {
        val state = mutableUiState.value
        if (state.unbindingContactId != null || state.localVerification != null) return
        val contact = state.contacts.firstOrNull {
            it.id == contactId && it.status != ContactStatus.REVOKED
        } ?: return
        mutableUiState.value = state.copy(
            errorMessage = null,
            informationMessage = null,
            pendingUnbind = PendingContactUnbind(
                contactId = contact.id,
                contactLabel = contact.userFacingLabel(),
                expectedContactVersion = contact.version,
                stage = ContactUnbindConfirmationStage.WARNING,
            ),
        )
    }

    /**
     * 从风险提示进入最终确认，尚不调用网络接口。
     */
    fun continueUnbindConfirmation() {
        val pending = mutableUiState.value.pendingUnbind
            ?.takeIf { it.stage == ContactUnbindConfirmationStage.WARNING }
            ?: return
        mutableUiState.value = mutableUiState.value.copy(
            pendingUnbind = pending.copy(
                stage = ContactUnbindConfirmationStage.FINAL_CONFIRMATION,
            ),
        )
    }

    /**
     * 用户完成第二次确认后提交解除绑定。失败时保留联系人，不排队、不自动重试。
     */
    fun confirmUnbind() {
        val state = mutableUiState.value
        val pending = state.pendingUnbind
            ?.takeIf { it.stage == ContactUnbindConfirmationStage.FINAL_CONFIRMATION }
            ?: return
        if (state.unbindingContactId != null) return
        if (state.contacts.none {
                it.id == pending.contactId && it.version == pending.expectedContactVersion
            }
        ) {
            mutableUiState.value = state.copy(
                pendingUnbind = null,
                errorMessage = "联系人状态已经变化，请刷新后重试",
            )
            return
        }
        mutableUiState.value = state.copy(
            pendingUnbind = null,
            unbindingContactId = pending.contactId,
            errorMessage = null,
            informationMessage = null,
        )
        viewModelScope.launch {
            runCatching {
                contactRepository.unbindContact(
                    contactId = pending.contactId,
                    expectedContactVersion = pending.expectedContactVersion,
                )
            }.onSuccess { result ->
                if (result.status != ContactStatus.REVOKED) {
                    mutableUiState.value = mutableUiState.value.copy(
                        unbindingContactId = null,
                        errorMessage = "解除绑定结果无法确认，请刷新后重试",
                    )
                    return@onSuccess
                }
                mutableUiState.value = mutableUiState.value.copy(
                    contacts = mutableUiState.value.contacts.filterNot { it.id == pending.contactId },
                    unbindingContactId = null,
                    informationMessage = "已解除与${pending.contactLabel}的绑定",
                )
            }.onFailure { throwable ->
                mutableUiState.value = mutableUiState.value.copy(
                    unbindingContactId = null,
                    errorMessage = throwable.toChineseUserMessage("解除绑定失败，请稍后重试"),
                )
            }
        }
    }

    /**
     * 取消当前解除绑定确认，不产生网络请求。
     */
    fun cancelUnbind() {
        mutableUiState.value = mutableUiState.value.copy(pendingUnbind = null)
    }

    /**
     * 关闭页面消息。
     */
    fun dismissMessage() {
        mutableUiState.value = mutableUiState.value.copy(
            errorMessage = null,
            informationMessage = null,
        )
    }

    /**
     * 重新读取体验流程所需的本机四类安全指令状态。
     *
     * 检查失败只关闭体验任务入口，不影响联系人列表和其他正式业务。
     */
    fun refreshDemoReadiness() {
        val state = mutableUiState.value
        if (state.isCheckingDemoReadiness) return
        if (state.contacts.none { it.relationship == DEBUG_DEMO_RELATIONSHIP }) {
            mutableUiState.value = state.copy(
                isCheckingDemoReadiness = false,
                safetyCommandsReady = false,
                demoReadinessCheckFailed = false,
            )
            return
        }
        mutableUiState.value = state.copy(
            isCheckingDemoReadiness = true,
            demoReadinessCheckFailed = false,
        )
        viewModelScope.launch {
            runCatching { localVoiceTemplateCoordinator.hasCompleteSafetyCommands() }
                .onSuccess { ready ->
                    mutableUiState.value = mutableUiState.value.copy(
                        isCheckingDemoReadiness = false,
                        safetyCommandsReady = ready,
                        demoReadinessCheckFailed = false,
                    )
                }
                .onFailure {
                    mutableUiState.value = mutableUiState.value.copy(
                        isCheckingDemoReadiness = false,
                        safetyCommandsReady = false,
                        demoReadinessCheckFailed = true,
                    )
                }
        }
    }

    private fun loadContacts(clearCurrentContacts: Boolean) {
        val state = mutableUiState.value
        if (loadInProgress || state.isPreparingDemoContact
            || state.unbindingContactId != null || state.localVerification != null
        ) return
        loadInProgress = true
        val initialLoading = clearCurrentContacts || !hasLoaded
        mutableUiState.value = state.copy(
            contacts = if (clearCurrentContacts) emptyList() else state.contacts,
            isInitialLoading = initialLoading,
            isRefreshing = !initialLoading,
            errorMessage = null,
            informationMessage = null,
            pendingUnbind = null,
        )
        viewModelScope.launch {
            runCatching {
                contactRepository.list(page = 0, size = CONTACT_LIMIT)
            }.onSuccess { page ->
                hasLoaded = true
                loadInProgress = false
                mutableUiState.value = mutableUiState.value.copy(
                    contacts = page.items.filterNot { it.status == ContactStatus.REVOKED },
                    isInitialLoading = false,
                    isRefreshing = false,
                )
                refreshDemoReadiness()
            }.onFailure { throwable ->
                loadInProgress = false
                mutableUiState.value = mutableUiState.value.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    errorMessage = throwable.toChineseUserMessage("联系人加载失败，请稍后重试"),
                )
            }
        }
    }

    private companion object {
        const val CONTACT_LIMIT = 20
        const val LOCAL_VERIFICATION_TIMEOUT_REFRESH_MILLIS = 61_000L
        val LOCAL_VERIFICATION_STATUSES = setOf(
            ContactStatus.PENDING_LOCAL_VERIFY,
            ContactStatus.REVERIFY_REQUIRED,
        )
    }
}

/** 只供不涉及本机验证的纯 ViewModel 单元测试使用。 */
private object DisabledWechatLocalContactVerificationCoordinator :
    WechatLocalContactVerificationCoordinator {
    private val disabledState = MutableStateFlow(WechatLocalVerificationCaptureState())
    override val state: StateFlow<WechatLocalVerificationCaptureState> = disabledState.asStateFlow()

    override fun start() = Unit

    override fun beginObservation(): Boolean = false

    override fun refresh() = Unit

    override fun failToOpenWechat() = Unit

    override fun buildEvidence(expectedContactVersion: Long) = null

    override fun cancel() = Unit
}
