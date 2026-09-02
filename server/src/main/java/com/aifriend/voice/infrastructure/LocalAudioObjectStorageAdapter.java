package com.aifriend.voice.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.voice.application.AudioObjectStoragePort;
import com.aifriend.voice.application.AudioStorageProperties;
import com.aifriend.voice.application.StoredAudioObject;

/**
 * dev/test 环境项目私有目录音频对象存储适配器。
 *
 * <p>对象键必须解析到配置根目录之内，写入始终使用只创建不覆盖语义。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class LocalAudioObjectStorageAdapter implements AudioObjectStoragePort {

    private final Path storageRoot;
    private final DigestService digestService;

    /**
     * 创建本地私有音频对象存储适配器。
     *
     * @param properties 音频存储配置
     * @param digestService 用于生成内容版本的 SHA-256 服务
     */
    public LocalAudioObjectStorageAdapter(
            AudioStorageProperties properties,
            DigestService digestService) {
        this.storageRoot = Path.of(properties.devStorageRoot()).toAbsolutePath().normalize();
        this.digestService = digestService;
    }

    /** {@inheritDoc} */
    @Override
    public String store(String objectKey, byte[] audioContent) {
        Path destination = resolveObjectKey(objectKey);
        try {
            Files.createDirectories(destination.getParent());
            Files.write(destination, audioContent,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return storageVersion(audioContent);
        } catch (FileAlreadyExistsException exception) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readCurrent(
            String objectKey,
            long maximumBytes) {
        return readBounded(objectKey, maximumBytes);
    }

    /** {@inheritDoc} */
    @Override
    public StoredAudioObject readExact(
            String objectKey,
            String expectedStorageVersion,
            long maximumBytes) {
        StoredAudioObject storedAudioObject = readBounded(
                objectKey, maximumBytes);
        if (!storedAudioObject.storageVersion().equals(
                expectedStorageVersion)) {
            storedAudioObject.close();
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        return storedAudioObject;
    }

    /** {@inheritDoc} */
    @Override
    public void delete(String objectKey) {
        Path destination = resolveObjectKey(objectKey);
        try {
            Files.deleteIfExists(destination);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private StoredAudioObject readBounded(
            String objectKey,
            long maximumBytes) {
        Path source = resolveObjectKey(objectKey);
        if (maximumBytes < 1 || maximumBytes > 20_971_520) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        try {
            long storedSize = Files.size(source);
            if (storedSize < 1 || storedSize > maximumBytes) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            byte[] audioContent;
            try (InputStream input = Files.newInputStream(
                    source, StandardOpenOption.READ)) {
                audioContent = input.readNBytes(
                        Math.toIntExact(maximumBytes) + 1);
            }
            if (audioContent.length != storedSize
                    || audioContent.length > maximumBytes) {
                throw new BusinessException(ErrorCode.AUDIO_INVALID);
            }
            String actualStorageVersion = storageVersion(audioContent);
            return new StoredAudioObject(actualStorageVersion, audioContent);
        } catch (NoSuchFileException exception) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Path resolveObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("\\")) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        Path relative = Path.of(objectKey);
        Path resolved = storageRoot.resolve(relative).normalize();
        if (relative.isAbsolute() || resolved.equals(storageRoot) || !resolved.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return resolved;
    }

    private String storageVersion(byte[] audioContent) {
        return "sha256:" + HexFormat.of().formatHex(digestService.sha256(audioContent));
    }
}
