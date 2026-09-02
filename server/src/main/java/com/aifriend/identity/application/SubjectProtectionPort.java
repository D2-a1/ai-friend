package com.aifriend.identity.application;

import com.aifriend.identity.domain.ProtectedWechatSubject;

/**
 * 微信主体加密和 HMAC 查询键生成端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface SubjectProtectionPort {

    /**
     * 保护微信主体。
     *
     * @param subject 微信稳定主体，禁止记录日志
     * @return 加密主体与查询键
     */
    ProtectedWechatSubject protect(String subject);
}
