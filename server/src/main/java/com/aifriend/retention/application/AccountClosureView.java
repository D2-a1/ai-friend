package com.aifriend.retention.application;

import java.time.Instant;

/**
 * 账号注销可靠受理后的最小公开视图。
 *
 * @param acceptedAt 服务端可靠受理时间
 * @param reRegistrationNotBefore 最早允许重新注册时间，不代表届时已删除完成
 * @author Codex
 * @since 1.0.0
 */
public record AccountClosureView(Instant acceptedAt, Instant reRegistrationNotBefore) {
}
