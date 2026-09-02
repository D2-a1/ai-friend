package com.aifriend.app.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aifriend.BuildConfig
import com.aifriend.contract.model.ConsentDecision
import com.aifriend.contract.model.ConsentType
import com.aifriend.core.network.toChineseUserMessage
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AccountClosureAcceptedException
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.DeviceIdentityPort
import com.aifriend.feature.auth.WechatLoginDevice
import com.aifriend.feature.auth.WechatLoginEvent
import com.aifriend.feature.auth.WechatLoginLaunchResult
import com.aifriend.feature.auth.WechatLoginPort
import com.aifriend.feature.consent.ConsentRepository
import com.aifriend.feature.invitation.InvitationRepository
import com.aifriend.core.voice.LocalVoiceTemplateCoordinator
import com.aifriend.feature.privacy.AccountWipeCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 登录恢复、基础身份授权和根页面状态编排。
 *
 * @author codex
 * @since 2026-08-04
 */
@HiltViewModel
class AiFriendViewModel @Inject constructor(
    private val authSessionRepository: AuthSessionRepository,
    private val consentRepository: ConsentRepository,
    private val invitationRepository: InvitationRepository,
    private val localVoiceTemplateCoordinator: LocalVoiceTemplateCoordinator,
    private val accountWipeCoordinator: AccountWipeCoordinator,
    private val wechatLoginPort: WechatLoginPort,
    deviceIdentity: DeviceIdentityPort,
) : ViewModel() {

    private val mutableUiState = MutableStateFlow<AiFriendUiState>(AiFriendUiState.Loading)
    val uiState: StateFlow<AiFriendUiState> = mutableUiState.asStateFlow()
    private val mutableInvitationState = MutableStateFlow<InvitationUiState>(InvitationUiState.Idle)
    val invitationState: StateFlow<InvitationUiState> = mutableInvitationState.asStateFlow()
    val deviceFingerprint: String = runCatching(deviceIdentity::fingerprint)
        .getOrElse { "本机设备编号暂时不可用" }

    init {
        observeWechatLogin()
        resumeWipeOrRestoreSession()
    }

    fun startWechatLogin() {
        when (val result = wechatLoginPort.launch()) {
            WechatLoginLaunchResult.Started -> mutableUiState.value = AiFriendUiState.Loading
            is WechatLoginLaunchResult.Unavailable -> {
                mutableUiState.value = AiFriendUiState.Error(result.message)
            }
        }
    }

    fun restoreSession() {
        resumeWipeOrRestoreSession()
    }

    private fun resumeWipeOrRestoreSession() {
        viewModelScope.launch {
            if (accountWipeCoordinator.isRequired()) {
                performRequiredWipe(markRequired = false)
                return@launch
            }
            mutableUiState.value = AiFriendUiState.Loading
            runCatching {
                val session = authSessionRepository.restore()
                if (session == null) {
                    mutableUiState.value = AiFriendUiState.SignedOut
                } else {
                    loadConsentState(session)
                }
            }.onFailure {
                if (it is AccountClosureAcceptedException) {
                    performRequiredWipe(markRequired = true)
                } else {
                    mutableUiState.value = AiFriendUiState.Error("本地登录状态读取失败")
                }
            }
        }
    }

    fun loginWithWechatCode(code: String) {
        viewModelScope.launch {
            mutableUiState.value = AiFriendUiState.Loading
            runCatching {
                authSessionRepository.loginWithWechatCode(
                    code = code,
                    device = WechatLoginDevice(
                        osVersion = Build.VERSION.RELEASE,
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceModel = Build.MODEL,
                    ),
                ).also { loadConsentState(it) }
            }.onFailure {
                if (it is AccountClosureAcceptedException) {
                    performRequiredWipe(markRequired = true)
                } else {
                    mutableUiState.value = AiFriendUiState.Error(
                        it.toChineseUserMessage("微信登录失败"),
                    )
                }
            }
        }
    }

    fun loginForLocalDevelopment() {
        if (!BuildConfig.DEBUG) {
            mutableUiState.value = AiFriendUiState.Error("正式版本不允许本地测试登录")
            return
        }
        loginWithWechatCode("local_owner.${UUID.randomUUID()}")
    }

    fun grantBasicIdentityConsent() {
        val current = mutableUiState.value as? AiFriendUiState.ConsentRequired ?: return
        viewModelScope.launch {
            mutableUiState.value = AiFriendUiState.Loading
            val updateResult = runCatching {
                consentRepository.update(
                    type = ConsentType.BASIC_IDENTITY,
                    decision = ConsentDecision.GRANTED,
                    policyVersion = BASIC_IDENTITY_POLICY_VERSION,
                )
            }
            if (updateResult.isSuccess) {
                mutableUiState.value = AiFriendUiState.Ready(current.userId)
                restorePendingInvitations()
            } else {
                mutableUiState.value = AiFriendUiState.Error(
                    updateResult.exceptionOrNull()
                        ?.toChineseUserMessage("授权保存失败")
                        ?: "授权保存失败",
                )
            }
        }
    }

    fun clearLocalSession() {
        wechatLoginPort.clear()
        viewModelScope.launch {
            mutableInvitationState.value = InvitationUiState.Idle
            authSessionRepository.clearLocalSession()
            mutableUiState.value = AiFriendUiState.SignedOut
        }
    }

    private fun observeWechatLogin() {
        viewModelScope.launch {
            wechatLoginPort.events.collect { event ->
                when (event) {
                    is WechatLoginEvent.Authorized -> loginWithWechatCode(event.code)
                    is WechatLoginEvent.Failed -> {
                        mutableUiState.value = AiFriendUiState.Error(event.message)
                    }
                }
            }
        }
    }

    /** 服务端 202 或可验证的 ACCOUNT_CLOSURE_ACCEPTED 后立即关闭本机能力并持久清除。 */
    fun onAccountClosureAccepted() {
        mutableUiState.value = AiFriendUiState.AccountWiping
        mutableInvitationState.value = InvitationUiState.Idle
        if (runCatching { accountWipeCoordinator.markRequired() }.isFailure) {
            mutableUiState.value = AiFriendUiState.AccountWipeFailed(
                "本机数据尚未完全清除，登录已锁定。请重试清除。",
            )
            return
        }
        viewModelScope.launch {
            performRequiredWipe(markRequired = false)
        }
    }

    fun retryAccountWipe() {
        if (mutableUiState.value !is AiFriendUiState.AccountWipeFailed) return
        viewModelScope.launch {
            performRequiredWipe(markRequired = !accountWipeCoordinator.isRequired())
        }
    }

    fun createInvitation() {
        if (mutableUiState.value !is AiFriendUiState.Ready) return
        val current = mutableInvitationState.value
        val canCreate = current is InvitationUiState.Idle ||
            current is InvitationUiState.Revoked ||
            current is InvitationUiState.Error &&
            current.invitation == null &&
            current.recoveredInvitations.isEmpty()
        if (!canCreate) return
        viewModelScope.launch {
            mutableInvitationState.value = InvitationUiState.Creating
            runCatching { invitationRepository.create() }
                .onSuccess { mutableInvitationState.value = InvitationUiState.Active(it) }
                .onFailure {
                    mutableInvitationState.value = InvitationUiState.Error(
                        it.toChineseUserMessage("邀请创建失败，请稍后重试"),
                    )
                }
        }
    }

    fun revokeInvitation(invitationId: String) {
        val current = mutableInvitationState.value
        val activeInvitation = when (current) {
            is InvitationUiState.Active -> current.invitation.takeIf {
                it.invitationId == invitationId
            }
            is InvitationUiState.Error -> current.invitation?.takeIf {
                it.invitationId == invitationId
            }
            else -> null
        }
        val recoveredInvitations = when (current) {
            is InvitationUiState.Recovered -> current.invitations
            is InvitationUiState.Error -> current.recoveredInvitations
            else -> emptyList()
        }
        if (activeInvitation == null &&
            recoveredInvitations.none { it.invitationId == invitationId }
        ) {
            return
        }
        viewModelScope.launch {
            mutableInvitationState.value = InvitationUiState.Revoking(
                invitationId = invitationId,
                activeInvitation = activeInvitation,
                recoveredInvitations = recoveredInvitations,
            )
            runCatching { invitationRepository.revoke(invitationId) }
                .onSuccess {
                    val remaining = recoveredInvitations.filterNot {
                        it.invitationId == invitationId
                    }
                    mutableInvitationState.value = if (remaining.isEmpty()) {
                        InvitationUiState.Revoked
                    } else {
                        InvitationUiState.Recovered(remaining)
                    }
                }
                .onFailure {
                    mutableInvitationState.value = InvitationUiState.Error(
                        message = it.toChineseUserMessage("邀请撤销失败，请稍后重试"),
                        invitation = activeInvitation,
                        recoveredInvitations = recoveredInvitations,
                    )
                }
        }
    }

    fun refreshInvitations() {
        if (mutableUiState.value !is AiFriendUiState.Ready) return
        viewModelScope.launch { restorePendingInvitations() }
    }

    /** 微信没有接收分享请求时保留当前邀请，并显示可重试的中文提示。 */
    fun reportWechatInvitationShareFailure() {
        mutableInvitationState.value = mutableInvitationState.value.withWechatShareFailure()
    }

    private suspend fun loadConsentState(session: AuthSession) {
        val basicIdentityGranted = consentRepository.listCurrent().any {
            it.type == ConsentType.BASIC_IDENTITY && it.decision == ConsentDecision.GRANTED
        }
        if (basicIdentityGranted) {
            // 对账失败不影响账号功能；本机任务匹配仍会因模板缺失而失败关闭。
            runCatching { localVoiceTemplateCoordinator.reconcile() }
            mutableUiState.value = AiFriendUiState.Ready(session.userId)
            restorePendingInvitations()
        } else {
            mutableUiState.value = AiFriendUiState.ConsentRequired(session.userId)
        }
    }

    private suspend fun restorePendingInvitations() {
        if (mutableUiState.value !is AiFriendUiState.Ready) return
        val current = mutableInvitationState.value
        if (current is InvitationUiState.Active ||
            current is InvitationUiState.Creating ||
            current is InvitationUiState.Revoking
        ) {
            return
        }
        mutableInvitationState.value = InvitationUiState.Restoring
        runCatching { invitationRepository.listPending() }
            .onSuccess { invitations ->
                mutableInvitationState.value = if (invitations.isEmpty()) {
                    InvitationUiState.Idle
                } else {
                    InvitationUiState.Recovered(invitations)
                }
            }
            .onFailure {
                mutableInvitationState.value = InvitationUiState.RestoreError(
                    it.toChineseUserMessage("待处理邀请暂时无法读取"),
                )
            }
    }

    private suspend fun performRequiredWipe(markRequired: Boolean) {
        mutableUiState.value = AiFriendUiState.AccountWiping
        mutableInvitationState.value = InvitationUiState.Idle
        runCatching {
            if (markRequired) {
                accountWipeCoordinator.beginAcceptedWipe()
            } else {
                accountWipeCoordinator.resumeRequiredWipe()
            }
        }.onSuccess {
            mutableUiState.value = AiFriendUiState.SignedOut
        }.onFailure {
            mutableUiState.value = AiFriendUiState.AccountWipeFailed(
                "本机数据尚未完全清除，登录已锁定。请重试清除。",
            )
        }
    }

    private companion object {
        const val BASIC_IDENTITY_POLICY_VERSION = "privacy-v1"
    }

    override fun onCleared() {
        wechatLoginPort.clear()
        super.onCleared()
    }
}
