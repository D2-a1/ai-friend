package com.aifriend.contact.infrastructure;

import java.util.List;

import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 测试或显式回退场景使用的失败关闭声学适配器。
 *
 * <p>正式运行由本地声学适配器检查签名方言包；本类不注册为 Spring Bean，防止与
 * 正式适配器形成含义不明的多实现。不得使用字节相等、ASR 文本或任意经验阈值伪造模板。
 *
 * @author Codex
 * @since 1.0.0
 */
public class UnavailableAcousticTemplateAdapter implements AcousticTemplatePort {

    /** 创建失败关闭适配器。 */
    public UnavailableAcousticTemplateAdapter() {
    }

    /** {@inheritDoc} */
    @Override
    public AcousticEnrollmentCandidate enroll(
            AcousticEnrollmentSample first,
            AcousticEnrollmentSample second) {
        throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
    }

    /** {@inheritDoc} */
    @Override
    public AcousticUniqueness classify(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates) {
        throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSafetyCommandDistinct(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates) {
        throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCompatible(
            String dialectCode,
            String dialectPackageVersion,
            String modelVersion,
            String thresholdVersion) {
        return false;
    }
}
