package com.aifriend.voicecollection.application;

import java.util.UUID;

/**
 * 本机训练输入流式写入端口。
 *
 * @author codex
 * @since 1.0.0
 */
public interface VoiceTrainingInputSinkPort {

    /**
     * 为一个已复验数据集打开只创建不覆盖的暂存会话。
     *
     * @param datasetId 数据集 UUID
     * @param datasetVersion 数据集版本
     * @param datasetManifestSha256 V30 数据集清单 SHA-256
     * @return 必须关闭的暂存会话
     */
    VoiceTrainingInputSink open(
            UUID datasetId,
            String datasetVersion,
            byte[] datasetManifestSha256);
}
