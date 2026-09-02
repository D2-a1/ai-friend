package com.aifriend.task.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Android 本机基础识别词级时间戳请求。
 *
 * @param text 临时词文本
 * @param startMs 起始毫秒
 * @param endMs 结束毫秒
 * @param confidence 词置信度
 * @author Codex
 * @since 1.0.0
 */
public record TaskRecognizedWordReq(
        @NotBlank @Size(max = 40) String text,
        @Min(0) int startMs,
        @Min(1) int endMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence) {
}
