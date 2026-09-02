package com.aifriend.voicecollection.application;

import java.nio.file.Path;

/**
 * 已完成本机原子发布的训练输入导出回执。
 *
 * @param outputDirectory 最终导出目录
 * @param sampleCount 已导出样本数
 * @param audioBytes 已导出音频总字节数
 * @param datasetManifestSha256 V30 数据集清单 SHA-256
 * @author codex
 * @since 1.0.0
 */
public record VoiceTrainingInputExportReceipt(
        Path outputDirectory,
        int sampleCount,
        long audioBytes,
        byte[] datasetManifestSha256) {

    /**
     * 创建带规范化路径和摘要副本的导出回执。
     */
    public VoiceTrainingInputExportReceipt {
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        datasetManifestSha256 = datasetManifestSha256.clone();
    }

    /**
     * 获取 V30 数据集清单摘要副本。
     *
     * @return V30 数据集清单 SHA-256 副本
     */
    @Override
    public byte[] datasetManifestSha256() {
        return datasetManifestSha256.clone();
    }
}
