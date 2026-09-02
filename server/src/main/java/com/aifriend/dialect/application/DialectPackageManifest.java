package com.aifriend.dialect.application;

/**
 * 受 Ed25519 签名保护的方言包清单。
 *
 * <p>签名覆盖清单原始字节，清单再用 SHA-256 固定声学校准文件和两个 ASR 模型归档。
 * 版本和摘要仅是运行时准入条件；文件缺失、开关关闭或未完成真实语料验收时仍必须失败关闭。
 *
 * @param dialectCode 方言代码
 * @param packageVersion 方言包版本
 * @param acousticEngine 声学引擎标识
 * @param acousticModelVersion 发音内容模板模型版本
 * @param thresholdVersion 校准阈值版本
 * @param primaryAsrModelVersion 主方言 ASR 模型版本
 * @param primaryAsrModelSha256 主方言 ASR 模型归档 SHA-256
 * @param mandarinAssistVersion 普通话辅助版本
 * @param mandarinAssistSha256 普通话辅助模型归档 SHA-256
 * @param fusionRuleVersion 主辅融合规则版本
 * @param alignmentVersion 音频对齐版本
 * @param minimumServerVersion 最低兼容服务端版本
 * @param minimumAndroidAppVersion 最低兼容 Android App 版本
 * @param calibrationFile 声学校准文件的安全相对文件名
 * @param calibrationSha256 声学校准文件 SHA-256 小写十六进制
 * @param signatureKeyId 清单签名公钥编号
 * @param issuedAt 清单签发时间，ISO-8601 UTC
 * @author Codex
 * @since 1.0.0
 */
public record DialectPackageManifest(
        String dialectCode,
        String packageVersion,
        String acousticEngine,
        String acousticModelVersion,
        String thresholdVersion,
        String primaryAsrModelVersion,
        String primaryAsrModelSha256,
        String mandarinAssistVersion,
        String mandarinAssistSha256,
        String fusionRuleVersion,
        String alignmentVersion,
        String minimumServerVersion,
        String minimumAndroidAppVersion,
        String calibrationFile,
        String calibrationSha256,
        String signatureKeyId,
        String issuedAt) {
}
