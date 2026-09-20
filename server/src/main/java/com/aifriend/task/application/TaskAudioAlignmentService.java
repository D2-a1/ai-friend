package com.aifriend.task.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 根据主方言词级时间戳计算保守有效原声范围。
 *
 * <p>只移除可完整落在词边界上的开头唤醒词和动作前缀。纠正解析服务必须先
 * 隔离最终连续子句；本服务收到残留纠正词、中间动作词或重叠时间戳时仍失败关闭，
 * 不拼接或合成老人声音。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskAudioAlignmentService {

    private static final int MINIMUM_RANGE_MS = 200;
    private static final List<String> LEADING_PREFIXES = List.of(
            "小友小友请帮我", "小友小友帮我", "小友小友",
            "小友请帮我", "小友帮我", "请帮我", "麻烦帮我",
            "打视频电话给", "打语音电话给", "打电话给",
            "小友", "帮我", "告诉", "叫", "喊", "给", "请");
    private static final List<String> UNCERTAIN_MARKERS = List.of(
            "不对", "说错了", "改成", "纠正", "不是");
    private static final List<String> EMBEDDED_ACTION_MARKERS = List.of(
            "发消息", "发信息", "视频电话", "语音电话", "打电话", "通话");

    /** 创建无状态对齐服务。 */
    public TaskAudioAlignmentService() {
    }

    /**
     * 计算单个连续有效原声范围。
     *
     * @param candidate 主方言首选候选
     * @param actualDurationMs 已解码真实音频时长
     * @return 零或一个连续半开区间
     * @throws BusinessException 当时间戳或语义边界不可靠时抛出
     */
    public List<TaskAudioRange> align(
            TaskTranscriptCandidate candidate,
            int actualDurationMs) {
        if (candidate == null || candidate.words().isEmpty()
                || actualDurationMs <= 0) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        validateWords(candidate.words(), actualDurationMs);
        String normalized = concatenate(candidate.words());
        if (!StringUtils.hasText(normalized)
                || containsAny(normalized, UNCERTAIN_MARKERS)) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        int excludedCharacters = leadingPrefixLength(normalized);
        int startIndex = wordIndexAtBoundary(candidate.words(), excludedCharacters);
        String retained = concatenate(candidate.words().subList(
                startIndex, candidate.words().size()));
        if (!StringUtils.hasText(retained)
                || containsAny(retained, EMBEDDED_ACTION_MARKERS)) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        int startMs = candidate.words().get(startIndex).startMs();
        int endMs = candidate.words().get(candidate.words().size() - 1).endMs();
        if (endMs - startMs < MINIMUM_RANGE_MS) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        return List.of(new TaskAudioRange(startMs, endMs));
    }

    private void validateWords(List<TaskRecognizedWord> words, int actualDurationMs) {
        int previousEndMs = 0;
        for (TaskRecognizedWord word : words) {
            boolean invalid = word == null || !StringUtils.hasText(word.text())
                    || word.startMs() < previousEndMs
                    || word.endMs() <= word.startMs()
                    || word.endMs() > actualDurationMs
                    || !Double.isFinite(word.confidence())
                    || word.confidence() < 0.0D || word.confidence() > 1.0D;
            if (invalid) {
                throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
            }
            previousEndMs = word.endMs();
        }
    }

    private int leadingPrefixLength(String normalized) {
        for (String prefix : LEADING_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return prefix.length();
            }
        }
        return 0;
    }

    private int wordIndexAtBoundary(
            List<TaskRecognizedWord> words,
            int excludedCharacters) {
        if (excludedCharacters == 0) {
            return 0;
        }
        int traversed = 0;
        for (int index = 0; index < words.size(); index++) {
            traversed += normalizeWord(words.get(index).text()).length();
            if (traversed == excludedCharacters) {
                if (index + 1 >= words.size()) {
                    throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
                }
                return index + 1;
            }
            if (traversed > excludedCharacters) {
                throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
            }
        }
        throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
    }

    private String concatenate(List<TaskRecognizedWord> words) {
        StringBuilder builder = new StringBuilder();
        words.forEach(word -> builder.append(normalizeWord(word.text())));
        return builder.toString();
    }

    private String normalizeWord(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }
}
