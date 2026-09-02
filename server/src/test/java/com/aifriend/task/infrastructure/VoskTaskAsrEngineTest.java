package com.aifriend.task.infrastructure;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.TaskAsrSource;
import com.aifriend.voice.application.ValidatedAudioObject;
import com.aifriend.voice.domain.AudioPurpose;

class VoskTaskAsrEngineTest {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final String MODEL_SHA256 =
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";

    @Test
    void shouldLoadNativeModelAndRejectSilenceWithoutFabricatingText()
            throws Exception {
        Path runtimeRoot = createNativeSafeRuntimeRoot();
        TaskAsrProperties.Engine properties = properties(runtimeRoot);

        try {
            try (VoskTaskAsrEngine engine = new VoskTaskAsrEngine(
                    properties, TaskAsrSource.MANDARIN_ASSIST)) {
                BusinessException exception = assertThrows(BusinessException.class,
                        () -> engine.recognize(silence()));

                assertEquals(
                        ErrorCode.AUDIO_SEGMENT_UNCERTAIN,
                        exception.errorCode());
            }
        } finally {
            Files.deleteIfExists(runtimeRoot);
        }
    }

    private TaskAsrProperties.Engine properties(Path runtimeRoot) {
        Path archive = Path.of("..", "android", "app", "src", "main", "assets",
                "voice", "vosk", "vosk-model-small-cn-0.22.zip");
        return new TaskAsrProperties.Engine(
                true,
                "vosk-model-small-cn-0.22",
                archive.toString(),
                MODEL_SHA256,
                "vosk-model-small-cn-0.22/",
                runtimeRoot.toString(),
                3);
    }

    private Path createNativeSafeRuntimeRoot() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            String systemDrive = System.getenv("SystemDrive");
            if (systemDrive == null || !systemDrive.matches("[A-Za-z]:")) {
                throw new IllegalStateException(
                        "VOSK_TEST_ASCII_RUNTIME_ROOT_UNAVAILABLE");
            }
            Path publicDirectory = Path.of(
                    systemDrive + "\\", "Users", "Public");
            if (!Files.isDirectory(publicDirectory)) {
                throw new IllegalStateException(
                        "VOSK_TEST_ASCII_RUNTIME_ROOT_UNAVAILABLE");
            }
            return Files.createTempDirectory(
                    publicDirectory, "ai-friend-vosk-test-");
        }
        return Files.createTempDirectory("ai-friend-vosk-test-");
    }

    private ValidatedAudioObject silence() {
        int durationMs = 1_000;
        byte[] pcm = new byte[SAMPLE_RATE_HZ * Short.BYTES];
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length).order(LITTLE_ENDIAN);
        wav.put(new byte[] {'R', 'I', 'F', 'F'});
        wav.putInt(36 + pcm.length);
        wav.put(new byte[] {'W', 'A', 'V', 'E'});
        wav.put(new byte[] {'f', 'm', 't', ' '});
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) 1);
        wav.putInt(SAMPLE_RATE_HZ);
        wav.putInt(SAMPLE_RATE_HZ * Short.BYTES);
        wav.putShort((short) Short.BYTES);
        wav.putShort((short) 16);
        wav.put(new byte[] {'d', 'a', 't', 'a'});
        wav.putInt(pcm.length);
        wav.put(pcm);
        return new ValidatedAudioObject(
                UUID.randomUUID(), UUID.randomUUID(), AudioPurpose.TASK,
                "audio/wav", wav.array(), durationMs, "v1", 0);
    }
}
