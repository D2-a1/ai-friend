package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.vosk.Model;
import org.vosk.Recognizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aifriend.task.application.TaskAsrProperties;

/**
 * 显式运行的中文合成语音回放。默认 Maven 门禁不会执行 *IT；调用方必须提供
 * 仓库外或 target 下的短期 WAV 目录。
 */
class VoskGeneratedSpeechReplayIT {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final String MODEL_SHA256 =
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";
    private static final String CONFIRMATION_GRAMMAR = """
            ["确 认","确认","否 认","否认","拒 绝","拒绝","取 消","取消",
             "不 确 认","不确认","[unk]"]
            """;

    @Test
    void generatedMandarinMustRecognizeTaskCorrectionAndDecisions()
            throws Exception {
        String configuredRoot = System.getProperty("aifriend.voiceReplayRoot", "");
        Assumptions.assumeTrue(!configuredRoot.isBlank());
        Path replayRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(replayRoot));
        List<Path> confirmations;
        try (var paths = Files.list(replayRoot)) {
            confirmations = paths
                    .filter(path -> path.getFileName().toString()
                            .endsWith("-confirm.wav"))
                    .sorted()
                    .toList();
        }
        assertTrue(confirmations.size() >= 2,
                "至少需要两种中文声线的确认回放");

        Path runtimeRoot = createNativeSafeRuntimeRoot();
        TaskAsrProperties.Engine properties = properties(runtimeRoot);
        try (VoskTaskModelArchive archive = VoskTaskModelArchive.install(properties);
                Model model = new Model(archive.modelRoot().toString())) {
            for (Path confirmation : confirmations) {
                String prefix = confirmation.getFileName().toString()
                        .replace("-confirm.wav", "");
                assertDecision(model, confirmation, "确认");
                assertDecision(model, replayRoot.resolve(prefix + "-reject.wav"), "否认");
                assertTokens(model, replayRoot.resolve(prefix + "-task.wav"),
                        List.of("老大", "电话"));
                assertTokens(model, replayRoot.resolve(prefix + "-correction.wav"),
                        List.of("视频", "通话"));
            }
        } finally {
            Files.deleteIfExists(runtimeRoot);
        }
    }

    private void assertDecision(Model model, Path wav, String expected)
            throws Exception {
        String transcript = recognize(model, wav, CONFIRMATION_GRAMMAR);
        assertTrue(transcript.equals(expected),
                () -> wav.getFileName() + " 实际识别为 " + transcript);
    }

    private void assertTokens(Model model, Path wav, List<String> expectedTokens)
            throws Exception {
        String transcript = recognize(model, wav, null);
        expectedTokens.forEach(token -> assertTrue(
                transcript.contains(token),
                () -> wav.getFileName() + " 缺少 " + token + "，实际为 " + transcript));
    }

    private String recognize(Model model, Path wav, String grammar)
            throws Exception {
        byte[] pcm = decodePcm(wav);
        List<String> results = new ArrayList<>();
        try (Recognizer recognizer = grammar == null
                ? new Recognizer(model, SAMPLE_RATE_HZ)
                : new Recognizer(model, SAMPLE_RATE_HZ, grammar)) {
            int offset = 0;
            while (offset < pcm.length) {
                int length = Math.min(4_096, pcm.length - offset);
                byte[] chunk = java.util.Arrays.copyOfRange(
                        pcm, offset, offset + length);
                try {
                    if (recognizer.acceptWaveForm(chunk, length)) {
                        results.add(text(recognizer.getResult()));
                    }
                } finally {
                    java.util.Arrays.fill(chunk, (byte) 0);
                }
                offset += length;
            }
            results.add(text(recognizer.getFinalResult()));
        } finally {
            java.util.Arrays.fill(pcm, (byte) 0);
        }
        return String.join("", results)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String text(String resultJson) throws Exception {
        JsonNode root = new ObjectMapper().readTree(resultJson);
        return root.path("text").asText("");
    }

    private byte[] decodePcm(Path wav) throws Exception {
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                SAMPLE_RATE_HZ,
                16,
                1,
                Short.BYTES,
                SAMPLE_RATE_HZ,
                false);
        try (AudioInputStream original = AudioSystem.getAudioInputStream(wav.toFile());
                AudioInputStream normalized = AudioSystem.getAudioInputStream(
                        target, original);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            normalized.transferTo(output);
            byte[] pcm = output.toByteArray();
            assertFalse(pcm.length == 0, () -> wav.getFileName() + " 没有 PCM");
            return pcm;
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
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("windows")) {
            String systemDrive = System.getenv("SystemDrive");
            if (systemDrive == null || !systemDrive.matches("[A-Za-z]:")) {
                throw new IllegalStateException(
                        "VOSK_TEST_ASCII_RUNTIME_ROOT_UNAVAILABLE");
            }
            Path publicDirectory = Path.of(systemDrive + "\\", "Users", "Public");
            if (!Files.isDirectory(publicDirectory)) {
                throw new IllegalStateException(
                        "VOSK_TEST_ASCII_RUNTIME_ROOT_UNAVAILABLE");
            }
            return Files.createTempDirectory(
                    publicDirectory, "ai-friend-vosk-replay-");
        }
        return Files.createTempDirectory("ai-friend-vosk-replay-");
    }
}
