package com.aifriend.task.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.task.application.TaskRecognizedWord;
import com.aifriend.task.application.TaskTranscriptCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 将 Vosk 有界 JSON 结果转换为不持久化的词级候选。 */
final class VoskTaskResultParser {

    private static final int MAXIMUM_RESULT_CHARACTERS = 65_536;
    private static final int MAXIMUM_TRANSCRIPT_CHARACTERS = 1_000;
    private static final int MAXIMUM_WORDS = 1_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    List<TaskTranscriptCandidate> parse(
            String json,
            TaskAsrSource source,
            String modelVersion) {
        if (json == null || json.isBlank() || json.length() > MAXIMUM_RESULT_CHARACTERS) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode alternatives = root.path("alternatives");
            List<TaskTranscriptCandidate> candidates = new ArrayList<>(3);
            if (alternatives.isArray()) {
                for (JsonNode alternative : alternatives) {
                    addCandidate(candidates, alternative, source, modelVersion);
                    if (candidates.size() == 3) {
                        break;
                    }
                }
            } else {
                addCandidate(candidates, root, source, modelVersion);
            }
            return candidates;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
    }

    private void addCandidate(
            List<TaskTranscriptCandidate> candidates,
            JsonNode node,
            TaskAsrSource source,
            String modelVersion) {
        String transcript = node.path("text").asText("").strip();
        if (transcript.isEmpty() || transcript.length() > MAXIMUM_TRANSCRIPT_CHARACTERS) {
            return;
        }
        JsonNode result = node.path("result");
        if (!result.isArray() || result.isEmpty() || result.size() > MAXIMUM_WORDS) {
            return;
        }
        List<TaskRecognizedWord> words = new ArrayList<>(result.size());
        double confidenceSum = 0.0D;
        for (JsonNode wordNode : result) {
            String word = wordNode.path("word").asText("").strip();
            double startSeconds = wordNode.path("start").asDouble(Double.NaN);
            double endSeconds = wordNode.path("end").asDouble(Double.NaN);
            double confidence = wordNode.path("conf").asDouble(Double.NaN);
            if (word.isEmpty() || !Double.isFinite(startSeconds)
                    || !Double.isFinite(endSeconds) || !Double.isFinite(confidence)) {
                throw new IllegalArgumentException("ASR_WORD_INVALID");
            }
            int startMs = Math.toIntExact(Math.round(startSeconds * 1_000.0D));
            int endMs = Math.toIntExact(Math.round(endSeconds * 1_000.0D));
            words.add(new TaskRecognizedWord(word, startMs, endMs, confidence));
            confidenceSum += confidence;
        }
        candidates.add(new TaskTranscriptCandidate(
                transcript, words, confidenceSum / words.size(), source, modelVersion));
    }
}
