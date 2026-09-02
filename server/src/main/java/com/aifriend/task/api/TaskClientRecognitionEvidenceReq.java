package com.aifriend.task.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Android 本机基础识别证据请求。
 *
 * @param transcript 临时转写
 * @param confidence 保守置信度
 * @param modelVersion 固定参考模型版本
 * @param modelArchiveSha256 模型归档摘要
 * @param words 词级时间戳
 * @author Codex
 * @since 1.0.0
 */
public record TaskClientRecognitionEvidenceReq(
        @NotBlank @Size(max = 1000) String transcript,
        @DecimalMin("0.0") @DecimalMax("1.0") double confidence,
        @NotBlank @Size(max = 60) String modelVersion,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String modelArchiveSha256,
        @NotNull @Size(min = 1, max = 200) List<@Valid TaskRecognizedWordReq> words) {
}
