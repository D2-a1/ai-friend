package com.aifriend.voice.api;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * 受限音频上传凭证响应。
 *
 * @param audioObjectId au_ 前缀音频对象编号
 * @param uploadUrl 短期上传地址
 * @param method 上传 HTTP 方法
 * @param requiredHeaders 必须原样提交的上传请求头
 * @param expiresAt 固定上传凭证过期时间
 * @param objectKey 服务端生成的私有对象键
 * @author Codex
 * @since 1.0.0
 */
public record AudioUploadTicketResp(
        String audioObjectId,
        URI uploadUrl,
        String method,
        Map<String, String> requiredHeaders,
        Instant expiresAt,
        String objectKey) {

    /**
     * 创建不可变且诊断文本脱敏的上传凭证响应。
     */
    public AudioUploadTicketResp {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }

    /**
     * 返回不含上传秘密和对象键的诊断文本。
     *
     * @return 固定脱敏文本
     */
    @Override
    public String toString() {
        return "AudioUploadTicketResp[redacted]";
    }
}
