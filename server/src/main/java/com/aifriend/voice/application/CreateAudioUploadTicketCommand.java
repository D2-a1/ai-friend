package com.aifriend.voice.application;

import com.aifriend.voice.domain.AudioPurpose;

/**
 * 创建受限音频上传凭证命令。
 *
 * @param purpose 音频业务用途
 * @param mediaType 允许的媒体类型
 * @param sizeBytes 预期字节数
 * @param durationMs 客户端预检时长毫秒数
 * @param sha256Hex 预期音频 SHA-256 十六进制文本
 * @author Codex
 * @since 1.0.0
 */
public record CreateAudioUploadTicketCommand(
        AudioPurpose purpose,
        String mediaType,
        long sizeBytes,
        int durationMs,
        String sha256Hex) {
}
