package com.aifriend.voice.infrastructure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.application.AudioStorageProperties;
import com.aifriend.voice.application.StoredAudioObject;

class LocalAudioObjectStorageAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateOnceWithoutOverwritingExistingObject() throws Exception {
        LocalAudioObjectStorageAdapter adapter = adapter();
        byte[] content = new byte[] {1, 2, 3};

        String storageVersion = adapter.store("temporary/object-one", content);

        assertArrayEquals(content, Files.readAllBytes(
                tempDir.resolve("temporary/object-one")));
        StoredAudioObject stored = adapter.readExact(
                "temporary/object-one", storageVersion, content.length);
        assertArrayEquals(content, stored.audioContent());
        assertEquals(storageVersion, stored.storageVersion());
        StoredAudioObject current = adapter.readCurrent(
                "temporary/object-one", content.length);
        assertArrayEquals(content, current.audioContent());
        assertEquals(storageVersion, current.storageVersion());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.store("temporary/object-one", new byte[] {9}));
        assertEquals(ErrorCode.SESSION_CONFLICT, exception.errorCode());
        assertArrayEquals(content, Files.readAllBytes(
                tempDir.resolve("temporary/object-one")));
    }

    @Test
    void shouldRejectChangedContentOrStorageVersion() throws Exception {
        LocalAudioObjectStorageAdapter adapter = adapter();
        String storageVersion = adapter.store(
                "temporary/object-versioned", new byte[] {1, 2, 3});
        Files.write(tempDir.resolve("temporary/object-versioned"), new byte[] {3, 2, 1});

        BusinessException changedContent = assertThrows(
                BusinessException.class,
                () -> adapter.readExact(
                        "temporary/object-versioned", storageVersion, 3));
        BusinessException wrongVersion = assertThrows(
                BusinessException.class,
                () -> adapter.readExact(
                        "temporary/object-versioned", "sha256:wrong", 3));

        assertEquals(ErrorCode.AUDIO_INVALID, changedContent.errorCode());
        assertEquals(ErrorCode.AUDIO_INVALID, wrongVersion.errorCode());
    }

    @Test
    void shouldRejectPathTraversalBeforeFileAccess() {
        LocalAudioObjectStorageAdapter adapter = adapter();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> adapter.store("../outside-object", new byte[] {1}));

        assertEquals(ErrorCode.INTERNAL_ERROR, exception.errorCode());
    }

    private LocalAudioObjectStorageAdapter adapter() {
        return new LocalAudioObjectStorageAdapter(
                new AudioStorageProperties(
                        Duration.ofMinutes(10),
                        URI.create("http://10.0.2.2:8080/api/v1/dev/audio-objects/"),
                        tempDir.toString(),
                        Duration.ofMinutes(5),
                        100),
                new DigestService());
    }
}
