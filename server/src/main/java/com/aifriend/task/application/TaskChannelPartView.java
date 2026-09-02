package com.aifriend.task.application;

/**
 * 微信渠道一个受控部分的可验证结果。
 *
 * @param part AUDIO、TEXT 或 CALL
 * @param result 受控结果枚举值
 * @param evidenceCode 不含正文和页面内容的证据码，可空
 * @author Codex
 * @since 1.0.0
 */
public record TaskChannelPartView(String part, String result, String evidenceCode) {
}
