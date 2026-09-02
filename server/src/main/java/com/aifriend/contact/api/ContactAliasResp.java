package com.aifriend.contact.api;

import java.time.Instant;

/**
 * 联系人方言称呼响应，不包含音素提示或声学模板。
 *
 * @param id al_ 前缀称呼编号
 * @param displayText 辅助展示文字
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 模型版本
 * @param thresholdVersion 阈值版本
 * @param compatibility 模板兼容状态
 * @param createdAt 创建时间
 * @author Codex
 * @since 1.0.0
 */
public record ContactAliasResp(
        String id,
        String displayText,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion,
        String compatibility,
        Instant createdAt) {
}
