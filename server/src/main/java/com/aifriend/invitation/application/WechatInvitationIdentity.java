package com.aifriend.invitation.application;

/**
 * 微信邀请 OAuth 验证后仅在当前事务内短暂存在的最小身份。
 *
 * @param subject 微信应用作用域稳定主体，不得记录日志
 * @author Codex
 * @since 1.0.0
 */
public record WechatInvitationIdentity(String subject) {
}
