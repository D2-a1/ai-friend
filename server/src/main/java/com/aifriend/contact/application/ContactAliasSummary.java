package com.aifriend.contact.application;

import java.time.Instant;

/**
 * 不含声学模板、音素提示或内部 UUID 的联系人称呼展示结果。
 *
 * @param id al_ 前缀公开编号
 * @param displayText 辅助展示文字
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @param compatibility 模板兼容状态
 * @param createdAt 创建时间
 * @author Codex
 * @since 1.0.0
 */
public record ContactAliasSummary(
        String id,
        String displayText,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion,
        String compatibility,
        Instant createdAt) {
}
