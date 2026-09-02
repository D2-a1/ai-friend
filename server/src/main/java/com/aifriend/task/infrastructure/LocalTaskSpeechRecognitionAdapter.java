package com.aifriend.task.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aifriend.dialect.application.BasicExperienceProperties;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrEngineDescriptor;
import com.aifriend.task.application.TaskAsrEngineResult;
import com.aifriend.task.application.TaskAsrFusionResult;
import com.aifriend.task.application.TaskAsrFusionService;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskClientRecognitionEvidence;
import com.aifriend.task.application.TaskMandarinAssistAsrEnginePort;
import com.aifriend.task.application.TaskPrimaryAsrEnginePort;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskSpeechRecognitionPort;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 主方言、普通话辅助和保守融合的本地任务识别适配器。
 *
 * <p>两路模型版本与归档摘要必须同时命中当前已验签方言包和客户端任务快照。
 * 任一路缺失均失败关闭；普通话辅助不会成为最终首选，也不能越过后续声学联系人匹配。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class LocalTaskSpeechRecognitionAdapter implements TaskSpeechRecognitionPort {

    private final DialectPackageRegistry dialectPackageRegistry;
    private final TaskPrimaryAsrEnginePort primaryAsrEnginePort;
    private final TaskMandarinAssistAsrEnginePort mandarinAssistAsrEnginePort;
    private final TaskAsrFusionService fusionService;
    private final TaskAsrProperties properties;
    private final BasicExperienceProperties basicExperienceProperties;

    /**
     * 创建本地双路识别适配器。
     *
     * @param dialectPackageRegistry 已验签方言包注册表
     * @param primaryAsrEnginePort 主方言识别端口
     * @param mandarinAssistAsrEnginePort 普通话辅助识别端口
     * @param fusionService 保守融合服务
     * @param properties 本地任务 ASR 配置
     */
    public LocalTaskSpeechRecognitionAdapter(
            DialectPackageRegistry dialectPackageRegistry,
            TaskPrimaryAsrEnginePort primaryAsrEnginePort,
            TaskMandarinAssistAsrEnginePort mandarinAssistAsrEnginePort,
            TaskAsrFusionService fusionService,
            TaskAsrProperties properties) {
        this(dialectPackageRegistry, primaryAsrEnginePort,
                mandarinAssistAsrEnginePort, fusionService, properties,
                new BasicExperienceProperties(false));
    }

    /**
     * 创建支持显式基础体验门禁的本地识别适配器。
     *
     * @param dialectPackageRegistry 方言或基础体验声学注册表
     * @param primaryAsrEnginePort 主方言识别端口
     * @param mandarinAssistAsrEnginePort 普通话辅助识别端口
     * @param fusionService 保守融合服务
     * @param properties 本地任务 ASR 配置
     * @param basicExperienceProperties 个人基础体验开关
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LocalTaskSpeechRecognitionAdapter(
            DialectPackageRegistry dialectPackageRegistry,
            TaskPrimaryAsrEnginePort primaryAsrEnginePort,
            TaskMandarinAssistAsrEnginePort mandarinAssistAsrEnginePort,
            TaskAsrFusionService fusionService,
            TaskAsrProperties properties,
            BasicExperienceProperties basicExperienceProperties) {
        this.dialectPackageRegistry = dialectPackageRegistry;
        this.primaryAsrEnginePort = primaryAsrEnginePort;
        this.mandarinAssistAsrEnginePort = mandarinAssistAsrEnginePort;
        this.fusionService = fusionService;
        this.properties = properties;
        this.basicExperienceProperties = basicExperienceProperties;
    }

    /** {@inheritDoc} */
    @Override
    public TaskSpeechRecognition recognize(
            ValidatedAudioObject audioObject,
            TaskClientContext context) {
        if (context != null && context.basicRecognition() != null) {
            return recognizeBasic(audioObject, context);
        }
        VerifiedDialectPackage dialectPackage = dialectPackageRegistry.findActive()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
        DialectPackageManifest manifest = dialectPackage.manifest();
        TaskAsrEngineDescriptor primaryDescriptor = primaryAsrEnginePort.descriptor();
        TaskAsrEngineDescriptor assistDescriptor = mandarinAssistAsrEnginePort.descriptor();
        validateCompatibility(context, manifest, primaryDescriptor, assistDescriptor);

        TaskAsrEngineResult primary = primaryAsrEnginePort.recognize(audioObject);
        TaskAsrEngineResult assist = mandarinAssistAsrEnginePort.recognize(audioObject);
        validateRuntimeResult(primary, primaryDescriptor);
        validateRuntimeResult(assist, assistDescriptor);
        TaskAsrFusionResult fused = fusionService.fuse(primary, assist);
        return new TaskSpeechRecognition(
                fused.selectedCandidate().transcript(),
                fused.evidenceCandidates(),
                List.of(),
                fused.confidence(),
                primary.modelVersion(),
                assist.modelVersion(),
                properties.fusionRuleVersion(),
                properties.alignmentVersion());
    }

    private TaskSpeechRecognition recognizeBasic(
            ValidatedAudioObject audioObject,
            TaskClientContext context) {
        TaskClientRecognitionEvidence evidence = context.basicRecognition();
        DialectPackageManifest manifest = dialectPackageRegistry.findActive()
                .map(VerifiedDialectPackage::manifest)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASR_UNAVAILABLE));
        boolean invalid = !basicExperienceProperties.enabled()
                || evidence == null || evidence.words().isEmpty()
                || evidence.words().size() > 200
                || evidence.transcript() == null || evidence.transcript().isBlank()
                || evidence.transcript().length() > 1000
                || !Double.isFinite(evidence.confidence())
                || evidence.confidence() < 0.0D || evidence.confidence() > 1.0D
                || !manifest.dialectCode().equals(context.dialectCode())
                || !manifest.packageVersion().equals(context.dialectPackageVersion())
                || !manifest.acousticModelVersion().equals(context.templateModelVersion())
                || !manifest.thresholdVersion().equals(context.thresholdVersion())
                || !manifest.mandarinAssistVersion().equals(evidence.modelVersion())
                || !manifest.mandarinAssistSha256().equals(evidence.modelArchiveSha256())
                || !manifest.mandarinAssistVersion().equals(
                        context.mandarinAssistVersion())
                || !manifest.fusionRuleVersion().equals(context.fusionRuleVersion())
                || invalidBasicWords(
                        evidence.words(), evidence.transcript(),
                        audioObject.actualDurationMs());
        if (invalid) {
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        }
        TaskTranscriptCandidate candidate = new TaskTranscriptCandidate(
                evidence.transcript(), evidence.words(), evidence.confidence(),
                TaskAsrSource.PRIMARY, evidence.modelVersion());
        return new TaskSpeechRecognition(
                evidence.transcript(), List.of(candidate), List.of(),
                evidence.confidence(), evidence.modelVersion(),
                evidence.modelVersion(), manifest.fusionRuleVersion(),
                manifest.alignmentVersion());
    }

    private boolean invalidBasicWords(
            List<TaskRecognizedWord> words,
            String transcript,
            int actualDurationMs) {
        int previousEndMs = 0;
        StringBuilder joinedWords = new StringBuilder();
        for (TaskRecognizedWord word : words) {
            boolean invalid = word == null
                    || word.text() == null || word.text().isBlank()
                    || word.text().length() > 40
                    || word.startMs() < previousEndMs
                    || word.endMs() <= word.startMs()
                    || word.endMs() > actualDurationMs
                    || !Double.isFinite(word.confidence())
                    || word.confidence() < 0.0D || word.confidence() > 1.0D;
            if (invalid) {
                return true;
            }
            previousEndMs = word.endMs();
            joinedWords.append(removeWhitespace(word.text()));
        }
        return !removeWhitespace(transcript).contentEquals(joinedWords);
    }

    private String removeWhitespace(String value) {
        return value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .collect(StringBuilder::new,
                        StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString();
    }

    private void validateCompatibility(
            TaskClientContext context,
            DialectPackageManifest manifest,
            TaskAsrEngineDescriptor primary,
            TaskAsrEngineDescriptor assist) {
        boolean invalid = context == null || properties == null
                || !primary.enabled() || !assist.enabled()
                || !manifest.dialectCode().equals(context.dialectCode())
                || !manifest.packageVersion().equals(context.dialectPackageVersion())
                || !manifest.primaryAsrModelVersion().equals(primary.modelVersion())
                || !manifest.primaryAsrModelSha256().equals(primary.archiveSha256())
                || !manifest.mandarinAssistVersion().equals(assist.modelVersion())
                || !manifest.mandarinAssistSha256().equals(assist.archiveSha256())
                || !manifest.mandarinAssistVersion().equals(
                        context.mandarinAssistVersion())
                || !manifest.fusionRuleVersion().equals(properties.fusionRuleVersion())
                || !manifest.fusionRuleVersion().equals(context.fusionRuleVersion())
                || !manifest.alignmentVersion().equals(properties.alignmentVersion());
        if (invalid) {
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        }
    }

    private void validateRuntimeResult(
            TaskAsrEngineResult result,
            TaskAsrEngineDescriptor descriptor) {
        if (result == null || result.candidates().isEmpty()
                || !descriptor.modelVersion().equals(result.modelVersion())) {
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        }
    }
}
