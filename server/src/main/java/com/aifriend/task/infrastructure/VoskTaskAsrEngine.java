package com.aifriend.task.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.util.StringUtils;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrEngineDescriptor;
import com.aifriend.task.application.TaskAsrEngineResult;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.aifriend.voice.application.ValidatedAudioObject;

/** 封装单个本地 Vosk 模型的延迟加载、识别和内存清理。 */
final class VoskTaskAsrEngine implements AutoCloseable {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int PCM_CHUNK_BYTES = 4_096;
    private static final int MAXIMUM_COMBINED_CANDIDATES = 3;

    private final TaskAsrProperties.Engine properties;
    private final TaskAsrSource source;
    private final WavTaskPcmDecoder pcmDecoder;
    private final VoskTaskResultParser resultParser;
    private VoskTaskModelArchive modelArchive;
    private Model model;

    VoskTaskAsrEngine(
            TaskAsrProperties.Engine properties,
            TaskAsrSource source) {
        this.properties = properties;
        this.source = source;
        this.pcmDecoder = new WavTaskPcmDecoder();
        this.resultParser = new VoskTaskResultParser();
    }

    TaskAsrEngineDescriptor descriptor() {
        if (properties == null) {
            return new TaskAsrEngineDescriptor(false, "", "");
        }
        return new TaskAsrEngineDescriptor(
                properties.enabled(),
                nullToEmpty(properties.modelVersion()),
                nullToEmpty(properties.archiveSha256()));
    }

    TaskAsrEngineResult recognize(ValidatedAudioObject audioObject) {
        validateEnabledConfiguration();
        byte[] pcm = pcmDecoder.decode(audioObject);
        try (Recognizer recognizer = new Recognizer(requireModel(), SAMPLE_RATE_HZ)) {
            recognizer.setWords(true);
            recognizer.setMaxAlternatives(properties.maximumAlternatives());
            List<List<TaskTranscriptCandidate>> segments = new ArrayList<>();
            int offset = 0;
            while (offset < pcm.length) {
                int length = Math.min(PCM_CHUNK_BYTES, pcm.length - offset);
                byte[] chunk = java.util.Arrays.copyOfRange(pcm, offset, offset + length);
                try {
                    if (recognizer.acceptWaveForm(chunk, length)) {
                        List<TaskTranscriptCandidate> parsed = resultParser.parse(
                                recognizer.getResult(), source, properties.modelVersion());
                        if (!parsed.isEmpty()) {
                            segments.add(parsed);
                        }
                    }
                } finally {
                    java.util.Arrays.fill(chunk, (byte) 0);
                }
                offset += length;
            }
            List<TaskTranscriptCandidate> finalSegment = resultParser.parse(
                    recognizer.getFinalResult(), source, properties.modelVersion());
            if (!finalSegment.isEmpty()) {
                segments.add(finalSegment);
            }
            List<TaskTranscriptCandidate> combined = combineSegments(segments);
            if (combined.isEmpty()) {
                throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
            }
            return new TaskAsrEngineResult(combined, properties.modelVersion());
        } catch (IOException | IllegalStateException exception) {
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        } finally {
            java.util.Arrays.fill(pcm, (byte) 0);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (model != null) {
            model.close();
            model = null;
        }
        if (modelArchive != null) {
            modelArchive.close();
            modelArchive = null;
        }
    }

    private synchronized Model requireModel() throws IOException {
        if (model != null) {
            return model;
        }
        try {
            modelArchive = VoskTaskModelArchive.install(properties);
            model = new Model(modelArchive.modelRoot().toString());
            return model;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError exception) {
            closeQuietly();
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        }
    }

    private void validateEnabledConfiguration() {
        boolean invalid = properties == null || !properties.enabled()
                || !StringUtils.hasText(properties.modelVersion())
                || properties.modelVersion().length() > 60;
        if (invalid) {
            throw new BusinessException(ErrorCode.ASR_UNAVAILABLE);
        }
    }

    private List<TaskTranscriptCandidate> combineSegments(
            List<List<TaskTranscriptCandidate>> segments) {
        if (segments.isEmpty()) {
            return List.of();
        }
        int alternativeCount = segments.stream()
                .mapToInt(List::size)
                .min()
                .orElse(0);
        alternativeCount = Math.min(alternativeCount, MAXIMUM_COMBINED_CANDIDATES);
        List<TaskTranscriptCandidate> combined = new ArrayList<>(alternativeCount);
        for (int rank = 0; rank < alternativeCount; rank++) {
            List<TaskTranscriptCandidate> rankedSegments = new ArrayList<>(segments.size());
            for (List<TaskTranscriptCandidate> segment : segments) {
                rankedSegments.add(segment.get(rank));
            }
            combined.add(combineRank(rankedSegments));
        }
        return combined;
    }

    private TaskTranscriptCandidate combineRank(
            List<TaskTranscriptCandidate> segments) {
        StringBuilder transcript = new StringBuilder();
        List<TaskRecognizedWord> words = new ArrayList<>();
        double weightedConfidence = 0.0D;
        int totalWords = 0;
        for (TaskTranscriptCandidate segment : segments) {
            if (!transcript.isEmpty()) {
                transcript.append(' ');
            }
            transcript.append(segment.transcript());
            words.addAll(segment.words());
            weightedConfidence += segment.confidence() * segment.words().size();
            totalWords += segment.words().size();
        }
        if (transcript.length() > 1_000 || totalWords == 0) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        return new TaskTranscriptCandidate(
                transcript.toString(), words, weightedConfidence / totalWords,
                source, properties.modelVersion());
    }

    private void closeQuietly() {
        try {
            close();
        } catch (IOException ignored) {
            // 临时目录清理失败不改变对外错误码，也不记录模型路径。
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
