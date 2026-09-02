package com.aifriend.matcher.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aifriend.matcher.domain.IntentSignal;
import com.aifriend.matcher.domain.IntentSignalType;

/**
 * 安全优先的基础关键词意图匹配器。
 *
 * <p>当前只提供确定性的候选信号，取消与纠正始终排在动作信号之前。
 * 后续语义模型、声学模板和检索结果通过独立端口参与融合，不能覆盖安全优先级。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class KeywordIntentMatcher {

    private static final Map<IntentSignalType, List<String>> KEYWORDS = Map.of(
            IntentSignalType.CANCELLATION, List.of("取消", "不发了", "不要打了"),
            IntentSignalType.CORRECTION, List.of("不对", "说错了", "改成"),
            IntentSignalType.VIDEO_CALL, List.of("打视频", "视频通话"),
            IntentSignalType.VOICE_CALL, List.of("打电话", "打语音", "语音通话"),
            IntentSignalType.SEND_MESSAGE, List.of("发消息", "发信息", "说给"));

    private static final Map<IntentSignalType, Integer> PRIORITIES = Map.of(
            IntentSignalType.CANCELLATION, 0,
            IntentSignalType.CORRECTION, 1,
            IntentSignalType.VIDEO_CALL, 10,
            IntentSignalType.VOICE_CALL, 10,
            IntentSignalType.SEND_MESSAGE, 10);

    /**
     * 创建确定性关键词候选匹配器。
     */
    public KeywordIntentMatcher() {
    }

    /**
     * 从规范化文本中提取关键词意图信号。
     *
     * @param normalizedText ASR 或本地规则产生的规范化文本
     * @return 按安全优先级排序的不可变信号列表；无命中时返回空列表
     */
    public List<IntentSignal> match(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }

        String comparableText = normalizedText.toLowerCase(Locale.ROOT).trim();
        List<IntentSignal> signals = new ArrayList<>();
        KEYWORDS.forEach((signalType, keywords) -> keywords.stream()
                .filter(comparableText::contains)
                .findFirst()
                .ifPresent(keyword -> signals.add(new IntentSignal(
                        signalType,
                        keyword,
                        PRIORITIES.get(signalType)))));
        signals.sort(Comparator.comparingInt(IntentSignal::priority));
        return List.copyOf(signals);
    }
}
