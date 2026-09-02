package com.aifriend.consent.api;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.aifriend.consent.domain.ConsentDecision;

/**
 * 追加授权决定请求。
 *
 * @param decision GRANTED 或 REVOKED
 * @param policyVersion 用户看到并确认的政策版本
 * @param confirmedAt 客户端明确确认时间
 * @author Codex
 * @since 1.0.0
 */
public record UpdateConsentReq(
        @NotNull ConsentDecision decision,
        @NotBlank @Size(max = 40) String policyVersion,
        @NotNull Instant confirmedAt) {
}
