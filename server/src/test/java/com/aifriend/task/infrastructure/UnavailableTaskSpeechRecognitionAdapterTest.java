package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class UnavailableTaskSpeechRecognitionAdapterTest {

    @Test
    void mustReturnAsrUnavailableWithoutFabricatingTranscript() {
        UnavailableTaskSpeechRecognitionAdapter adapter =
                new UnavailableTaskSpeechRecognitionAdapter();
        ValidatedAudioObject audio = new ValidatedAudioObject(
                UUID.randomUUID(), UUID.randomUUID(), AudioPurpose.TASK,
                "audio/wav", new byte[] {1, 2, 3}, 1000, "v1", 0);
        TaskClientContext context = new TaskClientContext(
                "1", "1", "rule-v1", "zh-Hans-CN-x-wugang",
                "dialect-v1", "mandarin-v1", "fusion-v1", "mfcc-v1", "t-v1");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> adapter.recognize(audio, context));

        assertEquals(ErrorCode.ASR_UNAVAILABLE, exception.errorCode());
    }
}
