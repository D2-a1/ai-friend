package com.aifriend.contact.infrastructure;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.template.application.RoutineCommandAcousticCandidate;
import com.aifriend.template.application.RoutineCommandAcousticTemplatePort;
import com.aifriend.template.application.RoutineCommandAudioSnapshot;
import com.aifriend.template.application.RoutineCommandLearningJob;
import com.aifriend.template.application.RoutineCommandPlainTemplate;
import com.aifriend.template.application.RoutineCommandTemplateMatch;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 基于已验签方言包提取和比较日常动作发音内容模板。
 *
 * <p>实现只切取 Outbox 固化的完整动作词边界，不比较联系人或消息正文，
 * 不输出说话人身份或声纹结论。方言包及全部版本必须精确匹配。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandAcousticTemplateAdapter
        implements RoutineCommandAcousticTemplatePort {

    private static final int MAXIMUM_EXISTING_TEMPLATES = 30;

    private final DialectPackageRegistry packageRegistry;
    private final TaskWavPcmNormalizer pcmNormalizer = new TaskWavPcmNormalizer();
    private final MfccFeatureExtractor featureExtractor = new MfccFeatureExtractor();
    private final DynamicTimeWarping dynamicTimeWarping = new DynamicTimeWarping();
    private final RoutineCommandTemplateCodec templateCodec =
            new RoutineCommandTemplateCodec();

    /**
     * 创建本地日常指令声学适配器。
     *
     * @param packageRegistry 已验签方言包注册表
     */
    public RoutineCommandAcousticTemplateAdapter(
            DialectPackageRegistry packageRegistry) {
        this.packageRegistry = packageRegistry;
    }

    /** {@inheritDoc} */
    @Override
    public RoutineCommandAcousticCandidate extract(
            RoutineCommandLearningJob job,
            RoutineCommandAudioSnapshot audio) {
        VerifiedDialectPackage dialectPackage = requireCompatiblePackage(job);
        DialectAcousticCalibration calibration =
                dialectPackage.acousticCalibration();
        int durationMs = job.actionEndMs() - job.actionStartMs();
        if (!"audio/wav".equals(audio.mediaType())
                || job.actionStartMs() < 0
                || job.actionEndMs() > audio.actualDurationMs()
                || durationMs < calibration.minimumDurationMs()
                || durationMs > calibration.maximumDurationMs()) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }

        byte[] source = audio.audioContent();
        ValidatedAudioObject validated = new ValidatedAudioObject(
                job.audioObjectId(), job.ownerUserId(), AudioPurpose.TASK,
                audio.mediaType(), source, audio.actualDurationMs(),
                "routine-learning", 0L);
        Arrays.fill(source, (byte) 0);
        PcmAudio fullAudio = null;
        PcmAudio segment = null;
        float[][] features = null;
        byte[] material = null;
        try {
            fullAudio = pcmNormalizer.normalize(validated, calibration);
            double[] allSamples = fullAudio.samples();
            try {
                int from = Math.toIntExact((long) job.actionStartMs()
                        * calibration.sampleRateHz() / 1_000L);
                int to = Math.toIntExact((long) job.actionEndMs()
                        * calibration.sampleRateHz() / 1_000L);
                if (from < 0 || to <= from || to > allSamples.length) {
                    throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
                }
                double[] slice = Arrays.copyOfRange(allSamples, from, to);
                segment = new PcmAudio(slice, calibration.sampleRateHz(), 0.0D);
                Arrays.fill(slice, 0.0D);
            } finally {
                Arrays.fill(allSamples, 0.0D);
            }
            features = featureExtractor.extract(segment, calibration);
            material = templateCodec.encode(features);
            return new RoutineCommandAcousticCandidate(material);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        } finally {
            validated.close();
            if (fullAudio != null) {
                fullAudio.clear();
            }
            if (segment != null) {
                segment.clear();
            }
            clear(features);
            if (material != null) {
                Arrays.fill(material, (byte) 0);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RoutineCommandTemplateMatch> findMergeTarget(
            RoutineCommandAcousticCandidate candidate,
            List<RoutineCommandPlainTemplate> existingTemplates,
            RoutineCommandLearningJob job) {
        VerifiedDialectPackage dialectPackage = requireCompatiblePackage(job);
        if (candidate == null || existingTemplates == null
                || existingTemplates.size() > MAXIMUM_EXISTING_TEMPLATES) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        byte[] candidateMaterial = candidate.material();
        float[][] candidateFeatures = null;
        try {
            candidateFeatures = templateCodec.decode(candidateMaterial);
            Optional<RoutineCommandTemplateMatch> closest = Optional.empty();
            for (RoutineCommandPlainTemplate existing : existingTemplates) {
                if (existing == null) {
                    throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
                }
                byte[] existingMaterial = existing.material();
                float[][] existingFeatures = null;
                try {
                    existingFeatures = templateCodec.decode(existingMaterial);
                    double distance = dynamicTimeWarping.distance(
                            candidateFeatures,
                            existingFeatures,
                            dialectPackage.acousticCalibration().dtwWindowRatio());
                    RoutineCommandTemplateMatch current =
                            new RoutineCommandTemplateMatch(
                                    existing.templateId(), existing.version(), distance);
                    if (closest.isEmpty()
                            || MATCH_ORDER.compare(current, closest.orElseThrow()) < 0) {
                        closest = Optional.of(current);
                    }
                } finally {
                    Arrays.fill(existingMaterial, (byte) 0);
                    clear(existingFeatures);
                }
            }
            double maximumDistance = dialectPackage.acousticCalibration()
                    .enrollmentConsistencyMaxDistance();
            return closest.filter(match ->
                    match.normalizedDistance() <= maximumDistance);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        } finally {
            Arrays.fill(candidateMaterial, (byte) 0);
            clear(candidateFeatures);
        }
    }

    private VerifiedDialectPackage requireCompatiblePackage(
            RoutineCommandLearningJob job) {
        VerifiedDialectPackage dialectPackage = packageRegistry.findActive()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
        DialectPackageManifest manifest = dialectPackage.manifest();
        if (!manifest.dialectCode().equals(job.dialectCode())
                || !manifest.packageVersion().equals(job.dialectPackageVersion())
                || !manifest.acousticModelVersion().equals(job.templateModelVersion())
                || !manifest.thresholdVersion().equals(job.thresholdVersion())) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        return dialectPackage;
    }

    private static final Comparator<RoutineCommandTemplateMatch> MATCH_ORDER =
            Comparator.comparingDouble(RoutineCommandTemplateMatch::normalizedDistance)
                    .thenComparing(RoutineCommandTemplateMatch::templateId);

    private static void clear(float[][] features) {
        if (features != null) {
            for (float[] frame : features) {
                Arrays.fill(frame, 0.0F);
            }
        }
    }
}
