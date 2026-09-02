package com.aifriend.task.infrastructure;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.aifriend.task.application.TaskAsrProperties;

/** 校验模型归档摘要并受限解压到本进程创建的临时目录。 */
final class VoskTaskModelArchive implements AutoCloseable {

    private static final long MAXIMUM_ARCHIVE_BYTES = 50L * 1024L * 1024L;
    private static final long MAXIMUM_EXPANDED_BYTES = 128L * 1024L * 1024L;
    private static final long MAXIMUM_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int MAXIMUM_ENTRIES = 128;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUIRED_FILES = Set.of(
            "am/final.mdl",
            "conf/mfcc.conf",
            "conf/model.conf",
            "graph/Gr.fst",
            "graph/HCLr.fst",
            "graph/phones/word_boundary.int");

    private final Path temporaryRoot;
    private final Path temporaryParent;
    private final Path modelRoot;

    private VoskTaskModelArchive(
            Path temporaryRoot,
            Path temporaryParent,
            Path modelRoot) {
        this.temporaryRoot = temporaryRoot;
        this.temporaryParent = temporaryParent;
        this.modelRoot = modelRoot;
    }

    static VoskTaskModelArchive install(TaskAsrProperties.Engine properties)
            throws IOException {
        validateProperties(properties);
        Path archive = Path.of(properties.archivePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) {
            throw new IllegalArgumentException("ASR_MODEL_ARCHIVE_UNAVAILABLE");
        }
        verifyArchiveHash(archive, properties.archiveSha256());
        Path temporaryParent = resolveTemporaryParent(properties.runtimeRoot());
        Path temporaryRoot = Files.createTempDirectory(
                temporaryParent, "ai-friend-vosk-")
                .toAbsolutePath().normalize();
        try {
            Path modelRoot = extract(archive, temporaryRoot, properties.archiveRoot());
            return new VoskTaskModelArchive(
                    temporaryRoot, temporaryParent, modelRoot);
        } catch (IOException | RuntimeException exception) {
            deleteOwnedTree(temporaryRoot, temporaryParent);
            throw exception;
        }
    }

    Path modelRoot() {
        return modelRoot;
    }

    @Override
    public void close() throws IOException {
        deleteOwnedTree(temporaryRoot, temporaryParent);
    }

    private static void validateProperties(TaskAsrProperties.Engine properties) {
        boolean invalid = properties == null
                || properties.archivePath() == null || properties.archivePath().isBlank()
                || properties.archiveSha256() == null
                || !SHA_256.matcher(properties.archiveSha256()).matches()
                || properties.archiveRoot() == null
                || !properties.archiveRoot().matches(
                        "[A-Za-z0-9][A-Za-z0-9._-]{0,100}/")
                || properties.maximumAlternatives() < 1
                || properties.maximumAlternatives() > 3;
        if (invalid) {
            throw new IllegalArgumentException("ASR_MODEL_CONFIGURATION_INVALID");
        }
    }

    private static void verifyArchiveHash(Path archive, String expectedHash)
            throws IOException {
        MessageDigest digest = sha256();
        long totalBytes = 0;
        byte[] buffer = new byte[8_192];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(archive))) {
            int readBytes;
            while ((readBytes = input.read(buffer)) >= 0) {
                totalBytes += readBytes;
                if (totalBytes > MAXIMUM_ARCHIVE_BYTES) {
                    throw new IllegalArgumentException("ASR_MODEL_ARCHIVE_TOO_LARGE");
                }
                digest.update(buffer, 0, readBytes);
            }
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
        if (totalBytes == 0 || !MessageDigest.isEqual(
                HexFormat.of().parseHex(expectedHash), digest.digest())) {
            throw new IllegalArgumentException("ASR_MODEL_ARCHIVE_HASH_INVALID");
        }
    }

    private static Path extract(Path archive, Path destination, String archiveRoot)
            throws IOException {
        Set<String> seenFiles = new HashSet<>();
        int entryCount = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[8_192];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAXIMUM_ENTRIES
                        || !entry.getName().startsWith(archiveRoot)) {
                    throw new IllegalArgumentException("ASR_MODEL_ENTRY_INVALID");
                }
                String relative = entry.getName().substring(archiveRoot.length())
                        .replace('\\', '/');
                if (relative.isBlank()) {
                    if (!entry.isDirectory()) {
                        throw new IllegalArgumentException("ASR_MODEL_ENTRY_INVALID");
                    }
                    continue;
                }
                if (relative.startsWith("/") || relative.contains("../")
                        || relative.contains("/..")) {
                    throw new IllegalArgumentException("ASR_MODEL_PATH_INVALID");
                }
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination) || target.equals(destination)) {
                    throw new IllegalArgumentException("ASR_MODEL_PATH_INVALID");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!seenFiles.add(relative)) {
                    throw new IllegalArgumentException("ASR_MODEL_ENTRY_DUPLICATE");
                }
                Files.createDirectories(target.getParent());
                long fileBytes = 0;
                try (OutputStream output = Files.newOutputStream(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int readBytes;
                    while ((readBytes = zip.read(buffer)) >= 0) {
                        fileBytes += readBytes;
                        totalBytes += readBytes;
                        if (fileBytes > MAXIMUM_FILE_BYTES
                                || totalBytes > MAXIMUM_EXPANDED_BYTES) {
                            throw new IllegalArgumentException(
                                    "ASR_MODEL_EXPANDED_SIZE_INVALID");
                        }
                        output.write(buffer, 0, readBytes);
                    }
                }
            }
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
        if (!seenFiles.containsAll(REQUIRED_FILES)) {
            throw new IllegalArgumentException("ASR_MODEL_REQUIRED_FILE_MISSING");
        }
        return destination;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static Path resolveTemporaryParent(String configuredRoot)
            throws IOException {
        Path candidate;
        if (configuredRoot == null || configuredRoot.isBlank()) {
            candidate = Path.of(System.getProperty("java.io.tmpdir"));
        } else {
            candidate = Path.of(configuredRoot.strip());
            if (!candidate.isAbsolute()) {
                throw new IllegalArgumentException("ASR_MODEL_RUNTIME_ROOT_INVALID");
            }
        }
        Files.createDirectories(candidate);
        Path resolved = candidate.toRealPath().normalize();
        if (resolved.getParent() == null) {
            throw new IllegalArgumentException("ASR_MODEL_RUNTIME_ROOT_INVALID");
        }
        return resolved;
    }

    private static void deleteOwnedTree(Path root, Path expectedParent)
            throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedParent = expectedParent.toAbsolutePath().normalize();
        if (!normalizedParent.equals(normalizedRoot.getParent())
                || !root.getFileName().toString().startsWith("ai-friend-vosk-")) {
            throw new IOException("ASR_MODEL_CLEANUP_SCOPE_INVALID");
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
