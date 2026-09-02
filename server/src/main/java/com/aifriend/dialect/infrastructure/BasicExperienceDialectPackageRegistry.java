package com.aifriend.dialect.infrastructure;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;

/**
 * 为个人测试提供固定声学参数的基础体验注册表。
 *
 * <p>该对象复用既有 MFCC/DTW 端口完成个人称呼和安全指令模板比较，
 * 不包含武冈话 ASR 模型、训练语料或签名发布声明。开关默认关闭。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(prefix = "ai-friend.basic-experience", name = "enabled",
        havingValue = "true")
public class BasicExperienceDialectPackageRegistry implements DialectPackageRegistry {

    /** 基础体验方言代码。 */
    public static final String DIALECT_CODE = "zh-Hans-CN-x-wugang";

    /** 基础体验包版本。 */
    public static final String PACKAGE_VERSION = "basic-experience-v1";

    /** 基础声学模板模型版本。 */
    public static final String ACOUSTIC_MODEL_VERSION = "mfcc-dtw-basic-v1";

    /** 基础声学阈值版本。 */
    public static final String THRESHOLD_VERSION = "basic-personal-v2";

    /** 普通话辅助识别模型版本。 */
    public static final String ASR_MODEL_VERSION = "vosk-model-small-cn-0.22";

    /** 普通话辅助识别模型归档摘要。 */
    public static final String ASR_ARCHIVE_SHA256 =
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";

    /** 基础识别融合规则版本。 */
    public static final String FUSION_RULE_VERSION = "basic-local-direct-v1";

    /** 普通话识别时间对齐规则版本。 */
    public static final String ALIGNMENT_VERSION = "vosk-word-timestamp-v1";

    private final VerifiedDialectPackage basicPackage = new VerifiedDialectPackage(
            new DialectPackageManifest(
                    DIALECT_CODE, PACKAGE_VERSION, "MFCC_DTW_V1",
                    ACOUSTIC_MODEL_VERSION, THRESHOLD_VERSION,
                    ASR_MODEL_VERSION, ASR_ARCHIVE_SHA256,
                    ASR_MODEL_VERSION, ASR_ARCHIVE_SHA256,
                    FUSION_RULE_VERSION, ALIGNMENT_VERSION,
                    "1.0.0", "0.0.1", "built-in-basic-calibration",
                    "0".repeat(64), "basic-experience", "2026-08-28T00:00:00Z"),
            new DialectAcousticCalibration(
                    "MFCC_DTW_V1", 16_000, 25, 10, 26, 13,
                    300, 5_000, -60.0D, 35.0D, 0.20D, 0.01D,
                    0.20D, 1.0D, 0.2D, 1.5D,
                    0.5D, 1.5D, 0.2D));

    /**
     * 创建基础体验方言包注册表。
     */
    public BasicExperienceDialectPackageRegistry() {
        // 固定体验包在字段初始化阶段构建，此处保留显式构造方法以说明生命周期。
    }

    /** {@inheritDoc} */
    @Override
    public DialectPackageState state() {
        return DialectPackageState.BASIC_EXPERIENCE;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<VerifiedDialectPackage> findActive() {
        return Optional.of(basicPackage);
    }
}
