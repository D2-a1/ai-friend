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

class FunAsrTrainingRunnerScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "run-funasr-training.ps1");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldValidateBoundInputsWithoutStartingTraining() throws Exception {
        TrainingFixture fixture = createTrainingFixture();

        ProcessResult result = runScript(
                fixture,
                "validate-only",
                temporaryDirectory.resolve("missing-torchrun.exe"),
                true);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(fixture.datasetDirectory().resolve("funasr/training-runs"))
                .doesNotExist();
    }

    @Test
    void shouldPublishVerifiedTrainingOutputFromControlledTorchRun()
            throws Exception {
        TrainingFixture fixture = createTrainingFixture();
        Path controlledTorchRun = createControlledTorchRun(false);

        ProcessResult result = runScript(
                fixture,
                "local-run-01",
                controlledTorchRun,
                false);

        Path resultDirectory = fixture.datasetDirectory()
                .resolve("funasr/training-runs/local-run-01");
        JsonNode manifest = OBJECT_MAPPER.readTree(
                resultDirectory.resolve("training-run.json").toFile());
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(resultDirectory.resolve("model.pt")).exists();
        assertThat(resultDirectory.resolve("configuration.json")).exists();
        assertThat(manifest.path("formatVersion").asText())
                .isEqualTo("funasr-training-run-v1");
        assertThat(manifest.path("processCount").asInt()).isEqualTo(1);
        assertThat(manifest.path("candidateRegistered").asBoolean()).isFalse();
        assertThat(manifest.path("runtimeReplaced").asBoolean()).isFalse();
        assertThat(manifest.path("trainedModelSha256").asText())
                .hasSize(64);
        assertThat(resultDirectory.resolve("arguments.txt")).exists();
        try (var paths = Files.list(resultDirectory.getParent())) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".training-staging-"));
        }
    }

    @Test
    void shouldRejectTamperedJsonlBeforeStartingTraining() throws Exception {
        TrainingFixture fixture = createTrainingFixture();
        Files.writeString(
                fixture.datasetDirectory().resolve("funasr/jsonl/train.jsonl"),
                "篡改",
                UTF_8);

        ProcessResult result = runScript(
                fixture,
                "tampered-run",
                temporaryDirectory.resolve("missing-torchrun.exe"),
                true);

        assertThat(result.exitCode()).isNotZero();
        assertThat(fixture.datasetDirectory()
                .resolve("funasr/training-runs/tampered-run"))
                .doesNotExist();
    }

    @Test
    void shouldCleanOwnedStagingWhenTrainingFails() throws Exception {
        TrainingFixture fixture = createTrainingFixture();
        Path failingTorchRun = createControlledTorchRun(true);

        ProcessResult result = runScript(
                fixture,
                "failed-run",
                failingTorchRun,
                false);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("exitCode=7");
        Path runsDirectory = fixture.datasetDirectory().resolve("funasr/training-runs");
        assertThat(runsDirectory.resolve("failed-run")).doesNotExist();
        try (var paths = Files.list(runsDirectory)) {
            assertThat(paths).isEmpty();
        }
    }

    @Test
    void shouldKeepExistingPublishedRunWhenSameNameIsRetried() throws Exception {
        TrainingFixture fixture = createTrainingFixture();
        Path controlledTorchRun = createControlledTorchRun(false);
        ProcessResult first = runScript(
                fixture,
                "immutable-run",
                controlledTorchRun,
                false);
        Path manifestPath = fixture.datasetDirectory()
                .resolve("funasr/training-runs/immutable-run/training-run.json");
        String originalManifest = Files.readString(manifestPath, UTF_8);

        ProcessResult second = runScript(
                fixture,
                "immutable-run",
                controlledTorchRun,
                false);

        assertThat(first.exitCode()).as(first.output()).isZero();
        assertThat(second.exitCode()).isNotZero();
        assertThat(Files.readString(manifestPath, UTF_8))
                .isEqualTo(originalManifest);
    }

    @Test
    void shouldKeepOfficialTrainingContractAndWindowsLaunchers() throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "run-funasr-training.cmd"), UTF_8);

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(script)
                .contains(
                        "funasr/bin/train_ds.py",
                        "--standalone",
                        "--nproc_per_node=1",
                        "++train_data_set_list=",
                        "++valid_data_set_list=",
                        "++dataset=AudioDataset",
                        "++train_conf.resume=false",
                        "++train_conf.use_deepspeed=false",
                        "++output_dir=",
                        "funasr-training-run-v1",
                        "-Description \"训练模型文件\"",
                        "-Description \"训练配置文件\"",
                        "candidateRegistered = $false",
                        "runtimeReplaced = $false")
                .doesNotContain(
                        "pip install",
                        "conda install",
                        "git clone",
                        "Invoke-WebRequest",
                        "Start-BitsTransfer");
        assertThat(command)
                .contains(
                        "run-funasr-training.ps1",
                        "%*",
                        "trainingExitCode");
    }

    private ProcessResult runScript(
            TrainingFixture fixture,
            String runName,
            Path torchRunExecutable,
            boolean validateOnly) throws Exception {
        Path windowsPowerShell = windowsPowerShell();
        var command = new ArrayList<String>();
        command.add(windowsPowerShell.toString());
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
        command.add("-InitialModelDirectory");
        command.add(fixture.initialModelDirectory().toString());
        command.add("-RunName");
        command.add(runName);
        command.add("-TorchRunExecutable");
        command.add(torchRunExecutable.toString());
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

    private TrainingFixture createTrainingFixture() throws Exception {
        Path fixtureRoot = temporaryDirectory.resolve("训练 fixture with spaces");
        Path datasetDirectory = fixtureRoot.resolve("dataset");
        Path jsonlDirectory = datasetDirectory.resolve("funasr/jsonl");
        Path funAsrRepositoryDirectory = fixtureRoot.resolve("FunASR source");
        Path trainEntry = funAsrRepositoryDirectory.resolve("funasr/bin/train_ds.py");
        Path initialModelDirectory = fixtureRoot.resolve("initial model");
        Files.createDirectories(jsonlDirectory);
        Files.createDirectories(trainEntry.getParent());
        Files.createDirectories(initialModelDirectory);

        String datasetJson = "{\"formatVersion\":\"voice-training-input-v3\","
                + "\"trainingFormatVersion\":\"funasr-scp-v2\"}";
        Path datasetManifest = datasetDirectory.resolve("dataset.json");
        Files.writeString(datasetManifest, datasetJson, UTF_8);
        String trainJsonl = "{\"key\":\"utt_000000\",\"source\":"
                + "\"audio/000000.wav\",\"source_len\":10,"
                + "\"target\":\"你好\",\"target_len\":2}\n";
        String validationJsonl = "{\"key\":\"utt_000001\",\"source\":"
                + "\"audio/000001.wav\",\"source_len\":10,"
                + "\"target\":\"小友\",\"target_len\":2}\n";
        Path trainJsonlPath = jsonlDirectory.resolve("train.jsonl");
        Path validationJsonlPath = jsonlDirectory.resolve("val.jsonl");
        Files.writeString(trainJsonlPath, trainJsonl, UTF_8);
        Files.writeString(validationJsonlPath, validationJsonl, UTF_8);
        String conversionJson = "{"
                + "\"formatVersion\":\"funasr-jsonl-v1\","
                + "\"datasetJsonSha256\":\"" + sha256(datasetManifest) + "\","
                + "\"trainJsonlSha256\":\"" + sha256(trainJsonlPath) + "\","
                + "\"validationJsonlSha256\":\""
                + sha256(validationJsonlPath) + "\","
                + "\"trainSampleCount\":1,"
                + "\"validationSampleCount\":1}";
        Files.writeString(jsonlDirectory.resolve("conversion.json"), conversionJson, UTF_8);
        Files.writeString(trainEntry, "# controlled train entry\n", UTF_8);
        Files.writeString(initialModelDirectory.resolve("model.pt"), "initial", UTF_8);
        Files.writeString(
                initialModelDirectory.resolve("configuration.json"),
                "{\"model\":\"controlled\"}",
                UTF_8);
        return new TrainingFixture(
                datasetDirectory,
                funAsrRepositoryDirectory,
                initialModelDirectory);
    }

    private Path createControlledTorchRun(boolean fail) throws Exception {
        Path command = temporaryDirectory.resolve(
                fail ? "failing-torchrun.cmd" : "controlled-torchrun.cmd");
        String content;
        if (fail) {
            content = "@echo off\r\nexit /b 7\r\n";
        } else {
            content = "@echo off\r\n"
                    + "setlocal EnableDelayedExpansion\r\n"
                    + "set output=\r\n"
                    + "for %%A in (%*) do (\r\n"
                    + "  set arg=%%~A\r\n"
                    + "  if \"!arg:~0,13!\"==\"++output_dir=\" set output=!arg:~13!\r\n"
                    + ")\r\n"
                    + "if not defined output exit /b 6\r\n"
                    + ">\"!output!\\model.pt\" echo trained\r\n"
                    + ">\"!output!\\configuration.json\" echo {\"model\":\"trained\"}\r\n"
                    + ">\"!output!\\arguments.txt\" echo %*\r\n"
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

    private record TrainingFixture(
            Path datasetDirectory,
            Path funAsrRepositoryDirectory,
            Path initialModelDirectory) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
