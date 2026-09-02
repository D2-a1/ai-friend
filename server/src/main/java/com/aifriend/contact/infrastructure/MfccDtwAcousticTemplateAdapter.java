package com.aifriend.contact.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 基于已验证方言包执行本地 MFCC 与 DTW 发音内容模板注册的适配器。
 *
 * <p>本实现只比较短称呼的时序发音内容，不输出说话人身份，不使用展示文字或 ASR
 * 替代声学唯一性，也不发起网络调用。方言包关闭、损坏或版本不兼容时失败关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class MfccDtwAcousticTemplateAdapter implements AcousticTemplatePort {

    private static final int MAX_EXISTING_TEMPLATES = 100;

    private final DialectPackageRegistry dialectPackageRegistry;
    private final WavPcmNormalizer pcmNormalizer;
    private final MfccFeatureExtractor featureExtractor;
    private final DynamicTimeWarping dynamicTimeWarping;
    private final AcousticTemplateCodec templateCodec;

    /**
     * 创建只依赖本地已验证方言包的发音内容模板适配器。
     *
     * @param dialectPackageRegistry 当前签名方言包注册表
     */
    public MfccDtwAcousticTemplateAdapter(
            DialectPackageRegistry dialectPackageRegistry) {
        this.dialectPackageRegistry = dialectPackageRegistry;
        this.pcmNormalizer = new WavPcmNormalizer();
        this.featureExtractor = new MfccFeatureExtractor();
        this.dynamicTimeWarping = new DynamicTimeWarping();
        this.templateCodec = new AcousticTemplateCodec();
    }

    /** {@inheritDoc} */
    @Override
    public AcousticEnrollmentCandidate enroll(
            AcousticEnrollmentSample first,
            AcousticEnrollmentSample second) {
        VerifiedDialectPackage dialectPackage = requireActivePackage();
        DialectAcousticCalibration calibration = dialectPackage.acousticCalibration();
        float[][] firstFeatures = featureExtractor.extract(
                pcmNormalizer.normalize(first, calibration), calibration);
        float[][] secondFeatures = featureExtractor.extract(
                pcmNormalizer.normalize(second, calibration), calibration);
        double enrollmentDistance = dynamicTimeWarping.distance(
                firstFeatures, secondFeatures, calibration.dtwWindowRatio());
        if (enrollmentDistance
                > calibration.enrollmentConsistencyMaxDistance()) {
            throw new BusinessException(ErrorCode.ENROLLMENT_INCONSISTENT);
        }
        byte[] template;
        try {
            template = templateCodec.encode(List.of(firstFeatures, secondFeatures));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        DialectPackageManifest manifest = dialectPackage.manifest();
        return new AcousticEnrollmentCandidate(
                template,
                manifest.dialectCode(),
                manifest.packageVersion(),
                manifest.acousticModelVersion(),
                manifest.thresholdVersion());
    }

    /** {@inheritDoc} */
    @Override
    public AcousticUniqueness classify(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates) {
        VerifiedDialectPackage dialectPackage = requireActivePackage();
        if (candidate == null
                || existingTemplates == null
                || existingTemplates.size() > MAX_EXISTING_TEMPLATES) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        validateVersion(candidate.dialectCode(), candidate.dialectPackageVersion(),
                candidate.modelVersion(), candidate.thresholdVersion(),
                dialectPackage.manifest());
        AcousticTemplateCodec.DecodedAcousticTemplate candidateTemplate = decode(
                candidate.template());
        if (existingTemplates.isEmpty()) {
            return AcousticUniqueness.DISTINCT;
        }

        DialectAcousticCalibration calibration = dialectPackage.acousticCalibration();
        double minimumDistance = Double.POSITIVE_INFINITY;
        for (ExistingAcousticTemplate existing : existingTemplates) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
            }
            validateVersion(existing.dialectCode(), existing.dialectPackageVersion(),
                    existing.modelVersion(), existing.thresholdVersion(),
                    dialectPackage.manifest());
            AcousticTemplateCodec.DecodedAcousticTemplate existingTemplate = decode(
                    existing.template());
            minimumDistance = Math.min(minimumDistance,
                    minimumPairDistance(candidateTemplate, existingTemplate,
                            calibration.dtwWindowRatio()));
            if (minimumDistance <= calibration.uniquenessConflictMaxDistance()) {
                return AcousticUniqueness.CONFLICT;
            }
        }
        return minimumDistance < calibration.uniquenessDistinctMinDistance()
                ? AcousticUniqueness.BORDERLINE
                : AcousticUniqueness.DISTINCT;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSafetyCommandDistinct(
            AcousticEnrollmentCandidate candidate,
            List<ExistingAcousticTemplate> existingTemplates) {
        VerifiedDialectPackage dialectPackage = requireActivePackage();
        if (candidate == null
                || existingTemplates == null
                || existingTemplates.size() > MAX_EXISTING_TEMPLATES) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        validateVersion(candidate.dialectCode(), candidate.dialectPackageVersion(),
                candidate.modelVersion(), candidate.thresholdVersion(),
                dialectPackage.manifest());
        AcousticTemplateCodec.DecodedAcousticTemplate candidateTemplate = decode(
                candidate.template());
        if (existingTemplates.isEmpty()) {
            return true;
        }

        DialectAcousticCalibration calibration = dialectPackage.acousticCalibration();
        double candidateBaseline = internalPairDistance(
                candidateTemplate, calibration.dtwWindowRatio());
        for (ExistingAcousticTemplate existing : existingTemplates) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
            }
            validateVersion(existing.dialectCode(), existing.dialectPackageVersion(),
                    existing.modelVersion(), existing.thresholdVersion(),
                    dialectPackage.manifest());
            AcousticTemplateCodec.DecodedAcousticTemplate existingTemplate = decode(
                    existing.template());
            double existingBaseline = internalPairDistance(
                    existingTemplate, calibration.dtwWindowRatio());
            double crossDistance = minimumPairDistance(
                    candidateTemplate, existingTemplate, calibration.dtwWindowRatio());
            double relativeMargin = crossDistance
                    - Math.max(candidateBaseline, existingBaseline);
            if (relativeMargin < calibration.taskAliasMinimumMargin()) {
                return false;
            }
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCompatible(
            String dialectCode,
            String dialectPackageVersion,
            String modelVersion,
            String thresholdVersion) {
        return dialectPackageRegistry.findActive()
                .map(VerifiedDialectPackage::manifest)
                .map(manifest -> manifest.dialectCode().equals(dialectCode)
                        && manifest.packageVersion().equals(dialectPackageVersion)
                        && manifest.acousticModelVersion().equals(modelVersion)
                        && manifest.thresholdVersion().equals(thresholdVersion))
                .orElse(false);
    }

    private VerifiedDialectPackage requireActivePackage() {
        return dialectPackageRegistry.findActive()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
    }

    private void validateVersion(
            String dialectCode,
            String dialectPackageVersion,
            String modelVersion,
            String thresholdVersion,
            DialectPackageManifest manifest) {
        if (!manifest.dialectCode().equals(dialectCode)
                || !manifest.packageVersion().equals(dialectPackageVersion)
                || !manifest.acousticModelVersion().equals(modelVersion)
                || !manifest.thresholdVersion().equals(thresholdVersion)) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    private AcousticTemplateCodec.DecodedAcousticTemplate decode(byte[] template) {
        try {
            return templateCodec.decode(template);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    /**
     * 双录顺序不具有业务含义，因此比较四种配对并采用最保守的最小距离。
     */
    private double minimumPairDistance(
            AcousticTemplateCodec.DecodedAcousticTemplate candidate,
            AcousticTemplateCodec.DecodedAcousticTemplate existing,
            double windowRatio) {
        double minimum = dynamicTimeWarping.distance(
                candidate.first(), existing.first(), windowRatio);
        minimum = Math.min(minimum, dynamicTimeWarping.distance(
                candidate.first(), existing.second(), windowRatio));
        minimum = Math.min(minimum, dynamicTimeWarping.distance(
                candidate.second(), existing.first(), windowRatio));
        return Math.min(minimum, dynamicTimeWarping.distance(
                candidate.second(), existing.second(), windowRatio));
    }

    private double internalPairDistance(
            AcousticTemplateCodec.DecodedAcousticTemplate template,
            double windowRatio) {
        return dynamicTimeWarping.distance(
                template.first(), template.second(), windowRatio);
    }
}
