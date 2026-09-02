package com.aifriend.invitation.application;

import java.time.Instant;

/**
 * 微信身份验证后供同源邀请页展示的最小会话信息。
 *
 * @param inviterDisplayName 合法邀请人展示名称；当前无事实来源时为空
 * @param relationshipSummary 固定绑定用途说明
 * @param consentPolicyVersion 当前亲友同意政策版本
 * @param expiresAt 受限会话过期时间
 * @param readyForConsent 是否允许展示明确同意操作
 * @param csrfToken 本次查询新签发的 CSRF token，只允许页面内存持有
 * @author Codex
 * @since 1.0.0
 */
public record InvitationSessionView(
        String inviterDisplayName,
        String relationshipSummary,
        String consentPolicyVersion,
        Instant expiresAt,
        boolean readyForConsent,
        String csrfToken) {

    /**
     * 返回不含 CSRF 明文的诊断文本。
     *
     * @return 固定脱敏文本
     */
    @Override
    public String toString() {
        return "InvitationSessionView[redacted]";
    }
}
