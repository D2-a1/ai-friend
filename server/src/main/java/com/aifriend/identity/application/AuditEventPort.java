package com.aifriend.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 去标识化安全审计写入端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface AuditEventPort {

    /**
     * 写入不含 token、code、微信主体和请求正文的审计事件。
     *
     * @param actorUserId 用户 UUID
     * @param action 动作码
     * @param result 结果码
     * @param reasonCode 原因码，可空
     * @param occurredAt 发生时间
     */
    void append(UUID actorUserId, String action, String result, String reasonCode, Instant occurredAt);
}
