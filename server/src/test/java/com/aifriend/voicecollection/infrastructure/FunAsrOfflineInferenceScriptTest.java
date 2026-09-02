package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunAsrOfflineInferenceScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "run-funasr-inference.ps1");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldValidateBoundInputsWithoutStartingInference() throws Exception {
        InferenceFixture fixture = createInferenceFixture();

        ProcessResult processResult = runScript(
                fixture,
                "validate-only",
                temporaryDirectory.resolve("missing-python.exe"),
                true);

        assertThat(processResult.exitCode()).as(processResult.output()).isZero();
        assertThat(fixture.datasetDirectory().resolve("funasr/inferences"))
                .doesNotExist();
    }

    @Test
    void shouldPublishCanonicalHypothesesFromControlledOfficialOutput()
            throws Exception {
        InferenceFixture fixture = createInferenceFixture();
        Path controlledPython = createControlledPython(ControlledMode.SUCCESS);

        ProcessResult processResult = runScript(
                fixture,
                "local-inference-01",
                controlledPython,
                false);

        Path resultDirectory = fixture.datasetDirectory()
                .resolve("funasr/inferences/local-inference-01");
        JsonNode manifest = OBJECT_MAPPER.readTree(
                resultDirectory.resolve("inference-run.json").toFile());
        assertThat(processResult.exitCode()).as(processResult.output()).isZero();
        assertThat(Files.readString(resultDirectory.resolve("hypotheses.txt"), UTF_8))
                .isEqualTo("utt_000001\t小友");
        assertThat(resultDirectory.resolve("official-output")).doesNotExist();
        assertThat(manifest.path("formatVersion").asText())
                .isEqualTo("funasr-offline-inference-v1");
        assertThat(manifest.path("sampleCount").asInt()).isEqualTo(1);
        assertThat(manifest.path("evaluationCompleted").asBoolean()).isFalse();
        assertThat(manifest.path("candidateRegistered").asBoolean()).isFalse();
        assertThat(manifest.path("runtimeReplaced").asBoolean()).isFalse();
        assertThat(manifest.path("validationAudioSetSha256").asText()).hasSize(64);
        try (var paths = Files.list(resultDirectory.getParent())) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".inference-staging-"));
        }
    }

    @Test
    void shouldRejectTamperedTrainingModelBeforeStartingInference()
            throws Exception {
        InferenceFixture fixture = createInferenceFixture();
        Files.writeString(
                fixture.trainingRunDirectory().resolve("model.pt"),
                "tampered",
                UTF_8);

        ProcessResult processResult = runScript(
                fixture,
                "tampered-model",
                temporaryDirectory.resolve("missing-python.exe"),
                true);

        assertThat(processResult.exitCode()).isNotZero();
        assertThat(fixture.datasetDirectory()
                .resolve("funasr/inferences/tampered-model"))
                .doesNotExist();
    }

    @Test
    void shouldCleanOwnedStagingWhenInferenceProcessFails() throws Exception {
        InferenceFixture fixture = createInferenceFixture();
        Path failingPython = createControlledPython(ControlledMode.FAIL);

        ProcessResult processResult = runScript(
                fixture,
                "failed-inference",
                failingPython,
                false);

        assertThat(processResult.exitCode()).isNotZero();
        assertThat(processResult.output()).contains("exitCode=7");
        Path inferenceDirectory = fixture.datasetDirectory().resolve("funasr/inferences");
        assertThat(inferenceDirectory.resolve("failed-inference")).doesNotExist();
        try (var paths = Files.list(inferenceDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void shouldRejectIncompleteOfficialOutputAndCleanStaging() throws Exception {
        InferenceFixture fixture = createInferenceFixture();
        Path incompletePython = createControlledPython(ControlledMode.INCOMPLETE);

        ProcessResult processResult = runScript(
                fixture,
                "incomplete-output",
                incompletePython,
                false);

        assertThat(processResult.exitCode()).isNotZero();
        Path inferenceDirectory = fixture.datasetDirectory().resolve("funasr/inferences");
        assertThat(inferenceDirectory.resolve("incomplete-output")).doesNotExist();
        try (var paths = Files.list(inferenceDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void shouldKeepExistingPublishedInferenceWhenSameNameIsRetried()
            throws Exception {
        InferenceFixture fixture = createInferenceFixture();
        Path controlledPython = createControlledPython(ControlledMode.SUCCESS);
        ProcessResult first = runScript(
                fixture,
                "immutable-inference",
                controlledPython,
                false);
        Path manifestPath = fixture.datasetDirectory().resolve(
                "funasr/inferences/immutable-inference/inference-run.json");
        String originalManifest = Files.readString(manifestPath, UTF_8);

        ProcessResult second = runScript(
                fixture,
                "immutable-inference",
                controlledPython,
                false);

        assertThat(first.exitCode()).as(first.output()).isZero();
        assertThat(second.exitCode()).isNotZero();
        assertThat(Files.readString(manifestPath, UTF_8))
                .isEqualTo(originalManifest);
    }

    @Test
    void shouldKeepOfficialInferenceContractAndWindowsLaunchers() throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "run-funasr-inference.cmd"), UTF_8);

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(script)
                .contains(
                        "funasr/bin/inference.py",
                        "funasr.bin.inference",
                        "++model=",
                        "++input=",
                        "++output_dir=",
                        "1best_recog/text",
                        "-Description \"FunASR 官方输出目录\"",
                        "-Description \"FunASR 一优识别目录\"",
                        "推理子进程越界创建了规范识别结果",
                        "推理子进程越界创建了结果清单",
                        "funasr-offline-inference-v1",
                        "evaluationCompleted = $false",
                        "candidateRegistered = $false",
                        "runtimeReplaced = $false",
                        "Remove-Item -LiteralPath $rawOutputDirectory")
                .doesNotContain(
                        "pip install",
                        "conda install",
                        "git clone",
                        "Invoke-WebRequest",
                        "Start-BitsTransfer");
        assertThat(command)
                .contains(
                        "run-funasr-inference.ps1",
                        "%*",
                        "inferenceExitCode");
    }

    private ProcessResult runScript(
            InferenceFixture fixture,
            String inferenceName,
            Path pythonExecutable,
            boolean validateOnly) throws Exception {
        var command = new ArrayList<String>();
        command.add(windowsPowerShell().toString());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(SCRIPT_PATH.toAbsolutePath().toString());
        command.add("-DatasetDirectory");
        command.add(fixture.datasetDirectory().toString());
        command.add("-FunAsrRepositoryDirectory");
        command.add(fixture.funAsrRepositoryDirectory().toString());
        command.add("-TrainingRunDirectory");
        command.add(fixture.trainingRunDirectory().toString());
        command.add("-InferenceName");
        command.add(inferenceName);
        command.add("-PythonExecutable");
        command.add(pythonExecutable.toString());
        if (validateOnly) {
            command.add("-ValidateOnly");
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    private InferenceFixture createInferenceFixture() throws Exception {
        Path fixtureRoot = temporaryDirectory.resolve("推理 fixture with spaces");
        Path datasetDirectory = fixtureRoot.resolve("dataset");
        Path funAsrDirectory = datasetDirectory.resolve("funasr");
        Path jsonlDirectory = funAsrDirectory.resolve("jsonl");
        Path audioDirectory = datasetDirectory.resolve("audio");
        Path funAsrRepositoryDirectory = fixtureRoot.resolve("FunASR source");
        Path inferenceEntry = funAsrRepositoryDirectory.resolve("funasr/bin/inference.py");
        Path trainingRunDirectory = funAsrDirectory.resolve("training-runs/local-run-01");
        Files.createDirectories(jsonlDirectory);
        Files.createDirectories(audioDirectory);
        Files.createDirectories(inferenceEntry.getParent());
        Files.createDirectories(trainingRunDirectory);

        Path trainAudio = audioDirectory.resolve("000000.wav");
        Path validationAudio = audioDirectory.resolve("000001.wav");
        Files.writeString(trainAudio, "train-audio", UTF_8);
        Files.writeString(validationAudio, "validation-audio", UTF_8);
        String datasetJson = "{"
                + "\"formatVersion\":\"voice-training-input-v3\","
                + "\"trainingFormatVersion\":\"funasr-scp-v2\","
                + "\"sampleCount\":2,"
                + "\"validationSampleCount\":1,"
                + "\"funAsrValidationWavScp\":\"funasr/val_wav.scp\"}";
        Path datasetManifest = datasetDirectory.resolve("dataset.json");
        Files.writeString(datasetManifest, datasetJson, UTF_8);
        String labels = "{\"memberOrder\":0,\"audioFile\":\"audio/000000.wav\","
                + "\"split\":\"TRAIN\",\"audioSha256\":\""
                + sha256(trainAudio) + "\"}\n"
                + "{\"memberOrder\":1,\"audioFile\":\"audio/000001.wav\","
                + "\"split\":\"VALIDATION\",\"audioSha256\":\""
                + sha256(validationAudio) + "\"}\n";
        Files.writeString(datasetDirectory.resolve("labels.jsonl"), labels, UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("val_wav.scp"),
                "utt_000001\taudio/000001.wav\n",
                UTF_8);
        Path validationJsonl = jsonlDirectory.resolve("val.jsonl");
        Files.writeString(
                validationJsonl,
                "{\"key\":\"utt_000001\",\"source\":\"audio/000001.wav\","
                        + "\"source_len\":10,\"target\":\"小友\",\"target_len\":2}\n",
                UTF_8);
        String conversionJson = "{"
                + "\"formatVersion\":\"funasr-jsonl-v1\","
                + "\"datasetJsonSha256\":\"" + sha256(datasetManifest) + "\","
                + "\"validationJsonlSha256\":\"" + sha256(validationJsonl) + "\","
                + "\"validationSampleCount\":1}";
        Path conversionManifest = jsonlDirectory.resolve("conversion.json");
        Files.writeString(conversionManifest, conversionJson, UTF_8);
        Files.writeString(inferenceEntry, "# controlled inference entry\n", UTF_8);
        Path trainedModel = trainingRunDirectory.resolve("model.pt");
        Path trainedConfiguration = trainingRunDirectory.resolve("configuration.json");
        Files.writeString(trainedModel, "trained", UTF_8);
        Files.writeString(
                trainedConfiguration,
                "{\"model\":\"trained\"}",
                UTF_8);
        String trainingRunJson = "{"
                + "\"formatVersion\":\"funasr-training-run-v1\","
                + "\"runName\":\"local-run-01\","
                + "\"datasetJsonSha256\":\"" + sha256(datasetManifest) + "\","
                + "\"conversionJsonSha256\":\"" + sha256(conversionManifest) + "\","
                + "\"validationJsonlSha256\":\"" + sha256(validationJsonl) + "\","
                + "\"validationSampleCount\":1,"
                + "\"trainedModelSha256\":\"" + sha256(trainedModel) + "\","
                + "\"trainedConfigurationSha256\":\""
                + sha256(trainedConfiguration) + "\","
                + "\"candidateRegistered\":false,"
                + "\"runtimeReplaced\":false}";
        Files.writeString(
                trainingRunDirectory.resolve("training-run.json"),
                trainingRunJson,
                UTF_8);
        return new InferenceFixture(
                datasetDirectory,
                funAsrRepositoryDirectory,
                trainingRunDirectory);
    }

    private Path createControlledPython(ControlledMode controlledMode) throws Exception {
        Path command = temporaryDirectory.resolve(
                "controlled-python-" + controlledMode.name().toLowerCase() + ".cmd");
        String content;
        if (controlledMode == ControlledMode.FAIL) {
            content = "@echo off\r\nexit /b 7\r\n";
        } else {
            String hypothesis = controlledMode == ControlledMode.SUCCESS
                    ? "utt_000001 小友"
                    : "utt_999999 不完整";
            content = "@echo off\r\n"
                    + "setlocal EnableDelayedExpansion\r\n"
                    + "set output=\r\n"
                    + "for %%A in (%*) do (\r\n"
                    + "  set arg=%%~A\r\n"
                    + "  if \"!arg:~0,13!\"==\"++output_dir=\" set output=!arg:~13!\r\n"
                    + ")\r\n"
                    + "if not defined output exit /b 6\r\n"
                    + "mkdir \"!output!\\1best_recog\"\r\n"
                    + ">\"!output!\\1best_recog\\text\" echo " + hypothesis + "\r\n"
                    + "exit /b 0\r\n";
        }
        Files.writeString(command, content, UTF_8);
        return command;
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

    private enum ControlledMode {
        SUCCESS,
        INCOMPLETE,
        FAIL
    }

    private record InferenceFixture(
            Path datasetDirectory,
            Path funAsrRepositoryDirectory,
            Path trainingRunDirectory) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
