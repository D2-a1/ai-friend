package com.aifriend.matcher.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.aifriend.matcher.domain.IntentSignalType;

/**
 * 关键词意图匹配器测试。
 *
 * @author Codex
 * @since 1.0.0
 */
class KeywordIntentMatcherTest {

    private final KeywordIntentMatcher matcher = new KeywordIntentMatcher();

    @Test
    void cancellationMustHavePriorityOverCallSignal() {
        var signals = matcher.match("不要打了，取消打电话");

        assertThat(signals).isNotEmpty();
        assertThat(signals.get(0).type()).isEqualTo(IntentSignalType.CANCELLATION);
        assertThat(signals).extracting(signal -> signal.type())
                .contains(IntentSignalType.VOICE_CALL);
    }

    @Test
    void correctionMustBeRecognizedWithoutExecutingAnAction() {
        var signals = matcher.match("不对，改成视频通话");

        assertThat(signals.get(0).type()).isEqualTo(IntentSignalType.CORRECTION);
        assertThat(signals).extracting(signal -> signal.type())
                .contains(IntentSignalType.VIDEO_CALL);
    }

    @Test
    void blankInputMustReturnNoSignal() {
        assertThat(matcher.match(" ")).isEmpty();
    }
}
