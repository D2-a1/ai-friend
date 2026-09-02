package com.aifriend.task.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地任务语音识别配置。
 *
 * <p>主方言和普通话辅助模型均默认关闭。启用时，模型版本和归档摘要还必须与
 * 当前已验签方言包完全一致；模型归档只从部署文件系统读取，不自动下载。
 *
 * @param alignmentVersion 本地词级时间戳对齐规则版本
 * @param fusionRuleVersion 主辅结果保守融合规则版本
 * @param primary 主方言识别引擎配置
 * @param mandarinAssist 普通话辅助识别引擎配置
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.task-asr")
public record TaskAsrProperties(
        String alignmentVersion,
        String fusionRuleVersion,
        Engine primary,
        Engine mandarinAssist) {

    /**
     * 单个本地 Vosk 模型配置。
     *
     * @param enabled 是否允许加载该模型
     * @param modelVersion 模型版本，必须由签名方言包固定
     * @param archivePath 部署文件系统中的模型 ZIP 路径
     * @param archiveSha256 模型 ZIP 的 SHA-256 小写十六进制
     * @param archiveRoot ZIP 内唯一模型根目录，以斜杠结尾
     * @param runtimeRoot 模型受限解压目录；留空时使用 JVM 临时目录
     * @param maximumAlternatives 最大 N-best 数量，范围 1—3
     * @author Codex
     * @since 1.0.0
     */
    public record Engine(
            boolean enabled,
            String modelVersion,
            String archivePath,
            String archiveSha256,
            String archiveRoot,
            String runtimeRoot,
            int maximumAlternatives) {
    }
}
