package com.aifriend.consent.api;

import java.time.Instant;

import com.aifriend.consent.domain.ConsentDecision;
import com.aifriend.consent.domain.ConsentType;

/**
 * 当前授权决定响应。
 *
 * @param type 授权类型
 * @param decision 最新决定
 * @param policyVersion 政策版本
 * @param decidedAt 服务端决定时间
 * @author Codex
 * @since 1.0.0
 */
public record ConsentResp(
        ConsentType type,
        ConsentDecision decision,
        String policyVersion,
        Instant decidedAt) {
}
