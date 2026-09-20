package com.aifriend.contact.infrastructure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.contact.application.ContactAliasRepositoryPort;
import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskContactCandidate;
import com.aifriend.task.application.TaskContactMatcherPort;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 使用已验签方言包在 owner 范围执行个人称呼 MFCC/DTW 匹配。
 *
 * <p>优先使用 ASR 词级时间戳限定原声切片，同时执行有界声学滑窗作为方言切词失败的兜底；
 * 仅 ACTIVE 绑定与兼容有效称呼可进入 Top-1/Top-3；边界情况必须让用户选择。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class MfccDtwTaskContactMatcherAdapter implements TaskContactMatcherPort {

    private static final int MAXIMUM_ALIASES = 100;
    private static final int MAXIMUM_WORDS = 120;
    private static final int MAXIMUM_WORD_SPAN = 12;
    private static final int MAXIMUM_WINDOWS = 1_440;
    private static final int MAXIMUM_RESULTS = 3;
    private static final int MAXIMUM_ACOUSTIC_SEARCH_MS = 15_000;
    private static final int ACOUSTIC_WINDOW_STEP_MS = 100;
    private static final int ACOUSTIC_WINDOW_QUANTUM_MS = 20;

    private final ContactAliasRepositoryPort aliasRepository;
    private final ContactBindingRepositoryPort bindingRepository;
    private final SensitiveDataProtector protector;
    private final DigestService digestService;
    private final DialectPackageRegistry packageRegistry;
    private final TaskWavPcmNormalizer pcmNormalizer = new TaskWavPcmNormalizer();
    private final MfccFeatureExtractor featureExtractor = new MfccFeatureExtractor();
    private final DynamicTimeWarping dtw = new DynamicTimeWarping();
    private final AcousticTemplateCodec templateCodec = new AcousticTemplateCodec();

    /**
     * 创建 owner 范围本地称呼匹配器。
     *
     * @param aliasRepository 有效称呼仓储端口
     * @param bindingRepository 联系人绑定仓储端口
     * @param protector 称呼、备注和模板解密器
     * @param digestService 模板完整性摘要服务
     * @param packageRegistry 已验签方言包注册表
     */
    public MfccDtwTaskContactMatcherAdapter(
            ContactAliasRepositoryPort aliasRepository,
            ContactBindingRepositoryPort bindingRepository,
            SensitiveDataProtector protector,
            DigestService digestService,
            DialectPackageRegistry packageRegistry) {
        this.aliasRepository = aliasRepository;
        this.bindingRepository = bindingRepository;
        this.protector = protector;
        this.digestService = digestService;
        this.packageRegistry = packageRegistry;
    }

    /** {@inheritDoc} */
    @Override
    public List<TaskContactCandidate> match(
            UUID ownerUserId,
            ValidatedAudioObject audioObject,
            TaskSpeechRecognition recognition,
            TaskClientContext context) {
        VerifiedDialectPackage dialectPackage = packageRegistry.findActive()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
        verifyContext(context, dialectPackage.manifest());
        List<TaskRecognizedWord> words = requirePrimaryWords(recognition, audioObject);
        Map<UUID, ContactBinding> activeBindings = activeBindings(ownerUserId);
        List<ContactAlias> aliases = aliasRepository.findActiveByOwner(ownerUserId);
        if (aliases.size() > MAXIMUM_ALIASES) {
            throw new BusinessException(ErrorCode.CONTACT_ALIAS_INCOMPATIBLE);
        }
        List<AliasTemplate> templates = loadTemplates(
                aliases, activeBindings, dialectPackage.manifest());
        if (templates.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }

        PcmAudio taskAudio = pcmNormalizer.normalize(
                audioObject, dialectPackage.acousticCalibration());
        Map<AudioWindow, float[][]> featureCache = new LinkedHashMap<>();
        try {
            Map<UUID, ContactScore> bestByContact = new LinkedHashMap<>();
            for (AliasTemplate template : templates) {
                double distance = minimumDistance(
                        taskAudio, words, template, dialectPackage.acousticCalibration(),
                        featureCache);
                if (Double.isFinite(distance)) {
                    bestByContact.merge(template.contactId(),
                            new ContactScore(template, distance),
                            (left, right) -> left.distance() <= right.distance()
                                    ? left : right);
                }
            }
            return classify(new ArrayList<>(bestByContact.values()),
                    dialectPackage.acousticCalibration(), activeBindings.size() == 1);
        } finally {
            taskAudio.clear();
            featureCache.values().forEach(MfccDtwTaskContactMatcherAdapter::clear);
            templates.forEach(AliasTemplate::clear);
        }
    }

    private void verifyContext(
            TaskClientContext context,
            DialectPackageManifest manifest) {
        if (context == null
                || !manifest.dialectCode().equals(context.dialectCode())
                || !manifest.packageVersion().equals(context.dialectPackageVersion())
                || !manifest.acousticModelVersion().equals(context.templateModelVersion())
                || !manifest.thresholdVersion().equals(context.thresholdVersion())) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    private List<TaskRecognizedWord> requirePrimaryWords(
            TaskSpeechRecognition recognition,
            ValidatedAudioObject audio) {
        TaskTranscriptCandidate primary = recognition.nBest().stream()
                .filter(candidate -> candidate.source() == TaskAsrSource.PRIMARY)
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.AUDIO_SEGMENT_UNCERTAIN));
        List<TaskRecognizedWord> words = primary.words();
        if (words.isEmpty() || words.size() > MAXIMUM_WORDS) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        int previousEnd = -1;
        for (TaskRecognizedWord word : words) {
            if (word == null || word.startMs() < 0 || word.endMs() <= word.startMs()
                    || word.endMs() > audio.actualDurationMs()
                    || word.startMs() < previousEnd) {
                throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
            }
            previousEnd = word.endMs();
        }
        return words;
    }

    private Map<UUID, ContactBinding> activeBindings(UUID ownerUserId) {
        Map<UUID, ContactBinding> result = new HashMap<>();
        bindingRepository.findByOwner(ownerUserId, ContactStatus.ACTIVE, 0, 20)
                .items().forEach(binding -> result.put(binding.id(), binding));
        return result;
    }

    private List<AliasTemplate> loadTemplates(
            List<ContactAlias> aliases,
            Map<UUID, ContactBinding> bindings,
            DialectPackageManifest manifest) {
        List<AliasTemplate> result = new ArrayList<>();
        try {
            for (ContactAlias alias : aliases) {
                ContactBinding binding = bindings.get(alias.bindingId());
                if (binding == null) {
                    continue;
                }
                requireCompatible(alias, manifest);
                byte[] templateBytes = decryptVerifiedTemplate(alias);
                try {
                    AcousticTemplateCodec.DecodedAcousticTemplate decoded =
                            templateCodec.decode(templateBytes);
                    String aliasText = protector.decrypt(alias.displayTextCipher());
                    String displayName = binding.remarkCipher() == null
                            ? aliasText : protector.decrypt(binding.remarkCipher());
                    result.add(new AliasTemplate(
                            binding.id(), displayName, aliasText,
                            decoded.first(), decoded.second()));
                } finally {
                    Arrays.fill(templateBytes, (byte) 0);
                }
            }
            return result;
        } catch (RuntimeException exception) {
            result.forEach(AliasTemplate::clear);
            if (exception instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.CONTACT_ALIAS_INCOMPATIBLE);
        }
    }

    private void requireCompatible(ContactAlias alias, DialectPackageManifest manifest) {
        if (alias == null || !alias.hasPersistedMaterial()
                || !alias.matchesVersions(
                        manifest.dialectCode(), manifest.packageVersion(),
                        manifest.acousticModelVersion(), manifest.thresholdVersion())) {
            throw new BusinessException(ErrorCode.CONTACT_ALIAS_INCOMPATIBLE);
        }
    }

    private byte[] decryptVerifiedTemplate(ContactAlias alias) {
        byte[] template = protector.decryptBytes(alias.templateCipher());
        byte[] actualDigest = digestService.sha256(template);
        try {
            if (!digestService.constantTimeEquals(alias.templateDigest(), actualDigest)) {
                throw new BusinessException(ErrorCode.CONTACT_ALIAS_INCOMPATIBLE);
            }
            return template;
        } finally {
            Arrays.fill(actualDigest, (byte) 0);
        }
    }

    private double minimumDistance(
            PcmAudio audio,
            List<TaskRecognizedWord> words,
            AliasTemplate template,
            DialectAcousticCalibration calibration,
            Map<AudioWindow, float[][]> featureCache) {
        int expectedDurationMs = expectedDurationMs(template, calibration);
        Set<AudioWindow> windows = new LinkedHashSet<>();
        for (int startIndex = 0; startIndex < words.size(); startIndex++) {
            AudioWindow best = null;
            int bestDifference = Integer.MAX_VALUE;
            int maximumEnd = Math.min(words.size(), startIndex + MAXIMUM_WORD_SPAN);
            for (int endIndex = startIndex; endIndex < maximumEnd; endIndex++) {
                int startMs = words.get(startIndex).startMs();
                int endMs = words.get(endIndex).endMs();
                int durationMs = endMs - startMs;
                if (durationMs < calibration.minimumDurationMs()
                        || durationMs > calibration.maximumDurationMs()) {
                    continue;
                }
                int difference = Math.abs(durationMs - expectedDurationMs);
                if (difference < bestDifference) {
                    bestDifference = difference;
                    best = new AudioWindow(startMs, endMs);
                }
            }
            if (best != null) {
                windows.add(best);
            }
        }
        addAcousticWindows(audio, expectedDurationMs, calibration, windows);
        double minimum = Double.POSITIVE_INFINITY;
        for (AudioWindow window : windows) {
            float[][] features = features(audio, window, calibration, featureCache);
            if (features != null) {
                minimum = Math.min(minimum, dtw.distance(
                        features, template.first(), calibration.dtwWindowRatio()));
                minimum = Math.min(minimum, dtw.distance(
                        features, template.second(), calibration.dtwWindowRatio()));
            }
        }
        return minimum;
    }

    /**
     * 在最长十五秒的当前任务音频上按模板时长执行有界声学搜索。
     *
     * <p>这些窗口不依赖普通话 ASR 的汉字或词边界；最终仍使用个人称呼模板、
     * 当前签名阈值、Top-1 距离和 Top-1/Top-2 余量失败关闭。
     */
    private void addAcousticWindows(
            PcmAudio audio,
            int expectedDurationMs,
            DialectAcousticCalibration calibration,
            Set<AudioWindow> windows) {
        int durationMs = Math.max(calibration.minimumDurationMs(),
                Math.min(calibration.maximumDurationMs(),
                        quantizeDuration(expectedDurationMs)));
        int audioDurationMs = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                (long) audio.samples().length * 1_000L / calibration.sampleRateHz()));
        int searchEndMs = Math.min(audioDurationMs, MAXIMUM_ACOUSTIC_SEARCH_MS);
        if (searchEndMs < durationMs) {
            return;
        }
        int lastStartMs = searchEndMs - durationMs;
        for (int startMs = 0; startMs <= lastStartMs;
                startMs += ACOUSTIC_WINDOW_STEP_MS) {
            windows.add(new AudioWindow(startMs, startMs + durationMs));
        }
        windows.add(new AudioWindow(lastStartMs, searchEndMs));
    }

    private int quantizeDuration(int durationMs) {
        return Math.max(ACOUSTIC_WINDOW_QUANTUM_MS,
                Math.round((float) durationMs / ACOUSTIC_WINDOW_QUANTUM_MS)
                        * ACOUSTIC_WINDOW_QUANTUM_MS);
    }

    private int expectedDurationMs(
            AliasTemplate template,
            DialectAcousticCalibration calibration) {
        double averageFrames = (template.first().length + template.second().length) / 2.0D;
        return (int) Math.round((averageFrames - 1.0D) * calibration.frameShiftMs()
                + calibration.frameLengthMs());
    }

    private float[][] features(
            PcmAudio audio,
            AudioWindow window,
            DialectAcousticCalibration calibration,
            Map<AudioWindow, float[][]> cache) {
        if (cache.containsKey(window)) {
            return cache.get(window);
        }
        if (cache.size() >= MAXIMUM_WINDOWS) {
            var oldest = cache.entrySet().iterator();
            if (oldest.hasNext()) {
                Map.Entry<AudioWindow, float[][]> entry = oldest.next();
                clear(entry.getValue());
                oldest.remove();
            }
        }
        double[] allSamples = audio.samples();
        int from = Math.toIntExact((long) window.startMs()
                * calibration.sampleRateHz() / 1_000L);
        int to = Math.toIntExact((long) window.endMs()
                * calibration.sampleRateHz() / 1_000L);
        if (from < 0 || to <= from || to > allSamples.length) {
            Arrays.fill(allSamples, 0.0D);
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        double[] slice = Arrays.copyOfRange(allSamples, from, to);
        Arrays.fill(allSamples, 0.0D);
        PcmAudio segment = new PcmAudio(slice, calibration.sampleRateHz(), 0.0D);
        Arrays.fill(slice, 0.0D);
        try {
            float[][] value = featureExtractor.extract(segment, calibration);
            cache.put(window, value);
            return value;
        } catch (BusinessException exception) {
            if (exception.errorCode() != ErrorCode.AUDIO_INVALID) {
                throw exception;
            }
            cache.put(window, null);
            return null;
        } finally {
            segment.clear();
        }
    }

    private List<TaskContactCandidate> classify(
            List<ContactScore> scores,
            DialectAcousticCalibration calibration,
            boolean singleActiveContact) {
        scores.sort(Comparator.comparingDouble(ContactScore::distance)
                .thenComparing(score -> score.template().contactId()));
        if (scores.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        ContactScore best = scores.get(0);
        /*
         * 仅有一名 ACTIVE 亲友时，联系人槽位由绑定关系收口，而不是把口音较重或
         * 漏说称呼的责任交给老人。该推断只进入完整播报和口头确认，绝不直接执行。
         * 所有结果仍必须先进入签名阈值的候选区间。
         */
        if (best.distance() > calibration.taskAliasCandidateMaxDistance()) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        double margin = scores.size() == 1
                ? Double.POSITIVE_INFINITY : scores.get(1).distance() - best.distance();
        boolean unique = singleActiveContact
                || best.distance() <= calibration.taskAliasUniqueMaxDistance()
                && margin >= calibration.taskAliasMinimumMargin();
        if (unique) {
            return List.of(toCandidate(best, "UNIQUE", 1));
        }
        List<TaskContactCandidate> candidates = new ArrayList<>();
        for (ContactScore score : scores) {
            if (score.distance() > calibration.taskAliasCandidateMaxDistance()
                    || candidates.size() == MAXIMUM_RESULTS) {
                break;
            }
            candidates.add(toCandidate(score, "AMBIGUOUS", candidates.size() + 1));
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_CONTACT_MATCH);
        }
        return List.copyOf(candidates);
    }

    private TaskContactCandidate toCandidate(
            ContactScore score,
            String scoreBand,
            int rank) {
        AliasTemplate template = score.template();
        return new TaskContactCandidate(
                UUID.randomUUID().toString(), template.contactId(),
                PublicIdCodec.contactId(template.contactId()),
                StringUtils.hasText(template.displayName()) ? template.displayName() : "",
                template.alias(), scoreBand, rank);
    }

    private static void clear(float[][] features) {
        if (features != null) {
            for (float[] frame : features) {
                Arrays.fill(frame, 0.0F);
            }
        }
    }

    private record AudioWindow(int startMs, int endMs) {
    }

    private record ContactScore(AliasTemplate template, double distance) {
    }

    private record AliasTemplate(
            UUID contactId,
            String displayName,
            String alias,
            float[][] first,
            float[][] second) {

        void clear() {
            MfccDtwTaskContactMatcherAdapter.clear(first);
            MfccDtwTaskContactMatcherAdapter.clear(second);
        }
    }
}
