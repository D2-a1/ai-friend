package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aifriend.shared.security.DigestService;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportProperties;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportReceipt;
import com.aifriend.voicecollection.application.VoiceTrainingInputItem;
import com.aifriend.voicecollection.application.VoiceTrainingInputSink;
import com.aifriend.voicecollection.application.VoiceTrainingInputSplit;
import com.aifriend.voicecollection.domain.VoiceCollectionCategory;
import com.aifriend.voicecollection.domain.VoiceCollectionEnvironment;

class LocalVoiceTrainingInputSinkAdapterTest {

    private static final UUID DATASET_ID =
            UUID.fromString("2ae9ed26-dc79-4ed8-bcbc-31f3a91953cc");
    private static final String DATASET_VERSION = "wugang-personal-20260828-v2";

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWriteChineseLabelsAndAtomicallyPublishWithoutOwnerIdentifiers()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("exports").toAbsolutePath();
        ObjectMapper objectMapper = new ObjectMapper();
        DigestService digestService = new DigestService();
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] transcript = "打电话给女儿".getBytes(UTF_8);
        byte[] validationAudio = "RIFF1111WAVEfmt ".getBytes(UTF_8);
        byte[] validationTranscript = "发消息给儿子".getBytes(UTF_8);
        byte[] datasetManifest = digestService.sha256("dataset");
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, objectMapper, digestService);

        VoiceTrainingInputExportReceipt receipt;
        try (VoiceTrainingInputSink sink = adapter.open(
                DATASET_ID, DATASET_VERSION, datasetManifest)) {
            sink.write(
                    item(
                            0,
                            audio,
                            transcript,
                            VoiceTrainingInputSplit.TRAIN,
                            digestService),
                    audio,
                    transcript);
            sink.write(
                    item(
                            1,
                            validationAudio,
                            validationTranscript,
                            VoiceTrainingInputSplit.VALIDATION,
                            digestService),
                    validationAudio,
                    validationTranscript);
            receipt = sink.commit(2);
        }

        assertEquals(2, receipt.sampleCount());
        assertEquals(audio.length + validationAudio.length, receipt.audioBytes());
        assertArrayEquals(datasetManifest, receipt.datasetManifestSha256());
        assertArrayEquals(
                audio,
                Files.readAllBytes(receipt.outputDirectory().resolve("audio/000000.wav")));
        String labelLine = Files.readString(
                receipt.outputDirectory().resolve("labels.jsonl"), UTF_8).strip();
        JsonNode label = objectMapper.readTree(labelLine);
        assertEquals("打电话给女儿", label.get("transcript").asText());
        assertEquals("audio/000000.wav", label.get("audioFile").asText());
        assertEquals("TRAIN", label.get("split").asText());
        assertEquals(1_000, label.get("durationMs").asInt());
        assertEquals(
                "utt_000000\taudio/000000.wav\n"
                        + "utt_000001\taudio/000001.wav\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/all_wav.scp"),
                        UTF_8));
        assertEquals(
                "utt_000000\t打电话给女儿\n"
                        + "utt_000001\t发消息给儿子\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/all_text.txt"),
                        UTF_8));
        assertEquals(
                "utt_000000\taudio/000000.wav\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/train_wav.scp"),
                        UTF_8));
        assertEquals(
                "utt_000000\t打电话给女儿\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/train_text.txt"),
                        UTF_8));
        assertEquals(
                "utt_000001\taudio/000001.wav\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/val_wav.scp"),
                        UTF_8));
        assertEquals(
                "utt_000001\t发消息给儿子\n",
                Files.readString(
                        receipt.outputDirectory().resolve("funasr/val_text.txt"),
                        UTF_8));
        String manifest = Files.readString(
                receipt.outputDirectory().resolve("dataset.json"), UTF_8);
        JsonNode dataset = objectMapper.readTree(manifest);
        assertFalse(manifest.contains("owner"));
        assertTrue(manifest.contains(DATASET_VERSION));
        assertTrue(manifest.contains("voice-training-input-v3"));
        assertTrue(manifest.contains("funasr-scp-v2"));
        assertEquals(1, dataset.get("trainSampleCount").asInt());
        assertEquals(1, dataset.get("validationSampleCount").asInt());
        assertTrue(dataset.get("jsonlGenerationRequired").asBoolean());
    }

    @Test
    void shouldRemoveStagingDirectoryWhenSessionClosesWithoutCommit()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("cancelled").toAbsolutePath();
        DigestService digestService = new DigestService();
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, new ObjectMapper(), digestService);

        try (VoiceTrainingInputSink ignored = adapter.open(
                DATASET_ID, DATASET_VERSION, digestService.sha256("dataset"))) {
            assertTrue(Files.exists(exportRoot));
        }

        try (var paths = Files.list(exportRoot)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void shouldRejectPublishingWithoutValidationAndCleanStaging()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("missing-validation")
                .toAbsolutePath();
        DigestService digestService = new DigestService();
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] transcript = "测试".getBytes(UTF_8);
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, new ObjectMapper(), digestService);

        assertThrows(IllegalStateException.class, () -> {
            try (VoiceTrainingInputSink sink = adapter.open(
                    DATASET_ID,
                    DATASET_VERSION,
                    digestService.sha256("dataset"))) {
                sink.write(
                        item(0, audio, transcript, digestService),
                        audio,
                        transcript);
                sink.commit(1);
            }
        });

        try (var paths = Files.list(exportRoot)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void shouldRejectCapacityOverflowAndCleanStagingDirectory()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("bounded").toAbsolutePath();
        DigestService digestService = new DigestService();
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] transcript = "测试".getBytes(UTF_8);
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, audio.length - 1L, new ObjectMapper(), digestService);

        assertThrows(IllegalStateException.class, () -> {
            try (VoiceTrainingInputSink sink = adapter.open(
                    DATASET_ID,
                    DATASET_VERSION,
                    digestService.sha256("dataset"))) {
                sink.write(
                        item(0, audio, transcript, digestService),
                        audio,
                        transcript);
            }
        });

        try (var paths = Files.list(exportRoot)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void shouldNeverOverwriteExistingFinalDatasetDirectory() throws Exception {
        Path exportRoot = temporaryDirectory.resolve("duplicate").toAbsolutePath();
        DigestService digestService = new DigestService();
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] transcript = "测试".getBytes(UTF_8);
        byte[] validationAudio = "RIFF1111WAVEfmt ".getBytes(UTF_8);
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, new ObjectMapper(), digestService);
        byte[] manifest = digestService.sha256("dataset");
        try (VoiceTrainingInputSink sink = adapter.open(
                DATASET_ID, DATASET_VERSION, manifest)) {
            sink.write(item(0, audio, transcript, digestService), audio, transcript);
            sink.write(
                    item(
                            1,
                            validationAudio,
                            transcript,
                            VoiceTrainingInputSplit.VALIDATION,
                            digestService),
                    validationAudio,
                    transcript);
            sink.commit(2);
        }

        assertThrows(
                IllegalStateException.class,
                () -> adapter.open(DATASET_ID, DATASET_VERSION, manifest));
    }

    @Test
    void shouldCleanStagingDirectoryWhenLabelGeneratorInitializationFails()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("initialization-failure")
                .toAbsolutePath();
        DigestService digestService = new DigestService();
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        JsonFactory jsonFactory = mock(JsonFactory.class);
        when(objectMapper.getFactory()).thenReturn(jsonFactory);
        when(jsonFactory.createGenerator(any(OutputStream.class)))
                .thenThrow(new IOException("simulated"));
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, objectMapper, digestService);

        assertThrows(
                IllegalStateException.class,
                () -> adapter.open(
                        DATASET_ID,
                        DATASET_VERSION,
                        digestService.sha256("dataset")));

        try (var paths = Files.list(exportRoot)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    @Test
    void shouldRejectLineBreakingTranscriptAndCleanStagingDirectory()
            throws Exception {
        Path exportRoot = temporaryDirectory.resolve("line-injection")
                .toAbsolutePath();
        DigestService digestService = new DigestService();
        byte[] audio = "RIFF0000WAVEfmt ".getBytes(UTF_8);
        byte[] transcript = "第一行\n第二行".getBytes(UTF_8);
        LocalVoiceTrainingInputSinkAdapter adapter = adapter(
                exportRoot, 1_024L, new ObjectMapper(), digestService);

        assertThrows(IllegalStateException.class, () -> {
            try (VoiceTrainingInputSink sink = adapter.open(
                    DATASET_ID,
                    DATASET_VERSION,
                    digestService.sha256("dataset"))) {
                sink.write(
                        item(0, audio, transcript, digestService),
                        audio,
                        transcript);
            }
        });

        try (var paths = Files.list(exportRoot)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    private LocalVoiceTrainingInputSinkAdapter adapter(
            Path exportRoot,
            long maximumTotalBytes,
            ObjectMapper objectMapper,
            DigestService digestService) {
        return new LocalVoiceTrainingInputSinkAdapter(
                new VoiceTrainingInputExportProperties(
                        true, exportRoot.toString(), maximumTotalBytes),
                objectMapper,
                digestService);
    }

    private VoiceTrainingInputItem item(
            int memberOrder,
            byte[] audio,
            byte[] transcript,
            DigestService digestService) {
        return item(
                memberOrder,
                audio,
                transcript,
                VoiceTrainingInputSplit.TRAIN,
                digestService);
    }

    private VoiceTrainingInputItem item(
            int memberOrder,
            byte[] audio,
            byte[] transcript,
            VoiceTrainingInputSplit split,
            DigestService digestService) {
        return new VoiceTrainingInputItem(
                memberOrder,
                VoiceCollectionCategory.FULL_TASK,
                "full_task_contact_message",
                VoiceCollectionEnvironment.QUIET,
                "zh-Hans-CN-x-wugang",
                split,
                1_000,
                digestService.sha256(audio),
                digestService.sha256(transcript));
    }
}
