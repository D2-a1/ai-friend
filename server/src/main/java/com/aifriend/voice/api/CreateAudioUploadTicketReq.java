package com.aifriend.voice.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.aifriend.voice.domain.AudioPurpose;

/**
 * 创建受限音频上传凭证请求。
 *
 * @param purpose 音频受限用途
 * @param mediaType 允许的音频媒体类型
 * @param sizeBytes 预期音频字节数
 * @param durationMs 客户端预检时长毫秒数
 * @param sha256 预期音频 SHA-256 十六进制文本
 * @author Codex
 * @since 1.0.0
 */
public record CreateAudioUploadTicketReq(
        @NotNull AudioPurpose purpose,
        @NotNull
        @Pattern(regexp = "audio/(mp4|aac|wav|ogg)")
        String mediaType,
        @Min(1) @Max(20_971_520) long sizeBytes,
        @Min(200) @Max(60_000) int durationMs,
        @NotNull @Pattern(regexp = "[A-Fa-f0-9]{64}") String sha256) {
}
