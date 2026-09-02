package com.aifriend.voice.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.application.AudioObjectInspection;

class JavaSoundAudioObjectContentInspectorAdapterTest {

    private final JavaSoundAudioObjectContentInspectorAdapter adapter =
            new JavaSoundAudioObjectContentInspectorAdapter();

    @Test
    void shouldDecodeWavAndCalculateRealDuration() throws IOException {
        byte[] wav = wavAudio(8_000, 8_000);

        AudioObjectInspection inspection = adapter.inspect("audio/wav", wav);

        assertEquals(1_000, inspection.durationMs());
    }

    @Test
    void shouldFailClosedForUnsupportedCodecAdapter() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.inspect("audio/mp4", new byte[] {0, 0, 0, 8}));

        assertEquals(ErrorCode.AUDIO_INVALID, exception.errorCode());
    }

    private byte[] wavAudio(int sampleRate, int frameCount) throws IOException {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        byte[] pcm = new byte[frameCount * format.getFrameSize()];
        try (ByteArrayInputStream source = new ByteArrayInputStream(pcm);
                AudioInputStream input = new AudioInputStream(source, format, frameCount);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        }
    }
}
