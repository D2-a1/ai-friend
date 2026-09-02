package com.aifriend.voice.application;

import java.net.URI;
import java.util.Map;

/**
 * 对象存储上传目标。
 *
 * @param uploadUrl 动态上传地址
 * @param method PUT 或 POST
 * @param requiredHeaders 上传时必须原样提交的请求头
 * @author Codex
 * @since 1.0.0
 */
public record AudioUploadTarget(
        URI uploadUrl,
        String method,
        Map<String, String> requiredHeaders) {

    /**
     * 创建不可变上传目标。
     */
    public AudioUploadTarget {
        requiredHeaders = Map.copyOf(requiredHeaders);
    }

    /**
     * 返回不含上传秘密的诊断文本。
     *
     * @return 固定脱敏文本
     */
    @Override
    public String toString() {
        return "AudioUploadTarget[redacted]";
    }
}
