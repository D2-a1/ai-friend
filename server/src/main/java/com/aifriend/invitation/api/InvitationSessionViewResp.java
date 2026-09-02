package com.aifriend.invitation.api;

import java.time.Instant;

/**
 * 当前受限邀请会话最小展示响应。
 *
 * @param inviterDisplayName 邀请人展示名称；没有可靠来源时为空
 * @param relationshipSummary 固定绑定用途说明
 * @param consentPolicyVersion 当前亲友同意政策版本
 * @param expiresAt 会话过期时间
 * @param readyForConsent 是否允许显示明确同意操作
 * @param csrfToken 本次查询新签发的 CSRF token，只允许页面内存持有
 * @author Codex
 * @since 1.0.0
 */
public record InvitationSessionViewResp(
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
        return "InvitationSessionViewResp[redacted]";
    }
}
