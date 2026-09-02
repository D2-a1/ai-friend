package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunAsrCandidatePreparationScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "prepare-funasr-candidate-report.ps1");
    private static final Path EVALUATION_SCRIPT_PATH =
            Path.of("scripts", "evaluate-funasr-asr.ps1");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldEvaluateInferenceAndPublishMeasuredOnlyCandidateReport()
            throws Exception {
        CandidateFixture fixture = createCandidateFixture();

        ProcessResult result = runCandidateScript(fixture, "candidate-local-01", false);

        Path preparationDirectory = fixture.datasetDirectory().resolve(
                "funasr/candidate-preparations/candidate-local-01");
        Path evaluationDirectory = fixture.datasetDirectory().resolve(
                "funasr/evaluations/candidate-local-01-evaluation");
        JsonNode report = OBJECT_MAPPER.readTree(
                preparationDirectory.resolve("candidate-preparation.json").toFile());
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(evaluationDirectory.resolve("evaluation.json")).exists();
        assertThat(report.path("formatVersion").asText())
                .isEqualTo("funasr-candidate-preparation-v1");
        assertThat(report.path("sampleCount").asInt()).isEqualTo(1);
        assertThat(report.path("microCer").asDouble()).isZero();
        assertThat(report.path("sentenceAccuracy").asDouble()).isEqualTo(1.0d);
        assertThat(report.path("thresholdApplied").asBoolean()).isFalse();
        assertThat(report.path("candidateReady").asBoolean()).isFalse();
        assertThat(report.path("candidateRegistrationAllowed").asBoolean()).isFalse();
        assertThat(report.path("modelSigningAllowed").asBoolean()).isFalse();
        assertThat(report.path("runtimeReplacementAllowed").asBoolean()).isFalse();
        assertThat(report.path("decisionReason").asText())
                .isEqualTo("FORMAL_THRESHOLDS_NOT_CONFIGURED");
    }

    @Test
    void shouldValidateWithoutCreatingEvaluationOrPreparation() throws Exception {
        CandidateFixture fixture = createCandidateFixture();

        ProcessResult result = runCandidateScript(fixture, "validate-only", true);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(fixture.datasetDirectory().resolve("funasr/evaluations"))
                .doesNotExist();
        assertThat(fixture.datasetDirectory().resolve("funasr/candidate-preparations"))
                .doesNotExist();
    }

    @Test
    void shouldRejectHypothesisChangedAfterInferencePublication() throws Exception {
        CandidateFixture fixture = createCandidateFixture();
        Files.writeString(
                fixture.inferenceDirectory().resolve("hypotheses.txt"),
                "utt_000001\t错误文字",
                UTF_8);

        ProcessResult result = runCandidateScript(fixture, "tampered-hypothesis", false);

        assertThat(result.exitCode()).isNotZero();
        assertThat(fixture.datasetDirectory().resolve(
                "funasr/evaluations/tampered-hypothesis-evaluation"))
                .doesNotExist();
    }

    @Test
    void shouldRejectTrainingManifestChangedAfterInferencePublication() throws Exception {
        CandidateFixture fixture = createCandidateFixture();
        Path trainingManifest = fixture.datasetDirectory().resolve(
                "funasr/training-runs/local-run-01/training-run.json");
        Files.writeString(trainingManifest, "\n", UTF_8, java.nio.file.StandardOpenOption.APPEND);

        ProcessResult result = runCandidateScript(fixture, "tampered-training", false);

        assertThat(result.exitCode()).isNotZero();
        assertThat(fixture.datasetDirectory().resolve(
                "funasr/evaluations/tampered-training-evaluation"))
                .doesNotExist();
    }

    @Test
    void shouldReuseExistingEvaluationWhenItMatchesBoundInference() throws Exception {
        CandidateFixture fixture = createCandidateFixture();
        ProcessResult evaluation = runEvaluation(
                fixture,
                fixture.inferenceDirectory().resolve("hypotheses.txt"),
                "reuse-evaluation");
        Path evaluationReport = fixture.datasetDirectory().resolve(
                "funasr/evaluations/reuse-evaluation/evaluation.json");
        String originalEvaluation = Files.readString(evaluationReport, UTF_8);

        ProcessResult preparation = runCandidateScript(fixture, "reuse", false);

        assertThat(evaluation.exitCode()).as(evaluation.output()).isZero();
        assertThat(preparation.exitCode()).as(preparation.output()).isZero();
        assertThat(Files.readString(evaluationReport, UTF_8))
                .isEqualTo(originalEvaluation);
        assertThat(fixture.datasetDirectory().resolve(
                "funasr/candidate-preparations/reuse/candidate-preparation.json"))
                .exists();
    }

    @Test
    void shouldRejectExistingEvaluationBoundToDifferentHypotheses() throws Exception {
        CandidateFixture fixture = createCandidateFixture();
        Path differentHypothesis = temporaryDirectory.resolve("different-hypothesis.txt");
        Files.writeString(differentHypothesis, "utt_000001\t完全不同", UTF_8);
        ProcessResult evaluation = runEvaluation(
                fixture,
                differentHypothesis,
                "mismatch-evaluation");

        ProcessResult preparation = runCandidateScript(fixture, "mismatch", false);

        assertThat(evaluation.exitCode()).as(evaluation.output()).isZero();
        assertThat(preparation.exitCode()).isNotZero();
        assertThat(fixture.datasetDirectory().resolve(
                "funasr/candidate-preparations/mismatch"))
                .doesNotExist();
    }

    @Test
    void shouldNeverOverwritePublishedCandidatePreparation() throws Exception {
        CandidateFixture fixture = createCandidateFixture();
        ProcessResult first = runCandidateScript(fixture, "immutable", false);
        Path reportPath = fixture.datasetDirectory().resolve(
                "funasr/candidate-preparations/immutable/candidate-preparation.json");
        String originalReport = Files.readString(reportPath, UTF_8);

        ProcessResult second = runCandidateScript(fixture, "immutable", false);

        assertThat(first.exitCode()).as(first.output()).isZero();
        assertThat(second.exitCode()).isNotZero();
        assertThat(Files.readString(reportPath, UTF_8)).isEqualTo(originalReport);
    }

    @Test
    void shouldKeepMeasuredOnlyBoundaryBomAndWindowsLauncher() throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "prepare-funasr-candidate-report.cmd"),
                UTF_8);

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(script)
                .contains(
                        "evaluate-funasr-asr.ps1",
                        "-HypothesisFile $hypothesisPath",
                        "funasr-candidate-preparation-v1",
                        "thresholdApplied = $false",
                        "candidateReady = $false",
                        "candidateRegistrationAllowed = $false",
                        "modelSigningAllowed = $false",
                        "runtimeReplacementAllowed = $false",
                        "FORMAL_THRESHOLDS_NOT_CONFIGURED",
                        "候选准备期间输入发生变化",
                        "[IO.Directory]::Move($stagingDirectory, $finalPreparationDirectory)")
                .doesNotContain(
                        "pip install",
                        "conda install",
                        "git clone",
                        "Invoke-WebRequest",
                        "candidateReady = $true",
                        "candidateRegistrationAllowed = $true",
                        "modelSigningAllowed = $true",
                        "runtimeReplacementAllowed = $true");
        assertThat(command)
                .contains(
                        "prepare-funasr-candidate-report.ps1",
                        "%*",
                        "candidateReportExitCode");
    }

    private ProcessResult runCandidateScript(
            CandidateFixture fixture,
            String preparationName,
            boolean validateOnly) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(windowsPowerShell().toString());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(SCRIPT_PATH.toAbsolutePath().toString());
        command.add("-DatasetDirectory");
        command.add(fixture.datasetDirectory().toString());
        command.add("-InferenceDirectory");
        command.add(fixture.inferenceDirectory().toString());
        command.add("-PreparationName");
        command.add(preparationName);
        if (validateOnly) {
            command.add("-ValidateOnly");
        }
        return run(command);
    }

    private ProcessResult runEvaluation(
            CandidateFixture fixture,
            Path hypothesisFile,
            String evaluationName) throws Exception {
        return run(java.util.List.of(
                windowsPowerShell().toString(),
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                EVALUATION_SCRIPT_PATH.toAbsolutePath().toString(),
                "-DatasetDirectory",
                fixture.datasetDirectory().toString(),
                "-HypothesisFile",
                hypothesisFile.toString(),
                "-EvaluationName",
                evaluationName));
    }

    private ProcessResult run(java.util.List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    private CandidateFixture createCandidateFixture() throws Exception {
        Path datasetDirectory = temporaryDirectory.resolve(
                "候选准备 fixture with spaces/dataset").toAbsolutePath();
        Path funAsrDirectory = Files.createDirectories(datasetDirectory.resolve("funasr"));
        Path jsonlDirectory = Files.createDirectories(funAsrDirectory.resolve("jsonl"));
        Path trainingDirectory = Files.createDirectories(
                funAsrDirectory.resolve("training-runs/local-run-01"));
        Path inferenceDirectory = Files.createDirectories(
                funAsrDirectory.resolve("inferences/local-inference-01"));

        Path referenceText = funAsrDirectory.resolve("val_text.txt");
        Files.writeString(referenceText, "utt_000001\t小友\n", UTF_8);
        Path datasetManifest = datasetDirectory.resolve("dataset.json");
        Files.writeString(
                datasetManifest,
                OBJECT_MAPPER.writeValueAsString(Map.of(
                        "formatVersion", "voice-training-input-v3",
                        "trainingFormatVersion", "funasr-scp-v2",
                        "validationSampleCount", 1,
                        "funAsrValidationText", "funasr/val_text.txt")),
                UTF_8);
        Path validationJsonl = jsonlDirectory.resolve("val.jsonl");
        Files.writeString(validationJsonl, "{\"key\":\"utt_000001\"}\n", UTF_8);
        Path conversionManifest = jsonlDirectory.resolve("conversion.json");
        Files.writeString(
                conversionManifest,
                OBJECT_MAPPER.writeValueAsString(Map.of(
                        "formatVersion", "funasr-jsonl-v1",
                        "datasetJsonSha256", sha256(datasetManifest),
                        "validationJsonl", "val.jsonl",
                        "validationJsonlSha256", sha256(validationJsonl),
                        "validationSampleCount", 1)),
                UTF_8);

        Path trainedModel = trainingDirectory.resolve("model.pt");
        Path trainedConfiguration = trainingDirectory.resolve("configuration.json");
        Files.writeString(trainedModel, "trained-model", UTF_8);
        Files.writeString(trainedConfiguration, "{\"model\":\"trained\"}", UTF_8);
        Path trainingManifest = trainingDirectory.resolve("training-run.json");
        Files.writeString(
                trainingManifest,
                OBJECT_MAPPER.writeValueAsString(Map.of(
                        "formatVersion", "funasr-training-run-v1",
                        "runName", "local-run-01",
                        "datasetJsonSha256", sha256(datasetManifest),
                        "conversionJsonSha256", sha256(conversionManifest),
                        "validationJsonlSha256", sha256(validationJsonl),
                        "validationSampleCount", 1,
                        "trainedModelSha256", sha256(trainedModel),
                        "trainedConfigurationSha256", sha256(trainedConfiguration),
                        "candidateRegistered", false,
                        "runtimeReplaced", false)),
                UTF_8);

        Path hypothesis = inferenceDirectory.resolve("hypotheses.txt");
        Files.writeString(hypothesis, "utt_000001\t小友", UTF_8);
        Path inferenceManifest = inferenceDirectory.resolve("inference-run.json");
        Files.writeString(
                inferenceManifest,
                OBJECT_MAPPER.writeValueAsString(Map.ofEntries(
                        Map.entry("formatVersion", "funasr-offline-inference-v1"),
                        Map.entry("inferenceName", "local-inference-01"),
                        Map.entry("trainingRunName", "local-run-01"),
                        Map.entry("datasetJsonSha256", sha256(datasetManifest)),
                        Map.entry("conversionJsonSha256", sha256(conversionManifest)),
                        Map.entry("validationJsonlSha256", sha256(validationJsonl)),
                        Map.entry("hypothesisSha256", sha256(hypothesis)),
                        Map.entry("trainingRunJsonSha256", sha256(trainingManifest)),
                        Map.entry("trainedModelSha256", sha256(trainedModel)),
                        Map.entry("trainedConfigurationSha256",
                                sha256(trainedConfiguration)),
                        Map.entry("sampleCount", 1),
                        Map.entry("evaluationCompleted", false),
                        Map.entry("candidateRegistered", false),
                        Map.entry("runtimeReplaced", false))),
                UTF_8);
        return new CandidateFixture(datasetDirectory, inferenceDirectory);
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private static Path windowsPowerShell() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").startsWith("Windows"),
                "脚本契约仅在 Windows PowerShell 5.1 执行");
        Path powerShell = Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe");
        Assumptions.assumeTrue(Files.isRegularFile(powerShell));
        return powerShell;
    }

    private record CandidateFixture(
            Path datasetDirectory,
            Path inferenceDirectory) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
