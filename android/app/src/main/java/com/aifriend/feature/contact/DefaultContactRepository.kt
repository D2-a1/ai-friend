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
import com.aifriend.core.network.ApiErrorCodeReader
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
    private val errorCodeReader: ApiErrorCodeReader,
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
        if (!response.isSuccessful) {
            val code = errorCodeReader.read(response)
            throw AuthApiException(response.code(), aliasCreationFailureMessage(response.code(), code), code)
        }
        return response.body()?.data
            ?: throw AuthApiException(response.code(), "服务端未返回称呼保存结果，请刷新核对，暂勿重复录制")
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

    private fun aliasDeletionFailureMessage(status: Int): String = when (status) {
        404 -> "没有找到该联系人或称呼"
        409 -> "联系人状态已经变化，请刷新后重试"
        else -> "删除称呼失败，请稍后重试"
    }
}

/** 只使用稳定错误码选择本机话术，不能把任意 422 都认定为双录不一致。 */
internal fun aliasCreationFailureMessage(status: Int, code: String?): String = when (code) {
    "ENROLLMENT_INCONSISTENT" -> "服务端判定两遍发音一致性不足，称呼没有保存"
    "ALIAS_PHONETIC_BORDERLINE" -> "这个发音与已有称呼区分度不足，称呼没有保存"
    "ALIAS_PHONETIC_CONFLICT" -> "这个发音与已有称呼太相似，称呼没有保存"
    "TEMPLATE_INCOMPATIBLE" -> "服务端声学模板暂不可用，请停止重复录制并检查服务端"
    "CONSENT_REQUIRED" -> "请先允许保存个人语音模板"
    "LOCAL_VERIFICATION_REQUIRED" -> "请先完成联系人本机验证"
    "ALIAS_LIMIT_REACHED" -> "称呼数量已满，请先管理已有称呼"
    "SESSION_CONFLICT" -> "联系人或录制状态已经变化，请刷新后核对"
    "AUDIO_INVALID" -> "上传录音无效或已失效，称呼没有保存"
    "RATE_LIMITED" -> "操作太频繁，请稍后再试"
    "NOT_FOUND" -> "没有找到该联系人"
    else -> when (status) {
        401 -> "登录已失效，请重新登录"
        403 -> "当前没有保存称呼的权限，请检查授权"
        else -> "称呼保存未通过服务端校验，原因尚未确认，请暂勿反复录制"
    }
}
