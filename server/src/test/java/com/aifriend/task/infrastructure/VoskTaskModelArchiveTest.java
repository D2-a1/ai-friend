package com.aifriend.task.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.aifriend.task.application.TaskAsrProperties;

class VoskTaskModelArchiveTest {

    private static final String MODEL_SHA256 =
            "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba";

    @Test
    void shouldVerifyAndExtractApprovedChineseModelArchive() throws Exception {
        TaskAsrProperties.Engine properties = properties(MODEL_SHA256);
        Path temporaryRoot;

        try (VoskTaskModelArchive archive = VoskTaskModelArchive.install(properties)) {
            temporaryRoot = archive.modelRoot();
            assertTrue(Files.isRegularFile(temporaryRoot.resolve("am/final.mdl")));
            assertTrue(Files.isRegularFile(temporaryRoot.resolve("graph/Gr.fst")));
            assertEquals(0L, Files.size(
                    temporaryRoot.resolve("ivector/online_cmvn.conf")));
        }

        assertFalse(Files.exists(temporaryRoot));
    }

    @Test
    void shouldRejectArchiveWhenConfiguredHashDoesNotMatch() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> VoskTaskModelArchive.install(properties("0".repeat(64))));

        assertEquals("ASR_MODEL_ARCHIVE_HASH_INVALID", exception.getMessage());
    }

    @Test
    void shouldRejectRelativeRuntimeRoot() {
        TaskAsrProperties.Engine original = properties(MODEL_SHA256);
        TaskAsrProperties.Engine invalid = new TaskAsrProperties.Engine(
                original.enabled(),
                original.modelVersion(),
                original.archivePath(),
                original.archiveSha256(),
                original.archiveRoot(),
                "relative-runtime-root",
                original.maximumAlternatives());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> VoskTaskModelArchive.install(invalid));

        assertEquals("ASR_MODEL_RUNTIME_ROOT_INVALID", exception.getMessage());
    }

    private TaskAsrProperties.Engine properties(String sha256) {
        Path archive = Path.of("..", "android", "app", "src", "main", "assets",
                "voice", "vosk", "vosk-model-small-cn-0.22.zip");
        return new TaskAsrProperties.Engine(
                true,
                "vosk-model-small-cn-0.22",
                archive.toString(),
                sha256,
                "vosk-model-small-cn-0.22/",
                null,
                3);
    }
}
