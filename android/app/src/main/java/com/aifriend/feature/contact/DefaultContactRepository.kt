package com.aifriend.feature.contact

import com.aifriend.contract.api.ContactsApi
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.ContactPage
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.ContactUnbindRequest
import com.aifriend.contract.model.CreateAliasRequest
import com.aifriend.contract.model.DeleteAliasRequest
import com.aifriend.contract.model.LocalVerificationRequest
import com.aifriend.contract.model.WechatPageType
import com.aifriend.feature.auth.AuthApiException
import com.aifriend.feature.auth.AuthSessionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 使用 OpenAPI ContactsApi 读取联系人；401 时只刷新并重试一次。
 *
 * @author codex
 * @since 2026-08-07
 */
@Singleton
class DefaultContactRepository @Inject constructor(
    private val contactsApi: ContactsApi,
    private val authSessionRepository: AuthSessionRepository,
) : ContactRepository {

    override suspend fun ensureDebugDemoContact(): Contact {
        var response = contactsApi.ensureDebugDemoContact()
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.ensureDebugDemoContact()
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), demoContactFailureMessage(response.code()))
    }

    override suspend fun list(
        page: Int,
        size: Int,
        status: ContactStatus?,
    ): ContactPage {
        var response = contactsApi.listContacts(page, size, status)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.listContacts(page, size, status)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), "联系人同步失败，请稍后重试")
    }

    override suspend fun verifyLocalWechatContact(
        contactId: String,
        evidence: LocalWechatVerificationEvidence,
    ): Contact {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = LocalVerificationRequest(
            stableLocator = evidence.stableLocator,
            pageType = evidence.pageType.toContractType(),
            friendConfirmed = evidence.friendConfirmed,
            locatorObservationCount = evidence.locatorObservationCount,
            locatorUnique = evidence.locatorUnique,
            wechatVersion = evidence.wechatVersion,
            ruleVersion = evidence.ruleVersion,
            verifiedAt = evidence.verifiedAt,
            expectedContactVersion = evidence.expectedContactVersion,
            currentRemark = evidence.currentRemark,
        )
        var response = contactsApi.verifyLocalWechatContact(contactId, idempotencyKey, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.verifyLocalWechatContact(contactId, idempotencyKey, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), verificationFailureMessage(response.code()))
    }

    override suspend fun unbindContact(
        contactId: String,
        expectedContactVersion: Long,
    ): Contact {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = ContactUnbindRequest(
            confirmed = true,
            expectedContactVersion = expectedContactVersion,
        )
        var response = contactsApi.unbindContact(contactId, idempotencyKey, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.unbindContact(contactId, idempotencyKey, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), unbindFailureMessage(response.code()))
    }

    override suspend fun createAlias(
        contactId: String,
        displayText: String,
        phoneticHint: String?,
        firstAudioObjectId: String,
        secondAudioObjectId: String,
        expectedContactVersion: Long,
    ): ContactAlias {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = CreateAliasRequest(
            displayText = displayText,
            firstAudioObjectId = firstAudioObjectId,
            secondAudioObjectId = secondAudioObjectId,
            expectedContactVersion = expectedContactVersion,
            confirmed = true,
            phoneticHint = phoneticHint,
        )
        var response = contactsApi.createContactAlias(contactId, idempotencyKey, request)
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.createContactAlias(contactId, idempotencyKey, request)
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), aliasCreationFailureMessage(response.code()))
    }

    override suspend fun deleteAlias(
        contactId: String,
        aliasId: String,
        expectedContactVersion: Long,
    ): Contact {
        val idempotencyKey = UUID.randomUUID().toString()
        val request = DeleteAliasRequest(
            confirmed = true,
            expectedContactVersion = expectedContactVersion,
        )
        var response = contactsApi.deleteContactAlias(
            contactId,
            aliasId,
            idempotencyKey,
            request,
        )
        if (response.code() == 401) {
            authSessionRepository.refresh()
            response = contactsApi.deleteContactAlias(
                contactId,
                aliasId,
                idempotencyKey,
                request,
            )
        }
        return response.body()?.data?.takeIf { response.isSuccessful }
            ?: throw AuthApiException(response.code(), aliasDeletionFailureMessage(response.code()))
    }

    private fun LocalWechatPageType.toContractType(): WechatPageType = when (this) {
        LocalWechatPageType.CONTACT_PROFILE -> WechatPageType.CONTACT_PROFILE
        LocalWechatPageType.DIRECT_CHAT -> WechatPageType.DIRECT_CHAT
        LocalWechatPageType.UNSUPPORTED -> WechatPageType.UNSUPPORTED
    }

    private fun verificationFailureMessage(status: Int): String = when (status) {
        404 -> "没有找到该联系人"
        409 -> "联系人状态已经变化，请刷新后重试"
        422 -> "当前微信版本或联系人资料页暂不支持验证，请记录微信版本后稍后重试"
        else -> "本机验证失败，请稍后重试"
    }

    private fun demoContactFailureMessage(status: Int): String = when (status) {
        404 -> "当前后端没有开启体验联系人"
        409 -> "联系人数量已满或状态已经变化，请刷新后重试"
        else -> "体验联系人准备失败，请稍后重试"
    }

    private fun unbindFailureMessage(status: Int): String = when (status) {
        404 -> "没有找到该联系人"
        409 -> "联系人状态已经变化，请刷新后重试"
        else -> "解除绑定失败，请稍后重试"
    }

    private fun aliasCreationFailureMessage(status: Int): String = when (status) {
        400 -> "录音无效，请重新录制"
        403 -> "请先允许保存个人语音模板"
        404 -> "没有找到该联系人"
        409 -> "称呼冲突、数量已满或模板暂不可用，请刷新后重试"
        422 -> "两遍发音不一致或不容易区分，请重新录制"
        else -> "保存称呼失败，请稍后重试"
    }

    private fun aliasDeletionFailureMessage(status: Int): String = when (status) {
        404 -> "没有找到该联系人或称呼"
        409 -> "联系人状态已经变化，请刷新后重试"
        else -> "删除称呼失败，请稍后重试"
    }
}
