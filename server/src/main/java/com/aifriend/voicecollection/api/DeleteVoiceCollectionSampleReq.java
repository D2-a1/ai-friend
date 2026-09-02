package com.aifriend.voicecollection.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 删除采集样本请求。
 *
 * @param confirmed 用户明确二次确认
 * @param expectedVersion 客户端最后读取版本
 * @author Codex
 * @since 1.0.0
 */
public record DeleteVoiceCollectionSampleReq(
        @AssertTrue boolean confirmed,
        @PositiveOrZero long expectedVersion) {
}
