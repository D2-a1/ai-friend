package com.aifriend.voicecollection.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 语音采集留存硬上限单元测试。 */
class VoiceCollectionPropertiesTest {

    @Test
    void shouldAllowThirtyDaysButRejectLongerRetention() {
        assertDoesNotThrow(() -> new VoiceCollectionProperties(
                "test-voice-collection-v1", "voice-model-training-v1",
                "voice-sample-review-v1",
                Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> new VoiceCollectionProperties(
                "test-voice-collection-v1", "voice-model-training-v1",
                "voice-sample-review-v1",
                Duration.ofDays(30).plusMillis(1)));
    }
}
