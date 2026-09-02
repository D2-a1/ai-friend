package com.aifriend.voice.application;

import java.time.Instant;

/**
 * 已创建的受限音频上传凭证。
 *
 * @param audioObjectId au_ 前缀音频对象编号
 * @param uploadTarget 动态上传目标
 * @param expiresAt 固定上传凭证过期时间
 * @param objectKey 服务端生成的私有对象键
 * @author Codex
 * @since 1.0.0
 */
public record CreatedAudioUploadTicket(
        String audioObjectId,
        AudioUploadTarget uploadTarget,
        Instant expiresAt,
        String objectKey) {

    /**
     * 返回不含上传秘密和对象键的诊断文本。
     *
     * @return 固定脱敏文本
     */
    @Override
    public String toString() {
        return "CreatedAudioUploadTicket[redacted]";
    }
}
