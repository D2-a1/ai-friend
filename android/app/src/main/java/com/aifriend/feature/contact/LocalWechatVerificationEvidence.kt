package com.aifriend.feature.contact

import java.time.OffsetDateTime

/**
 * 未来受限微信页面验证器产出的最小证据。
 *
 * 稳定定位和备注只允许进入当前 HTTPS 请求，不得写入日志、Room、DataStore 或崩溃信息。
 *
 * @author codex
 * @since 2026-08-09
 */
data class LocalWechatVerificationEvidence(
    val stableLocator: String,
    val currentRemark: String?,
    val pageType: LocalWechatPageType,
    val friendConfirmed: Boolean,
    val locatorObservationCount: Int,
    val locatorUnique: Boolean,
    val wechatVersion: String,
    val ruleVersion: String,
    val verifiedAt: OffsetDateTime,
    val expectedContactVersion: Long,
) {
    /**
     * 避免崩溃信息或调试输出意外带出稳定定位和备注。
     */
    override fun toString(): String =
        "LocalWechatVerificationEvidence(" +
            "stableLocator=<redacted>, currentRemark=<redacted>, " +
            "pageType=$pageType, friendConfirmed=$friendConfirmed, " +
            "locatorObservationCount=$locatorObservationCount, locatorUnique=$locatorUnique, " +
            "wechatVersion=$wechatVersion, ruleVersion=$ruleVersion, " +
            "verifiedAt=$verifiedAt, expectedContactVersion=$expectedContactVersion)"
}

/**
 * 本机验证器识别到的微信页面类型。
 *
 * @author codex
 * @since 2026-08-09
 */
enum class LocalWechatPageType {
    CONTACT_PROFILE,
    DIRECT_CHAT,
    UNSUPPORTED,
}
