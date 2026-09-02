package com.aifriend.invitation.application;

/**
 * 邀请 OAuth 回调尝试次数限制端口。
 *
 * <p>实现只能使用邀请会话 token 的摘要作为键，不得保存或记录 Cookie、state、
 * code、微信主体或客户端地址原文。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface InvitationOAuthAttemptLimiterPort {

    /**
     * 消耗当前邀请会话的一次 OAuth 回调额度。
     *
     * @param sessionTokenDigest 邀请会话 token 的 SHA-256 摘要
     * @throws com.aifriend.shared.error.BusinessException 超限或限流存储不可用时抛出
     */
    void acquire(byte[] sessionTokenDigest);
}
