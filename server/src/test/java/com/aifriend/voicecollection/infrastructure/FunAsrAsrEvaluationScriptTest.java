package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunAsrAsrEvaluationScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "evaluate-funasr-asr.ps1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldCalculateNormalizedMicroCerAndPublishImmutableReport()
            throws Exception {
        Path datasetDirectory = createSyntheticDataset();
        Path hypothesisFile = temporaryDirectory.resolve("1best_recog_text");
        Files.writeString(
                hypothesisFile,
                "utt_000001\t<|zh|>你好女儿\n"
                        + "utt_000002\t发消息给女子\n",
                UTF_8);

        Process process = evaluationProcess(
                datasetDirectory,
                hypothesisFile,
                "candidate-001");
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();

        Path resultDirectory = datasetDirectory.resolve(
                "funasr/evaluations/candidate-001");
        JsonNode report = objectMapper.readTree(
                resultDirectory.resolve("evaluation.json").toFile());
        assertThat(exitCode).as(output).isZero();
        assertThat(report.path("formatVersion").asText())
                .isEqualTo("funasr-asr-evaluation-v1");
        assertThat(report.path("sampleCount").asInt()).isEqualTo(2);
        assertThat(report.path("editDistance").asInt()).isEqualTo(1);
        assertThat(report.path("referenceCharacterCount").asInt()).isEqualTo(10);
        assertThat(report.path("microCer").asDouble()).isEqualTo(0.1d);
        assertThat(report.path("sentenceAccuracy").asDouble()).isEqualTo(0.5d);
        assertThat(report.path("thresholdApplied").asBoolean()).isFalse();
        assertThat(resultDirectory.resolve("details.jsonl")).exists();
        try (var paths = Files.list(datasetDirectory.resolve("funasr/evaluations"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".evaluation-staging-"));
        }
    }

    @Test
    void shouldValidateMetricsWithoutPublishingReport() throws Exception {
        Path datasetDirectory = createSyntheticDataset();
        Path hypothesisFile = temporaryDirectory.resolve("hypothesis.txt");
        Files.writeString(
                hypothesisFile,
                "utt_000001\t你好，女儿！\n"
                        + "utt_000002\t发消息给儿子\n",
                UTF_8);

        Process process = new ProcessBuilder(
                windowsPowerShell().toString(),
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                SCRIPT_PATH.toAbsolutePath().toString(),
                "-DatasetDirectory",
                datasetDirectory.toString(),
                "-HypothesisFile",
                hypothesisFile.toString(),
                "-EvaluationName",
                "validate-only",
                "-ValidateOnly")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();

        assertThat(exitCode).as(output).isZero();
        assertThat(output).contains("CER", "1");
        assertThat(datasetDirectory.resolve("funasr/evaluations")).doesNotExist();
    }

    @Test
    void shouldRejectIncompleteHypothesesWithoutLeavingOutput() throws Exception {
        Path datasetDirectory = createSyntheticDataset();
        Path hypothesisFile = temporaryDirectory.resolve("incomplete.txt");
        Files.writeString(hypothesisFile, "utt_000001\t你好女儿\n", UTF_8);

        Process process = evaluationProcess(
                datasetDirectory,
                hypothesisFile,
                "candidate-incomplete");
        int exitCode = process.waitFor();

        assertThat(exitCode).isNotZero();
        assertThat(datasetDirectory.resolve(
                "funasr/evaluations/candidate-incomplete")).doesNotExist();
    }

    @Test
    void shouldRejectChangedValidationJsonl() throws Exception {
        Path datasetDirectory = createSyntheticDataset();
        Path hypothesisFile = temporaryDirectory.resolve("changed-jsonl.txt");
        Files.writeString(
                hypothesisFile,
                "utt_000001\t你好女儿\n"
                        + "utt_000002\t发消息给儿子\n",
                UTF_8);
        Files.writeString(
                datasetDirectory.resolve("funasr/jsonl/val.jsonl"),
                "{\"key\":\"changed\"}\n",
                UTF_8);

        Process process = evaluationProcess(
                datasetDirectory,
                hypothesisFile,
                "candidate-changed-jsonl");
        int exitCode = process.waitFor();

        assertThat(exitCode).isNotZero();
        assertThat(datasetDirectory.resolve(
                "funasr/evaluations/candidate-changed-jsonl")).doesNotExist();
    }

    @Test
    void shouldNeverOverwritePublishedEvaluation() throws Exception {
        Path datasetDirectory = createSyntheticDataset();
        Path hypothesisFile = temporaryDirectory.resolve("immutable.txt");
        Files.writeString(
                hypothesisFile,
                "utt_000001\t你好女儿\n"
                        + "utt_000002\t发消息给儿子\n",
                UTF_8);

        Process firstProcess = evaluationProcess(
                datasetDirectory,
                hypothesisFile,
                "candidate-immutable");
        String firstOutput = new String(
                firstProcess.getInputStream().readAllBytes(),
                UTF_8);
        assertThat(firstProcess.waitFor()).as(firstOutput).isZero();
        Path reportPath = datasetDirectory.resolve(
                "funasr/evaluations/candidate-immutable/evaluation.json");
        String originalReport = Files.readString(reportPath, UTF_8);

        Files.writeString(
                hypothesisFile,
                "utt_000001\t错误结果\n"
                        + "utt_000002\t错误结果\n",
                UTF_8);
        Process secondProcess = evaluationProcess(
                datasetDirectory,
                hypothesisFile,
                "candidate-immutable");
        int secondExitCode = secondProcess.waitFor();

        assertThat(secondExitCode).isNotZero();
        assertThat(Files.readString(reportPath, UTF_8)).isEqualTo(originalReport);
    }

    @Test
    void shouldKeepOfficialCerBoundaryUtf8BomAndCommandForwarding()
            throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "evaluate-funasr-asr.cmd"), UTF_8);

        assertThat(script)
                .contains(
                        "funasr-normalized-micro-cer-v1",
                        "ConvertTo-CerText",
                        "Get-EditDistance",
                        "totalEditDistance",
                        "totalReferenceCharacters",
                        "thresholdApplied = $false",
                        "datasetJsonSha256",
                        "validationJsonlSha256",
                        "hypothesisSha256",
                        "输入文件在评测期间发生变化",
                        "识别结果目录",
                        "[IO.Directory]::Move($stagingDirectory, $finalDirectory)",
                        "$ValidateOnly",
                        "ReparsePoint")
                .doesNotContain(
                        "pip install",
                        "git clone",
                        "Invoke-WebRequest",
                        "Get-FileHash",
                        "IsPathFullyQualified");
        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(command)
                .contains(
                        "evaluate-funasr-asr.ps1",
                        "%*",
                        "exit /b %evaluateExitCode%");
    }

    private Process evaluationProcess(
            Path datasetDirectory,
            Path hypothesisFile,
            String evaluationName) throws Exception {
        return new ProcessBuilder(
                windowsPowerShell().toString(),
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                SCRIPT_PATH.toAbsolutePath().toString(),
                "-DatasetDirectory",
                datasetDirectory.toString(),
                "-HypothesisFile",
                hypothesisFile.toString(),
                "-EvaluationName",
                evaluationName)
                .redirectErrorStream(true)
                .start();
    }

    private Path createSyntheticDataset() throws Exception {
        Path datasetDirectory = Files.createDirectory(
                temporaryDirectory.resolve("evaluation dataset").toAbsolutePath());
        Path funAsrDirectory = Files.createDirectories(
                datasetDirectory.resolve("funasr"));
        Path jsonlDirectory = Files.createDirectories(
                funAsrDirectory.resolve("jsonl"));
        Files.writeString(
                funAsrDirectory.resolve("val_text.txt"),
                "utt_000001\t你好，女儿！\n"
                        + "utt_000002\t发消息给儿子\n",
                UTF_8);
        Path datasetJson = datasetDirectory.resolve("dataset.json");
        Files.writeString(
                datasetJson,
                """
                {
                  "formatVersion": "voice-training-input-v3",
                  "trainingFormatVersion": "funasr-scp-v2",
                  "validationSampleCount": 2,
                  "funAsrValidationText": "funasr/val_text.txt"
                }
                """,
                UTF_8);
        Path validationJsonl = jsonlDirectory.resolve("val.jsonl");
        Files.writeString(
                validationJsonl,
                "{\"key\":\"utt_000001\"}\n"
                        + "{\"key\":\"utt_000002\"}\n",
                UTF_8);
        Files.writeString(
                jsonlDirectory.resolve("train.jsonl"),
                "{\"key\":\"utt_000000\"}\n",
                UTF_8);
        Files.writeString(
                jsonlDirectory.resolve("conversion.json"),
                objectMapper.writeValueAsString(java.util.Map.of(
                        "formatVersion", "funasr-jsonl-v1",
                        "datasetJsonSha256", sha256(datasetJson),
                        "validationSampleCount", 2,
                        "validationJsonl", "val.jsonl",
                        "validationJsonlSha256", sha256(validationJsonl))),
                UTF_8);
        return datasetDirectory;
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private Path windowsPowerShell() {
        String systemRoot = System.getenv("SystemRoot");
        Assumptions.assumeTrue(systemRoot != null);
        Path windowsPowerShell = Path.of(
                systemRoot,
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe");
        Assumptions.assumeTrue(Files.isExecutable(windowsPowerShell));
        return windowsPowerShell;
    }
}
