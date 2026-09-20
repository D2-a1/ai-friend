package com.aifriend.contact.infrastructure;

import java.util.List;
import java.util.EnumSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aifriend.contact.application.AcousticEnrollmentCandidate;
import com.aifriend.contact.application.AcousticEnrollmentSample;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.contact.application.AcousticUniqueness;
import com.aifriend.contact.application.ExistingAcousticTemplate;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(MfccDtwAcousticTemplateAdapter.class);

    private enum ComparisonReason {
        CANDIDATE_VARIATION, EXISTING_VARIATION, INSUFFICIENT_MARGIN,
        ABSOLUTE_CONFLICT, SIGNED_ABSOLUTE_BAND
    }

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
            LOGGER.info("Alias enrollment consistency outcome=REJECTED");
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
        EnumSet<ComparisonReason> reasons = EnumSet.noneOf(ComparisonReason.class);
        if (existingTemplates.isEmpty()) {
            return reportComparison(AcousticUniqueness.DISTINCT, 0, reasons);
        }

        DialectAcousticCalibration calibration = dialectPackage.acousticCalibration();
        boolean basicExperience = dialectPackageRegistry.state() == DialectPackageState.BASIC_EXPERIENCE;
        double candidateBaseline = basicExperience
                ? internalPairDistance(candidateTemplate, calibration.dtwWindowRatio()) : Double.NaN;
        boolean borderline = false;
        for (ExistingAcousticTemplate existing : existingTemplates) {
            if (existing == null) {
                throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
            }
            validateVersion(existing.dialectCode(), existing.dialectPackageVersion(),
                    existing.modelVersion(), existing.thresholdVersion(),
                    dialectPackage.manifest());
            AcousticTemplateCodec.DecodedAcousticTemplate existingTemplate = decode(
                    existing.template());
            double crossDistance = minimumPairDistance(candidateTemplate, existingTemplate,
                    calibration.dtwWindowRatio());
            if (crossDistance <= calibration.uniquenessConflictMaxDistance()) {
                reasons.add(ComparisonReason.ABSOLUTE_CONFLICT);
                return reportComparison(AcousticUniqueness.CONFLICT, existingTemplates.size(), reasons);
            }
            if (crossDistance < calibration.uniquenessDistinctMinDistance()
                    && !(basicExperience && hasStablePersonalSeparation(
                            candidateBaseline, existingTemplate, crossDistance, calibration, reasons))) {
                borderline = true;
                if (!basicExperience) {
                    reasons.add(ComparisonReason.SIGNED_ABSOLUTE_BAND);
                }
            }
        }
        return reportComparison(borderline ? AcousticUniqueness.BORDERLINE : AcousticUniqueness.DISTINCT,
                existingTemplates.size(), reasons);
    }

    private AcousticUniqueness reportComparison(
            AcousticUniqueness outcome, int templateCount, EnumSet<ComparisonReason> reasons) {
        // One event per call: no names, IDs, feature values, distances or audio.
        LOGGER.info("Alias enrollment comparison outcome={} mode={} templates={} reasons={}",
                outcome, dialectPackageRegistry.state(), templateCount, reasons);
        return outcome;
    }

    /*
     * 基础体验的绝对类间阈值未由真实语料标定，不能将 1.5 候选距离当作不同
     * 短词必须达到的间距。仅在双方双录均满足注册一致性距离，且最小
     * 跨录音距离仍比双方双录基线多出既有候选余量时，补充 DISTINCT 证据。
     * 绝对冲突优先拒绝，每个已有模板都必须通过；签名方言包仍用原绝对三档。
     * 双录间距离不等于运行时探针到最近一遍的距离，不能混用后者的上限。
     * 不改特征、模板版本或数值阈值，不使用文字、ASR 或绑定数量放行。
     */
    private boolean hasStablePersonalSeparation(
            double candidateBaseline,
            AcousticTemplateCodec.DecodedAcousticTemplate existing,
            double crossDistance,
            DialectAcousticCalibration calibration,
            EnumSet<ComparisonReason> reasons) {
        double existingBaseline = internalPairDistance(existing, calibration.dtwWindowRatio());
        double baseline = Math.max(candidateBaseline, existingBaseline);
        if (candidateBaseline > calibration.enrollmentConsistencyMaxDistance()) {
            reasons.add(ComparisonReason.CANDIDATE_VARIATION);
        }
        if (existingBaseline > calibration.enrollmentConsistencyMaxDistance()) {
            reasons.add(ComparisonReason.EXISTING_VARIATION);
        }
        if (crossDistance - baseline < calibration.taskAliasMinimumMargin()) {
            reasons.add(ComparisonReason.INSUFFICIENT_MARGIN);
        }
        return baseline <= calibration.enrollmentConsistencyMaxDistance()
                && crossDistance - baseline >= calibration.taskAliasMinimumMargin();
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
