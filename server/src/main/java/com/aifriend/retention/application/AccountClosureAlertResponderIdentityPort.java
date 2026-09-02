package com.aifriend.retention.application;

import java.util.UUID;

/**
 * 注销 P0 告警接手人的独立运维身份验证端口。
 *
 * <p>实现必须使用与普通用户 token 隔离的安全链，并返回不可逆最小身份事实。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccountClosureAlertResponderIdentityPort {

    /**
     * 验证一次性运维凭据及其对目标投递的接手权限。
     *
     * @param deliveryId 目标 P0 告警投递 UUID
     * @param credentialProof 一次性运维身份凭据，只能在当前调用内存使用
     * @return 已验证责任组、主体域隔离 HMAC 和认证上下文摘要
     * @throws RuntimeException 凭据无效、过期、越权或身份服务不可用时抛出
     */
    AccountClosureAlertResponderIdentity verify(
            UUID deliveryId,
            byte[] credentialProof);
}
