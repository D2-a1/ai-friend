package com.aifriend.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 微信渠道一个受控部分结果。
 *
 * @param part AUDIO、TEXT 或 CALL
 * @param result 受控部分结果
 * @param evidenceCode 不含正文和截图的证据码，可空
 * @author Codex
 * @since 1.0.0
 */
public record TaskChannelPartReq(
        @NotBlank @Size(max = 20) String part,
        @NotBlank @Size(max = 30) String result,
        @Size(max = 80) String evidenceCode) {
}
