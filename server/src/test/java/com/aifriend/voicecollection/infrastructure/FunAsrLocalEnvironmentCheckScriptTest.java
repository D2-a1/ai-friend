package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunAsrLocalEnvironmentCheckScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "check-funasr-local-environment.ps1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldPassReadOnlyProbeWithCompleteLocalEnvironment() throws Exception {
        EnvironmentFixture fixture = createEnvironmentFixture(true);
        Path controlledExecutable = createControlledExecutable("controlled.cmd", 0);
        List<String> pathsBefore = listRelativePaths(fixture.rootDirectory());

        ProcessResult result = runScript(
                fixture,
                controlledExecutable,
                controlledExecutable,
                "cpu");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("[FUNASR_ENV_OK]");
        assertThat(listRelativePaths(fixture.rootDirectory())).isEqualTo(pathsBefore);
    }

    @Test
    void shouldRejectMissingInferenceEntryBeforeRunningProbes() throws Exception {
        EnvironmentFixture fixture = createEnvironmentFixture(false);
        Path missingExecutable = temporaryDirectory.resolve("missing.exe");

        ProcessResult result = runScript(
                fixture,
                missingExecutable,
                missingExecutable,
                "cpu");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("[FUNASR_ENV_REQUIRED_FILE_INVALID]");
    }

    @Test
    void shouldFailClosedWhenPythonProbeFails() throws Exception {
        EnvironmentFixture fixture = createEnvironmentFixture(true);
        Path failingPython = createControlledExecutable("failing-python.cmd", 7);
        Path controlledTorchRun = createControlledExecutable("torchrun.cmd", 0);

        ProcessResult result = runScript(
                fixture,
                failingPython,
                controlledTorchRun,
                "cpu");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output())
                .contains("[FUNASR_ENV_PROBE_FAILED]")
                .contains("exitCode=7");
    }

    @Test
    void shouldKeepReadOnlyEnvironmentContractBomAndWindowsLauncher()
            throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "check-funasr-local-environment.cmd"), UTF_8);

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(script)
                .contains(
                        "funasr/bin/train_ds.py",
                        "funasr/bin/inference.py",
                        "model.pt",
                        "configuration.json",
                        "config.yaml",
                        "import funasr,torch,sys",
                        "torch.distributed.is_available()",
                        "torch.cuda.is_available()",
                        "torch.cuda.device_count()",
                        "@(" + "\"-B\", \"-c\"",
                        "--help",
                        "本次只读取本机环境")
                .doesNotContain(
                        "pip install",
                        "conda install",
                        "git clone",
                        "Invoke-WebRequest",
                        "Start-BitsTransfer",
                        "New-Item",
                        "CreateDirectory",
                        "WriteAllText",
                        "Remove-Item");
        assertThat(command)
                .contains(
                        "check-funasr-local-environment.ps1",
                        "%*",
                        "environmentCheckExitCode");
    }

    private ProcessResult runScript(
            EnvironmentFixture fixture,
            Path pythonExecutable,
            Path torchRunExecutable,
            String device) throws Exception {
        var command = new ArrayList<String>();
        command.add(windowsPowerShell().toString());
        command.add("-NoLogo");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(SCRIPT_PATH.toAbsolutePath().toString());
        command.add("-FunAsrRepositoryDirectory");
        command.add(fixture.funAsrRepositoryDirectory().toString());
        command.add("-InitialModelDirectory");
        command.add(fixture.initialModelDirectory().toString());
        command.add("-PythonExecutable");
        command.add(pythonExecutable.toString());
        command.add("-TorchRunExecutable");
        command.add(torchRunExecutable.toString());
        command.add("-Device");
        command.add(device);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output);
    }

    private EnvironmentFixture createEnvironmentFixture(boolean includeInference)
            throws Exception {
        Path rootDirectory = temporaryDirectory.resolve("环境 fixture with spaces");
        Path repositoryDirectory = rootDirectory.resolve("FunASR source");
        Path binDirectory = repositoryDirectory.resolve("funasr/bin");
        Path initialModelDirectory = rootDirectory.resolve("initial model");
        Files.createDirectories(binDirectory);
        Files.createDirectories(initialModelDirectory);
        Files.writeString(binDirectory.resolve("train_ds.py"), "# train\n", UTF_8);
        if (includeInference) {
            Files.writeString(
                    binDirectory.resolve("inference.py"), "# inference\n", UTF_8);
        }
        Files.writeString(initialModelDirectory.resolve("model.pt"), "model", UTF_8);
        Files.writeString(
                initialModelDirectory.resolve("configuration.json"),
                "{\"model\":\"controlled\"}",
                UTF_8);
        return new EnvironmentFixture(
                rootDirectory,
                repositoryDirectory,
                initialModelDirectory);
    }

    private Path createControlledExecutable(String name, int exitCode) throws Exception {
        Path command = temporaryDirectory.resolve(name);
        Files.writeString(
                command,
                "@echo off\r\nexit /b " + exitCode + "\r\n",
                UTF_8);
        return command;
    }

    private static List<String> listRelativePaths(Path rootDirectory) throws Exception {
        try (var paths = Files.walk(rootDirectory)) {
            return paths
                    .map(rootDirectory::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
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

    private record EnvironmentFixture(
            Path rootDirectory,
            Path funAsrRepositoryDirectory,
            Path initialModelDirectory) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
