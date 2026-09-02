package com.aifriend.voicecollection.application;

import java.nio.file.Path;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 本机训练输入导出配置。
 *
 * <p>能力默认关闭。开启时根目录必须为绝对路径，导出只在该目录下创建
 * 暂存目录和最终数据集目录，不覆盖既有结果。</p>
 *
 * @param enabled 是否允许内部离线导出
 * @param rootDirectory 本机导出根目录
 * @param maximumTotalBytes 单次导出音频总字节上限
 * @author codex
 * @since 1.0.0
 */
@Validated
@ConfigurationProperties(prefix = "ai-friend.voice-training-export")
public record VoiceTrainingInputExportProperties(
        boolean enabled,
        @NotBlank String rootDirectory,
        @Min(1) @Max(10_737_418_240L) long maximumTotalBytes) {

    /** 单个采集音频沿用上传链的 20 MiB 硬上限。 */
    public static final long MAXIMUM_AUDIO_BYTES = 20_971_520L;

    /**
     * 复验导出开关、路径和容量边界。
     */
    public VoiceTrainingInputExportProperties {
        if (rootDirectory == null || rootDirectory.isBlank()) {
            throw new IllegalArgumentException("训练输入导出根目录不能为空");
        }
        if (maximumTotalBytes < 1L || maximumTotalBytes > 10_737_418_240L) {
            throw new IllegalArgumentException("训练输入导出总大小必须在 1 字节至 10 GiB 之间");
        }
        if (enabled && !Path.of(rootDirectory).isAbsolute()) {
            throw new IllegalArgumentException("启用训练输入导出时必须使用绝对根目录");
        }
    }
}
