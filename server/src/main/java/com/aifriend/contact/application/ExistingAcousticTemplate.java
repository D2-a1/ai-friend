package com.aifriend.contact.application;

import java.util.UUID;

/**
 * owner 全局唯一性比较使用的现有有效发音模板。
 *
 * @param aliasId 称呼 UUID，仅用于内部冲突定位
 * @param template 已解密的短期发音内容模板
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 模板模型版本
 * @param thresholdVersion 阈值版本
 * @author Codex
 * @since 1.0.0
 */
public record ExistingAcousticTemplate(
        UUID aliasId,
        byte[] template,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion) {

    /** 创建带防御性模板副本的现有模板。 */
    public ExistingAcousticTemplate {
        template = template.clone();
    }

    /**
     * 获取发音内容模板的防御性副本。
     *
     * @return 发音内容模板副本
     */
    @Override
    public byte[] template() {
        return template.clone();
    }
}
