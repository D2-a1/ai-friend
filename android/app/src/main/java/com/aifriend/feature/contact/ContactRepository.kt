package com.aifriend.feature.contact

import com.aifriend.contract.model.ContactPage
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias

/**
 * 当前用户联系人读取、本机验证和解除绑定仓库。
 *
 * @author codex
 * @since 2026-08-07
 */
interface ContactRepository {

    /**
     * 仅供 Debug MVP 准备不含真实微信资料的体验联系人。
     */
    suspend fun ensureDebugDemoContact(): Contact

    /**
     * 分页读取当前登录用户的最小联系人展示数据。
     */
    suspend fun list(
        page: Int = 0,
        size: Int = 20,
        status: ContactStatus? = null,
    ): ContactPage

    /**
     * 提交未来本地验证器产出的最小微信页面证据。
     */
    suspend fun verifyLocalWechatContact(
        contactId: String,
        evidence: LocalWechatVerificationEvidence,
    ): Contact

    /**
     * 使用两遍已上传录音创建方言称呼；展示文字不参与声学唯一性。
     */
    suspend fun createAlias(
        contactId: String,
        displayText: String,
        phoneticHint: String?,
        firstAudioObjectId: String,
        secondAudioObjectId: String,
        expectedContactVersion: Long,
    ): ContactAlias

    /**
     * 在用户完成二次确认后删除称呼，并使用联系人聚合版本防止覆盖并发修改。
     */
    suspend fun deleteAlias(
        contactId: String,
        aliasId: String,
        expectedContactVersion: Long,
    ): Contact

    /**
     * 在用户完成二次确认后解除联系人绑定。
     */
    suspend fun unbindContact(
        contactId: String,
        expectedContactVersion: Long,
    ): Contact
}
