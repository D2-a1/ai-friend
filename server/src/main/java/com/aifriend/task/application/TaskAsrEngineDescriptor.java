package com.aifriend.task.application;

/**
 * 本地识别引擎的非敏感配置快照。
 *
 * @param enabled 是否配置启用
 * @param modelVersion 模型版本
 * @param archiveSha256 模型归档 SHA-256
 * @author Codex
 * @since 1.0.0
 */
public record TaskAsrEngineDescriptor(
        boolean enabled,
        String modelVersion,
        String archiveSha256) {
}
