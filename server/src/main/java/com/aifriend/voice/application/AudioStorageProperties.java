package com.aifriend.voice.application;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 音频上传凭证与开发态私有存储配置。
 *
 * @param uploadTicketTtl 固定上传凭证有效期
 * @param devUploadBaseUrl dev/test 动态上传基础地址
 * @param devStorageRoot dev/test 私有临时音频根目录
 * @param cleanupInterval dev/test 过期清理间隔
 * @param cleanupBatchSize 单次过期清理上限
 * @author Codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.audio")
public record AudioStorageProperties(
        @NotNull Duration uploadTicketTtl,
        @NotNull URI devUploadBaseUrl,
        @NotBlank String devStorageRoot,
        @NotNull Duration cleanupInterval,
        @Min(1) @Max(500) int cleanupBatchSize) {

    /**
     * 校验不可被配置弱化的上传凭证和本地路径边界。
     */
    public AudioStorageProperties {
        Objects.requireNonNull(uploadTicketTtl, "uploadTicketTtl must not be null");
        Objects.requireNonNull(devUploadBaseUrl, "devUploadBaseUrl must not be null");
        Objects.requireNonNull(devStorageRoot, "devStorageRoot must not be null");
        Objects.requireNonNull(cleanupInterval, "cleanupInterval must not be null");
        if (!Duration.ofMinutes(10).equals(uploadTicketTtl)) {
            throw new IllegalArgumentException("音频上传凭证有效期必须固定为 10 分钟");
        }
        String scheme = devUploadBaseUrl.getScheme();
        if ((!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
                || devUploadBaseUrl.getHost() == null
                || devUploadBaseUrl.getUserInfo() != null
                || devUploadBaseUrl.getQuery() != null
                || devUploadBaseUrl.getFragment() != null) {
            throw new IllegalArgumentException("开发态音频上传基础地址格式错误");
        }
        if (devStorageRoot.isBlank()) {
            throw new IllegalArgumentException("开发态音频根目录不能为空");
        }
        if (cleanupInterval.isNegative() || cleanupInterval.isZero()) {
            throw new IllegalArgumentException("音频清理间隔必须大于 0");
        }
    }
}
