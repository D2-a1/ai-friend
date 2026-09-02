package com.aifriend.contact.infrastructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAudioRange;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.template.application.RoutineCommandRuntimeAcousticPort;
import com.aifriend.template.application.RoutineCommandRuntimeMatch;
import com.aifriend.template.application.RoutineCommandRuntimeTemplate;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

/**
 * 基于已验签 MFCC/DTW 参数的日常指令运行时声学意图分类器。
 *
 * <p>候选先按有限意图聚合最小距离，再使用签名包的一致性、内容区分和冲突阈值
 * 保守分档。实现只比较发音内容，不进行说话人识别或身份认证。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class MfccDtwRoutineCommandMatcherAdapter
        implements RoutineCommandRuntimeAcousticPort {

    private static final int MAXIMUM_TEMPLATES = 30;

    private final DialectPackageRegistry packageRegistry;
    private final TaskWavPcmNormalizer pcmNormalizer = new TaskWavPcmNormalizer();
    private final MfccFeatureExtractor featureExtractor = new MfccFeatureExtractor();
    private final DynamicTimeWarping dynamicTimeWarping = new DynamicTimeWarping();
    private final RoutineCommandTemplateCodec templateCodec =
            new RoutineCommandTemplateCodec();

    /**
     * 创建日常指令运行时声学分类器。
     *
     * @param packageRegistry 已验签方言包注册表
     */
    public MfccDtwRoutineCommandMatcherAdapter(
            DialectPackageRegistry packageRegistry) {
        this.packageRegistry = packageRegistry;
    }

    /** {@inheritDoc} */
    @Override
    public RoutineCommandRuntimeMatch classify(
            ValidatedAudioObject audio,
            TaskAudioRange actionRange,
            List<RoutineCommandRuntimeTemplate> templates,
            TaskClientContext clientContext) {
        VerifiedDialectPackage dialectPackage = requireCompatiblePackage(clientContext);
        DialectAcousticCalibration calibration =
                dialectPackage.acousticCalibration();
        if (audio == null || audio.purpose() != AudioPurpose.TASK
                || !"audio/wav".equals(audio.mediaType())
                || actionRange == null || templates == null
                || templates.isEmpty() || templates.size() > MAXIMUM_TEMPLATES) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        validateRange(audio, actionRange, calibration);

        float[][] candidateFeatures = null;
        try {
            candidateFeatures = extractFeatures(audio, actionRange, calibration);
            List<IntentDistance> distances = minimumDistanceByIntent(
                    candidateFeatures, templates, calibration);
            return classifyDistances(distances, calibration);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        } finally {
            clear(candidateFeatures);
        }
    }

    private float[][] extractFeatures(
            ValidatedAudioObject audio,
            TaskAudioRange actionRange,
            DialectAcousticCalibration calibration) {
        PcmAudio fullAudio = null;
        PcmAudio segment = null;
        try {
            fullAudio = pcmNormalizer.normalize(audio, calibration);
            double[] allSamples = fullAudio.samples();
            try {
                int from = Math.toIntExact((long) actionRange.startMs()
                        * calibration.sampleRateHz() / 1_000L);
                int to = Math.toIntExact((long) actionRange.endMs()
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
            return featureExtractor.extract(segment, calibration);
        } finally {
            if (fullAudio != null) {
                fullAudio.clear();
            }
            if (segment != null) {
                segment.clear();
            }
        }
    }

    private List<IntentDistance> minimumDistanceByIntent(
            float[][] candidateFeatures,
            List<RoutineCommandRuntimeTemplate> templates,
            DialectAcousticCalibration calibration) {
        Map<TaskIntent, IntentDistance> minimumByIntent =
                new EnumMap<>(TaskIntent.class);
        for (RoutineCommandRuntimeTemplate template : templates) {
            if (template == null || !isCommunicationIntent(template.intent())) {
                throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
            }
            byte[] material = template.material();
            float[][] existingFeatures = null;
            try {
                existingFeatures = templateCodec.decode(material);
                double distance = dynamicTimeWarping.distance(
                        candidateFeatures, existingFeatures,
                        calibration.dtwWindowRatio());
                IntentDistance current = new IntentDistance(
                        template.intent(), distance);
                minimumByIntent.merge(template.intent(), current,
                        (left, right) -> DISTANCE_ORDER.compare(left, right) <= 0
                                ? left : right);
            } finally {
                Arrays.fill(material, (byte) 0);
                clear(existingFeatures);
            }
        }
        List<IntentDistance> result = new ArrayList<>(minimumByIntent.values());
        result.sort(DISTANCE_ORDER);
        return result;
    }

    private RoutineCommandRuntimeMatch classifyDistances(
            List<IntentDistance> distances,
            DialectAcousticCalibration calibration) {
        if (distances.isEmpty()
                || distances.get(0).distance()
                        > calibration.uniquenessDistinctMinDistance()) {
            return RoutineCommandRuntimeMatch.none();
        }
        IntentDistance first = distances.get(0);
        double requiredMargin = calibration.uniquenessDistinctMinDistance()
                - calibration.uniquenessConflictMaxDistance();
        boolean hasSafeMargin = distances.size() == 1
                || distances.get(1).distance() - first.distance() >= requiredMargin;
        if (first.distance() <= calibration.enrollmentConsistencyMaxDistance()
                && hasSafeMargin) {
            return RoutineCommandRuntimeMatch.unique(first.intent());
        }
        return RoutineCommandRuntimeMatch.ambiguous();
    }

    private void validateRange(
            ValidatedAudioObject audio,
            TaskAudioRange range,
            DialectAcousticCalibration calibration) {
        int durationMs = range.endMs() - range.startMs();
        if (range.startMs() < 0 || range.endMs() > audio.actualDurationMs()
                || durationMs < calibration.minimumDurationMs()
                || durationMs > calibration.maximumDurationMs()) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
    }

    private VerifiedDialectPackage requireCompatiblePackage(
            TaskClientContext clientContext) {
        VerifiedDialectPackage dialectPackage = packageRegistry.findActive()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
        DialectPackageManifest manifest = dialectPackage.manifest();
        boolean compatible = clientContext != null
                && manifest.dialectCode().equals(clientContext.dialectCode())
                && manifest.packageVersion().equals(
                        clientContext.dialectPackageVersion())
                && manifest.acousticModelVersion().equals(
                        clientContext.templateModelVersion())
                && manifest.thresholdVersion().equals(
                        clientContext.thresholdVersion());
        if (!compatible) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        return dialectPackage;
    }

    private boolean isCommunicationIntent(TaskIntent intent) {
        return intent == TaskIntent.SEND_MESSAGE
                || intent == TaskIntent.VOICE_CALL
                || intent == TaskIntent.VIDEO_CALL;
    }

    private static void clear(float[][] features) {
        if (features != null) {
            for (float[] frame : features) {
                Arrays.fill(frame, 0.0F);
            }
        }
    }

    private static final Comparator<IntentDistance> DISTANCE_ORDER =
            Comparator.comparingDouble(IntentDistance::distance)
                    .thenComparing(item -> item.intent().name());

    private record IntentDistance(TaskIntent intent, double distance) {
    }
}
