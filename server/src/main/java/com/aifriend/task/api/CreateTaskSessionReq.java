package com.aifriend.task.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建方言任务请求。
 *
 * @param clientTaskId 客户端当前任务随机编号
 * @param audioObjectId TASK 音频对象编号
 * @param previousConfirmedContactId 5 秒连续会话联系人，可空
 * @param clientContext 客户端版本上下文
 * @param basicRecognition 可空的 Android 本机基础识别证据
 * @author Codex
 * @since 1.0.0
 */
public record CreateTaskSessionReq(
        @NotBlank @Size(max = 64) String clientTaskId,
        @NotBlank @Pattern(regexp = "^au_[A-Za-z0-9]+$") String audioObjectId,
        @Pattern(regexp = "^ct_[A-Za-z0-9]+$") String previousConfirmedContactId,
        @NotNull @Valid TaskClientContextReq clientContext,
        @Valid TaskClientRecognitionEvidenceReq basicRecognition) {
}
