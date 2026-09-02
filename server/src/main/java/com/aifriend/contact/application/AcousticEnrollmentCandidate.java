package com.aifriend.contact.application;

/**
 * 两遍录音一致后生成的发音内容模板候选。
 *
 * @param template 发音内容模板，不得用于声纹身份判断
 * @param dialectCode 方言代码
 * @param dialectPackageVersion 方言包版本
 * @param modelVersion 模板模型版本
 * @param thresholdVersion 注册唯一性阈值版本
 * @author Codex
 * @since 1.0.0
 */
public record AcousticEnrollmentCandidate(
        byte[] template,
        String dialectCode,
        String dialectPackageVersion,
        String modelVersion,
        String thresholdVersion) {

    /** 创建带防御性模板副本的候选。 */
    public AcousticEnrollmentCandidate {
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

    /**
     * 返回不包含模板内容的诊断文本。
     *
     * @return 不包含模板内容的诊断文本
     */
    @Override
    public String toString() {
        return "AcousticEnrollmentCandidate[dialectCode=" + dialectCode
                + ", dialectPackageVersion=" + dialectPackageVersion
                + ", modelVersion=" + modelVersion
                + ", thresholdVersion=" + thresholdVersion
                + ", templateSizeBytes=" + template.length + "]";
    }
}
