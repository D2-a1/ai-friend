package com.aifriend.task.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.voice.application.ValidatedAudioObject;

/** 将已校验 WAV 转为 Vosk 接受的 16 kHz 单声道 PCM16LE。 */
final class WavTaskPcmDecoder {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int MAXIMUM_DURATION_MS = 60_000;
    private static final int MAXIMUM_PCM_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE
            * MAXIMUM_DURATION_MS / 1_000;
    private static final int DURATION_TOLERANCE_MS = 250;

    byte[] decode(ValidatedAudioObject audioObject) {
        if (audioObject == null || !"audio/wav".equals(audioObject.mediaType())) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        byte[] sourceBytes = audioObject.audioContent();
        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                SAMPLE_RATE_HZ,
                16,
                1,
                BYTES_PER_SAMPLE,
                SAMPLE_RATE_HZ,
                false);
        try (ByteArrayInputStream source = new ByteArrayInputStream(sourceBytes);
                AudioInputStream original = AudioSystem.getAudioInputStream(source);
                AudioInputStream normalized = AudioSystem.getAudioInputStream(
                        targetFormat, original)) {
            byte[] pcm = normalized.readNBytes(MAXIMUM_PCM_BYTES + 1);
            if (pcm.length == 0 || pcm.length > MAXIMUM_PCM_BYTES
                    || pcm.length % BYTES_PER_SAMPLE != 0) {
                clear(pcm);
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            int durationMs = Math.round(pcm.length * 1_000.0F
                    / (SAMPLE_RATE_HZ * BYTES_PER_SAMPLE));
            if (Math.abs((long) durationMs - audioObject.actualDurationMs())
                    > DURATION_TOLERANCE_MS) {
                clear(pcm);
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            return pcm;
        } catch (UnsupportedAudioFileException | IOException
                | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        } finally {
            clear(sourceBytes);
        }
    }

    private void clear(byte[] bytes) {
        if (bytes != null) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }
}
