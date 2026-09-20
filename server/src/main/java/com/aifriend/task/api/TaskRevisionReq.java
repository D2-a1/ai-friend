package com.aifriend.task.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.aifriend.task.domain.TaskRevisionMode;

/**
 * 同一会话内重说或纠错请求。
 *
 * @param audioObjectId 当前修订录音公开编号
 * @param expectedVersion 客户端已见会话版本
 * @param mode 完整重说或定向纠错模式
 * @param basicRecognition 客户端基础识别证据，可空
 */
public record TaskRevisionReq(
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String audioObjectId,
        @Min(1) long expectedVersion,
        @NotNull TaskRevisionMode mode,
        @Valid TaskClientRecognitionEvidenceReq basicRecognition) {
}