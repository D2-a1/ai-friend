package com.aifriend.voicecollection.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aifriend.shared.security.DigestService;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportProperties;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportReceipt;
import com.aifriend.voicecollection.application.VoiceTrainingInputItem;
import com.aifriend.voicecollection.application.VoiceTrainingInputSink;
import com.aifriend.voicecollection.application.VoiceTrainingInputSinkPort;
import com.aifriend.voicecollection.application.VoiceTrainingInputSplit;

/**
 * 本机训练输入目录流式写入适配器。
 *
 * <p>所有中间文件只创建在配置根目录的随机暂存目录。成功时使用同文件系统原子移动
 * 发布最终目录；失败或未提交关闭时只递归清理已验证位于根目录内的暂存目录。</p>
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class LocalVoiceTrainingInputSinkAdapter implements VoiceTrainingInputSinkPort {

    private static final String EXPORT_FORMAT_VERSION = "voice-training-input-v3";
    private static final String FUN_ASR_FORMAT_VERSION = "funasr-scp-v2";

    private final VoiceTrainingInputExportProperties properties;
    private final ObjectMapper objectMapper;
    private final DigestService digestService;

    /**
     * 创建本机训练输入写入适配器。
     *
     * @param properties 默认关闭的本机导出配置
     * @param objectMapper 受控 JSON 生成器来源
     * @param digestService SHA-256 与常量时间比较服务
     */
    public LocalVoiceTrainingInputSinkAdapter(
            VoiceTrainingInputExportProperties properties,
            ObjectMapper objectMapper,
            DigestService digestService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.digestService = digestService;
    }

    /** {@inheritDoc} */
    @Override
    public VoiceTrainingInputSink open(
            UUID datasetId,
            String datasetVersion,
            byte[] datasetManifestSha256) {
        if (!properties.enabled()
                || datasetId == null
                || datasetVersion == null
                || !datasetVersion.matches("[a-z0-9][a-z0-9._-]{2,59}")
                || datasetManifestSha256 == null
                || datasetManifestSha256.length != 32) {
            throw new IllegalStateException("本机训练输入导出未启用或数据集参数无效");
        }
        Path exportRoot = null;
        Path stagingDirectory = null;
        try {
            Path configuredRoot = Path.of(properties.rootDirectory()).normalize();
            Files.createDirectories(configuredRoot);
            exportRoot = configuredRoot.toRealPath();
            if (exportRoot.getParent() == null) {
                throw new IllegalStateException("训练输入导出根目录不能是文件系统根目录");
            }
            Path targetDirectory = exportRoot
                    .resolve(datasetVersion + "-" + datasetId)
                    .normalize();
            if (!exportRoot.equals(targetDirectory.getParent())
                    || Files.exists(targetDirectory)) {
                throw new IllegalStateException("训练输入最终目录已存在或超出配置根目录");
            }
            stagingDirectory = Files.createTempDirectory(
                    exportRoot, ".voice-training-building-");
            return new LocalSink(
                    exportRoot,
                    stagingDirectory,
                    targetDirectory,
                    datasetVersion,
                    datasetManifestSha256.clone());
        } catch (IOException exception) {
            cleanupAfterOpenFailure(exportRoot, stagingDirectory, exception);
            throw new IllegalStateException("创建训练输入暂存目录失败", exception);
        } catch (RuntimeException exception) {
            cleanupAfterOpenFailure(exportRoot, stagingDirectory, exception);
            throw exception;
        }
    }

    private void cleanupAfterOpenFailure(
            Path exportRoot,
            Path stagingDirectory,
            Exception failure) {
        if (exportRoot == null || stagingDirectory == null) {
            return;
        }
        try {
            deleteDirectoryInsideRoot(exportRoot, stagingDirectory);
        } catch (RuntimeException cleanupException) {
            failure.addSuppressed(cleanupException);
        }
    }

    private void deleteDirectoryInsideRoot(Path exportRoot, Path directory) {
        Path normalizedRoot = exportRoot.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)
                || normalizedDirectory.equals(normalizedRoot)) {
            throw new IllegalStateException("拒绝清理配置根目录之外的暂存路径");
        }
        try (Stream<Path> paths = Files.walk(normalizedDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理训练输入暂存目录失败", exception);
        }
    }

    private JsonGenerator openJsonGenerator(Path path) throws IOException {
        OutputStream output = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            return objectMapper.getFactory().createGenerator(output);
        } catch (IOException | RuntimeException exception) {
            try {
                output.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    private void closeAfterInitializationFailure(
            AutoCloseable resource,
            Exception failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeException) {
            failure.addSuppressed(closeException);
        }
    }

    private final class LocalSink implements VoiceTrainingInputSink {

        private final Path exportRoot;
        private final Path stagingDirectory;
        private final Path targetDirectory;
        private final String datasetVersion;
        private final byte[] datasetManifestSha256;
        private final JsonGenerator labelGenerator;
        private final OutputStream funAsrAllWavOutput;
        private final OutputStream funAsrAllTextOutput;
        private final OutputStream funAsrTrainWavOutput;
        private final OutputStream funAsrTrainTextOutput;
        private final OutputStream funAsrValidationWavOutput;
        private final OutputStream funAsrValidationTextOutput;

        private int writtenCount;
        private int trainCount;
        private int validationCount;
        private long writtenAudioBytes;
        private boolean resourcesClosed;
        private boolean committed;

        private LocalSink(
                Path exportRoot,
                Path stagingDirectory,
                Path targetDirectory,
                String datasetVersion,
                byte[] datasetManifestSha256) throws IOException {
            this.exportRoot = exportRoot;
            this.stagingDirectory = stagingDirectory;
            this.targetDirectory = targetDirectory;
            this.datasetVersion = datasetVersion;
            this.datasetManifestSha256 = datasetManifestSha256;
            JsonGenerator openedLabelGenerator = null;
            OutputStream openedAllWavOutput = null;
            OutputStream openedAllTextOutput = null;
            OutputStream openedTrainWavOutput = null;
            OutputStream openedTrainTextOutput = null;
            OutputStream openedValidationWavOutput = null;
            OutputStream openedValidationTextOutput = null;
            try {
                Files.createDirectory(stagingDirectory.resolve("audio"));
                Files.createDirectory(stagingDirectory.resolve("funasr"));
                openedLabelGenerator = openJsonGenerator(
                        stagingDirectory.resolve("labels.jsonl"));
                openedAllWavOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/all_wav.scp"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                openedAllTextOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/all_text.txt"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                openedTrainWavOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/train_wav.scp"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                openedTrainTextOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/train_text.txt"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                openedValidationWavOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/val_wav.scp"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                openedValidationTextOutput = Files.newOutputStream(
                        stagingDirectory.resolve("funasr/val_text.txt"),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            } catch (IOException | RuntimeException exception) {
                closeAfterInitializationFailure(
                        openedValidationTextOutput, exception);
                closeAfterInitializationFailure(
                        openedValidationWavOutput, exception);
                closeAfterInitializationFailure(openedTrainTextOutput, exception);
                closeAfterInitializationFailure(openedTrainWavOutput, exception);
                closeAfterInitializationFailure(openedAllTextOutput, exception);
                closeAfterInitializationFailure(openedAllWavOutput, exception);
                closeAfterInitializationFailure(
                        openedLabelGenerator, exception);
                throw exception;
            }
            this.labelGenerator = openedLabelGenerator;
            this.funAsrAllWavOutput = openedAllWavOutput;
            this.funAsrAllTextOutput = openedAllTextOutput;
            this.funAsrTrainWavOutput = openedTrainWavOutput;
            this.funAsrTrainTextOutput = openedTrainTextOutput;
            this.funAsrValidationWavOutput = openedValidationWavOutput;
            this.funAsrValidationTextOutput = openedValidationTextOutput;
        }

        /** {@inheritDoc} */
        @Override
        public void write(
                VoiceTrainingInputItem item,
                byte[] audioContent,
                byte[] transcriptUtf8) {
            ensureOpen();
            validateItem(item, audioContent, transcriptUtf8);
            String fileName = String.format("%06d.wav", item.memberOrder());
            String utteranceKey = String.format("utt_%06d", item.memberOrder());
            String relativeAudioPath = "audio/" + fileName;
            long nextTotal;
            try {
                nextTotal = Math.addExact(writtenAudioBytes, audioContent.length);
            } catch (ArithmeticException exception) {
                throw new IllegalStateException("训练输入音频总大小溢出", exception);
            }
            if (nextTotal > properties.maximumTotalBytes()) {
                throw new IllegalStateException("训练输入音频总大小超过配置上限");
            }
            try {
                Files.write(
                        stagingDirectory.resolve("audio").resolve(fileName),
                        audioContent,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                labelGenerator.writeStartObject();
                labelGenerator.writeNumberField("memberOrder", item.memberOrder());
                labelGenerator.writeStringField("audioFile", relativeAudioPath);
                labelGenerator.writeStringField("category", item.category().name());
                labelGenerator.writeStringField("promptCode", item.promptCode());
                labelGenerator.writeStringField("environment", item.environment().name());
                labelGenerator.writeStringField("dialectCode", item.dialectCode());
                labelGenerator.writeStringField("split", item.split().name());
                labelGenerator.writeNumberField("durationMs", item.durationMs());
                labelGenerator.writeStringField(
                        "audioSha256",
                        HexFormat.of().formatHex(item.audioSha256()));
                labelGenerator.writeStringField(
                        "transcriptSha256",
                        HexFormat.of().formatHex(item.transcriptSha256()));
                labelGenerator.writeFieldName("transcript");
                labelGenerator.writeUTF8String(
                        transcriptUtf8, 0, transcriptUtf8.length);
                labelGenerator.writeEndObject();
                labelGenerator.writeRaw('\n');
                labelGenerator.flush();
                writeAsciiLine(
                        funAsrAllWavOutput,
                        utteranceKey + "\t" + relativeAudioPath);
                writeFunAsrTextLine(utteranceKey, transcriptUtf8);
                funAsrAllWavOutput.flush();
                funAsrAllTextOutput.flush();
                if (item.split() == VoiceTrainingInputSplit.TRAIN) {
                    writeFunAsrPair(
                            funAsrTrainWavOutput,
                            funAsrTrainTextOutput,
                            utteranceKey,
                            relativeAudioPath,
                            transcriptUtf8);
                    trainCount++;
                } else {
                    writeFunAsrPair(
                            funAsrValidationWavOutput,
                            funAsrValidationTextOutput,
                            utteranceKey,
                            relativeAudioPath,
                            transcriptUtf8);
                    validationCount++;
                }
                writtenCount++;
                writtenAudioBytes = nextTotal;
            } catch (IOException exception) {
                throw new IllegalStateException("写入训练输入暂存文件失败", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public VoiceTrainingInputExportReceipt commit(int expectedSampleCount) {
            ensureOpen();
            if (expectedSampleCount < 2
                    || writtenCount != expectedSampleCount
                    || trainCount < 1
                    || validationCount < 1) {
                throw new IllegalStateException("训练输入写入数量与数据集不一致");
            }
            closeResources();
            writeDatasetManifest();
            try {
                Files.move(
                        stagingDirectory,
                        targetDirectory,
                        StandardCopyOption.ATOMIC_MOVE);
                committed = true;
                return new VoiceTrainingInputExportReceipt(
                        targetDirectory,
                        writtenCount,
                        writtenAudioBytes,
                        datasetManifestSha256);
            } catch (IOException exception) {
                throw new IllegalStateException("原子发布训练输入目录失败", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            RuntimeException closeFailure = null;
            try {
                closeResources();
            } catch (RuntimeException exception) {
                closeFailure = exception;
            }
            if (!committed) {
                try {
                    deleteStagingDirectory();
                } catch (RuntimeException exception) {
                    if (closeFailure == null) {
                        closeFailure = exception;
                    } else {
                        closeFailure.addSuppressed(exception);
                    }
                }
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private void validateItem(
                VoiceTrainingInputItem item,
                byte[] audioContent,
                byte[] transcriptUtf8) {
            boolean valid = item != null
                    && item.memberOrder() == writtenCount
                    && item.category() != null
                    && item.promptCode() != null
                    && item.environment() != null
                    && item.dialectCode() != null
                    && item.split() != null
                    && item.durationMs() >= 200
                    && item.durationMs() <= 60_000
                    && item.audioSha256() != null
                    && item.audioSha256().length == 32
                    && item.transcriptSha256() != null
                    && item.transcriptSha256().length == 32
                    && audioContent != null
                    && audioContent.length >= 1
                    && audioContent.length
                        <= VoiceTrainingInputExportProperties.MAXIMUM_AUDIO_BYTES
                    && transcriptUtf8 != null
                    && transcriptUtf8.length >= 1
                    && transcriptUtf8.length <= 480
                    && !containsLineControl(transcriptUtf8)
                    && digestService.constantTimeEquals(
                        item.audioSha256(), digestService.sha256(audioContent))
                    && digestService.constantTimeEquals(
                        item.transcriptSha256(), digestService.sha256(transcriptUtf8));
            if (!valid) {
                throw new IllegalStateException("训练输入成员顺序、摘要或大小无效");
            }
        }

        private void writeDatasetManifest() {
            try (JsonGenerator generator = openJsonGenerator(
                    stagingDirectory.resolve("dataset.json"))) {
                generator.writeStartObject();
                generator.writeStringField("formatVersion", EXPORT_FORMAT_VERSION);
                generator.writeStringField(
                        "trainingFormatVersion", FUN_ASR_FORMAT_VERSION);
                generator.writeStringField("datasetVersion", datasetVersion);
                generator.writeStringField(
                        "datasetManifestSha256",
                        HexFormat.of().formatHex(datasetManifestSha256));
                generator.writeNumberField("sampleCount", writtenCount);
                generator.writeNumberField("trainSampleCount", trainCount);
                generator.writeNumberField(
                        "validationSampleCount", validationCount);
                generator.writeNumberField("audioBytes", writtenAudioBytes);
                generator.writeStringField("funAsrWavScp", "funasr/all_wav.scp");
                generator.writeStringField("funAsrText", "funasr/all_text.txt");
                generator.writeStringField(
                        "funAsrTrainWavScp", "funasr/train_wav.scp");
                generator.writeStringField(
                        "funAsrTrainText", "funasr/train_text.txt");
                generator.writeStringField(
                        "funAsrValidationWavScp", "funasr/val_wav.scp");
                generator.writeStringField(
                        "funAsrValidationText", "funasr/val_text.txt");
                generator.writeBooleanField("jsonlGenerationRequired", true);
                generator.writeEndObject();
            } catch (IOException exception) {
                throw new IllegalStateException("写入训练输入清单失败", exception);
            }
        }

        private void ensureOpen() {
            if (committed || resourcesClosed) {
                throw new IllegalStateException("训练输入暂存会话已经关闭");
            }
        }

        private void closeResources() {
            if (resourcesClosed) {
                return;
            }
            RuntimeException closeFailure = null;
            try {
                closeFailure = closeResource(
                        funAsrValidationTextOutput,
                        "关闭 FunASR 验证文字清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        funAsrValidationWavOutput,
                        "关闭 FunASR 验证音频清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        funAsrTrainTextOutput,
                        "关闭 FunASR 训练文字清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        funAsrTrainWavOutput,
                        "关闭 FunASR 训练音频清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        funAsrAllTextOutput,
                        "关闭 FunASR 全量文字清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        funAsrAllWavOutput,
                        "关闭 FunASR 全量音频清单失败",
                        closeFailure);
                closeFailure = closeResource(
                        labelGenerator,
                        "关闭训练输入标签文件失败",
                        closeFailure);
            } finally {
                resourcesClosed = true;
            }
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private RuntimeException mergeCloseFailure(
                RuntimeException existing,
                RuntimeException next) {
            if (existing == null) {
                return next;
            }
            existing.addSuppressed(next);
            return existing;
        }

        private RuntimeException closeResource(
                AutoCloseable resource,
                String message,
                RuntimeException existing) {
            try {
                resource.close();
                return existing;
            } catch (Exception exception) {
                return mergeCloseFailure(
                        existing,
                        new IllegalStateException(message, exception));
            }
        }

        private void writeAsciiLine(OutputStream output, String value)
                throws IOException {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
            output.write('\n');
        }

        private void writeFunAsrTextLine(
                String utteranceKey,
                byte[] transcriptUtf8) throws IOException {
            funAsrAllTextOutput.write(
                    (utteranceKey + "\t").getBytes(StandardCharsets.US_ASCII));
            funAsrAllTextOutput.write(transcriptUtf8);
            funAsrAllTextOutput.write(10);
        }

        private void writeFunAsrPair(
                OutputStream wavOutput,
                OutputStream textOutput,
                String utteranceKey,
                String relativeAudioPath,
                byte[] transcriptUtf8) throws IOException {
            wavOutput.write(utteranceKey.getBytes(StandardCharsets.US_ASCII));
            wavOutput.write(9);
            wavOutput.write(relativeAudioPath.getBytes(StandardCharsets.US_ASCII));
            wavOutput.write(10);
            textOutput.write(utteranceKey.getBytes(StandardCharsets.US_ASCII));
            textOutput.write(9);
            textOutput.write(transcriptUtf8);
            textOutput.write(10);
            wavOutput.flush();
            textOutput.flush();
        }

        private boolean containsLineControl(byte[] value) {
            for (byte current : value) {
                int unsigned = current & 0xFF;
                if (unsigned < 0x20 || unsigned == 0x7F) {
                    return true;
                }
            }
            return false;
        }

        private void deleteStagingDirectory() {
            deleteDirectoryInsideRoot(exportRoot, stagingDirectory);
        }
    }
}
