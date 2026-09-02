package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.BasicExperienceProperties;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrEngineDescriptor;
import com.aifriend.task.application.TaskAsrEngineResult;
import com.aifriend.task.application.TaskAsrFusionService;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskClientRecognitionEvidence;
import com.aifriend.task.application.TaskMandarinAssistAsrEnginePort;
import com.aifriend.task.application.TaskPrimaryAsrEnginePort;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskSpeechRecognition;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class LocalTaskSpeechRecognitionAdapterTest {

    private static final String PRIMARY_SHA = "0".repeat(64);
    private static final String ASSIST_SHA = "1".repeat(64);

    @Test
    void shouldUseBothSignedModelsAndKeepPrimaryAsFinalText() {
        TaskPrimaryAsrEnginePort primaryPort = mock(TaskPrimaryAsrEnginePort.class);
        TaskMandarinAssistAsrEnginePort assistPort = mock(
                TaskMandarinAssistAsrEnginePort.class);
        when(primaryPort.descriptor()).thenReturn(new TaskAsrEngineDescriptor(
                true, "primary-v1", PRIMARY_SHA));
        when(assistPort.descriptor()).thenReturn(new TaskAsrEngineDescriptor(
                true, "assist-v1", ASSIST_SHA));
        when(primaryPort.recognize(any()))
                .thenReturn(engineResult(
                        TaskAsrSource.PRIMARY, "primary-v1", 0.90D));
        when(assistPort.recognize(any()))
                .thenReturn(engineResult(
                        TaskAsrSource.MANDARIN_ASSIST, "assist-v1", 0.80D));
        LocalTaskSpeechRecognitionAdapter adapter = adapter(primaryPort, assistPort);

        TaskSpeechRecognition recognition = adapter.recognize(
                audio(), context());

        assertEquals("叫 二狗子 回来", recognition.transcript());
        assertEquals(2, recognition.nBest().size());
        assertEquals(List.of(), recognition.effectiveAudioRanges());
        assertEquals("primary-v1", recognition.primaryAsrModelVersion());
        assertEquals("assist-v1", recognition.mandarinAssistModelVersion());
    }

    @Test
    void shouldFailBeforeReadingAudioWhenSignedModelHashDoesNotMatch() {
        TaskPrimaryAsrEnginePort primaryPort = mock(TaskPrimaryAsrEnginePort.class);
        TaskMandarinAssistAsrEnginePort assistPort = mock(
                TaskMandarinAssistAsrEnginePort.class);
        when(primaryPort.descriptor()).thenReturn(new TaskAsrEngineDescriptor(
                true, "primary-v1", "9".repeat(64)));
        when(assistPort.descriptor()).thenReturn(new TaskAsrEngineDescriptor(
                true, "assist-v1", ASSIST_SHA));
        LocalTaskSpeechRecognitionAdapter adapter = adapter(primaryPort, assistPort);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.recognize(audio(), context()));

        assertEquals(ErrorCode.ASR_UNAVAILABLE, exception.errorCode());
        verify(primaryPort, never()).recognize(any());
        verify(assistPort, never()).recognize(any());
    }

    @Test
    void shouldAcceptBoundedAndroidEvidenceOnlyInBasicExperience() {
        TaskPrimaryAsrEnginePort primaryPort = mock(TaskPrimaryAsrEnginePort.class);
        TaskMandarinAssistAsrEnginePort assistPort = mock(
                TaskMandarinAssistAsrEnginePort.class);
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(basicManifest(), calibration())));
        LocalTaskSpeechRecognitionAdapter adapter =
                new LocalTaskSpeechRecognitionAdapter(
                        registry, primaryPort, assistPort,
                        new TaskAsrFusionService(), properties(),
                        new BasicExperienceProperties(true));

        TaskSpeechRecognition recognition = adapter.recognize(
                audio(), basicContext());

        assertEquals("给 女儿 打 电话", recognition.transcript());
        assertEquals("vosk-model-small-cn-0.22",
                recognition.primaryAsrModelVersion());
        assertEquals(TaskAsrSource.PRIMARY,
                recognition.nBest().get(0).source());
        verify(primaryPort, never()).descriptor();
        verify(assistPort, never()).descriptor();
    }

    @Test
    void shouldRejectAndroidEvidenceWhenBasicExperienceIsDisabled() {
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(basicManifest(), calibration())));
        LocalTaskSpeechRecognitionAdapter adapter =
                new LocalTaskSpeechRecognitionAdapter(
                        registry, mock(TaskPrimaryAsrEnginePort.class),
                        mock(TaskMandarinAssistAsrEnginePort.class),
                        new TaskAsrFusionService(), properties(),
                        new BasicExperienceProperties(false));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.recognize(audio(), basicContext()));

        assertEquals(ErrorCode.ASR_UNAVAILABLE, exception.errorCode());
    }

    @Test
    void shouldRejectBasicEvidenceWithOverlappingWordTimeline() {
        List<TaskRecognizedWord> words = List.of(
                new TaskRecognizedWord("给", 100, 300, 0.8D),
                new TaskRecognizedWord("女儿", 200, 400, 0.8D),
                new TaskRecognizedWord("打", 410, 500, 0.8D),
                new TaskRecognizedWord("电话", 510, 800, 0.8D));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> basicAdapter().recognize(audio(), basicContext(
                        "给 女儿 打 电话", words)));

        assertEquals(ErrorCode.ASR_UNAVAILABLE, exception.errorCode());
    }

    @Test
    void shouldRejectBasicEvidenceWhenTranscriptAndWordsDiffer() {
        List<TaskRecognizedWord> words = List.of(
                new TaskRecognizedWord("今天天气", 100, 500, 0.8D));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> basicAdapter().recognize(audio(), basicContext(
                        "给 女儿 打 电话", words)));

        assertEquals(ErrorCode.ASR_UNAVAILABLE, exception.errorCode());
    }

    private LocalTaskSpeechRecognitionAdapter adapter(
            TaskPrimaryAsrEnginePort primaryPort,
            TaskMandarinAssistAsrEnginePort assistPort) {
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(manifest(), calibration())));
        return new LocalTaskSpeechRecognitionAdapter(
                registry,
                primaryPort,
                assistPort,
                new TaskAsrFusionService(),
                properties());
    }

    private TaskAsrEngineResult engineResult(
            TaskAsrSource source,
            String modelVersion,
            double confidence) {
        TaskTranscriptCandidate candidate = new TaskTranscriptCandidate(
                "叫 二狗子 回来",
                List.of(
                        new TaskRecognizedWord("叫", 100, 200, confidence),
                        new TaskRecognizedWord("二狗子", 200, 600, confidence),
                        new TaskRecognizedWord("回来", 620, 900, confidence)),
                confidence,
                source,
                modelVersion);
        return new TaskAsrEngineResult(List.of(candidate), modelVersion);
    }

    private TaskAsrProperties properties() {
        return new TaskAsrProperties(
                "align-v1",
                "fusion-v1",
                new TaskAsrProperties.Engine(
                        true, "primary-v1", "primary.zip", PRIMARY_SHA,
                        "primary/", null, 3),
                new TaskAsrProperties.Engine(
                        true, "assist-v1", "assist.zip", ASSIST_SHA,
                        "assist/", null, 3));
    }

    private DialectPackageManifest manifest() {
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang",
                "dialect-v1",
                "MFCC_DTW_V1",
                "mfcc-v1",
                "threshold-v1",
                "primary-v1",
                PRIMARY_SHA,
                "assist-v1",
                ASSIST_SHA,
                "fusion-v1",
                "align-v1",
                "1.0.0",
                "1.0.0",
                "calibration.json",
                "2".repeat(64),
                "key-v1",
                "2026-08-20T12:00:00Z");
    }

    private DialectPackageManifest basicManifest() {
        String modelSha =
                "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
        return new DialectPackageManifest(
                "zh-Hans-CN-x-wugang", "basic-experience-v1",
                "MFCC_DTW_V1", "mfcc-dtw-basic-v1", "basic-personal-v1",
                "vosk-model-small-cn-0.22", modelSha,
                "vosk-model-small-cn-0.22", modelSha,
                "basic-local-direct-v1", "vosk-word-timestamp-v1",
                "1.0.0", "0.0.1", "built-in-basic-calibration",
                "0".repeat(64), "basic-experience", "2026-08-28T00:00:00Z");
    }

    private DialectAcousticCalibration calibration() {
        return new DialectAcousticCalibration(
                "MFCC_DTW_V1", 16_000, 25, 10, 26, 13,
                300, 5_000, -60.0D, 35.0D, 0.20D, 0.01D,
                0.20D, 1.0D, 0.2D, 1.5D,
                0.5D, 1.5D, 0.2D);
    }

    private TaskClientContext context() {
        return new TaskClientContext(
                "1.0.0", "wechat-v1", "rule-v1",
                "zh-Hans-CN-x-wugang", "dialect-v1", "assist-v1",
                "fusion-v1", "mfcc-v1", "threshold-v1");
    }

    private TaskClientContext basicContext() {
        return basicContext(
                "给 女儿 打 电话",
                List.of(
                        new TaskRecognizedWord("给", 100, 200, 0.8D),
                        new TaskRecognizedWord("女儿", 210, 400, 0.8D),
                        new TaskRecognizedWord("打", 410, 500, 0.8D),
                        new TaskRecognizedWord("电话", 510, 800, 0.8D)));
    }

    private LocalTaskSpeechRecognitionAdapter basicAdapter() {
        DialectPackageRegistry registry = mock(DialectPackageRegistry.class);
        when(registry.findActive()).thenReturn(Optional.of(
                new VerifiedDialectPackage(basicManifest(), calibration())));
        return new LocalTaskSpeechRecognitionAdapter(
                registry, mock(TaskPrimaryAsrEnginePort.class),
                mock(TaskMandarinAssistAsrEnginePort.class),
                new TaskAsrFusionService(), properties(),
                new BasicExperienceProperties(true));
    }

    private TaskClientContext basicContext(
            String transcript,
            List<TaskRecognizedWord> words) {
        String modelSha =
                "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
        return new TaskClientContext(
                "0.0.1-debug", "UNVERIFIED", "RESERVED_DISABLED",
                "zh-Hans-CN-x-wugang", "basic-experience-v1",
                "vosk-model-small-cn-0.22", "basic-local-direct-v1",
                "mfcc-dtw-basic-v1", "basic-personal-v1",
                new TaskClientRecognitionEvidence(
                        transcript, 0.8D,
                        "vosk-model-small-cn-0.22", modelSha, words));
    }

    private ValidatedAudioObject audio() {
        return new ValidatedAudioObject(
                UUID.randomUUID(), UUID.randomUUID(), AudioPurpose.TASK,
                "audio/wav", new byte[] {1, 2, 3}, 1_000, "v1", 0);
    }

}
