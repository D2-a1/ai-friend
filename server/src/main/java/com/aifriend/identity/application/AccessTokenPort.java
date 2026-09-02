package com.aifriend.identity.application;

import java.time.Instant;

import com.aifriend.identity.domain.UserAccount;

/**
 * 短期访问令牌签发端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AccessTokenPort {

    /**
     * 为 ACTIVE 用户签发短期 JWT。
     *
     * @param user 用户账号
     * @param issuedAt 签发时间
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @return 访问令牌
     */
    AccessToken issue(UserAccount user, Instant issuedAt, byte[] devicePublicKeySha256);
}
