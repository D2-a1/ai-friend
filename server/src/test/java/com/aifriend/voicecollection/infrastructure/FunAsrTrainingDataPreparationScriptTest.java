package com.aifriend.voicecollection.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FunAsrTrainingDataPreparationScriptTest {

    private static final Path SCRIPT_PATH =
            Path.of("scripts", "prepare-funasr-training-data.ps1");

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldValidateSyntheticDatasetWithoutCallingPython() throws Exception {
        Path windowsPowerShell = windowsPowerShell();
        Path datasetDirectory = createSyntheticDataset();

        Process process = new ProcessBuilder(
                windowsPowerShell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                SCRIPT_PATH.toAbsolutePath().toString(),
                "-DatasetDirectory",
                datasetDirectory.toString(),
                "-ValidateOnly")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();

        assertThat(exitCode).as(output).isZero();
        assertThat(output).contains("FunASR", "2", "1");
        assertThat(datasetDirectory.resolve("funasr/jsonl")).doesNotExist();
    }

    @Test
    void shouldPublishBothValidatedJsonlFilesFromControlledConverter()
            throws Exception {
        Path windowsPowerShell = windowsPowerShell();
        Path datasetDirectory = createSyntheticDataset();
        Path converter = createControlledConverter();

        Process process = new ProcessBuilder(
                windowsPowerShell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                SCRIPT_PATH.toAbsolutePath().toString(),
                "-DatasetDirectory",
                datasetDirectory.toString(),
                "-PythonExecutable",
                converter.toString(),
                "-ConversionTimeoutSeconds",
                "30")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        int exitCode = process.waitFor();

        Path jsonlDirectory = datasetDirectory.resolve("funasr/jsonl");
        assertThat(exitCode).as(output).isZero();
        assertThat(jsonlDirectory.resolve("train.jsonl")).exists();
        assertThat(jsonlDirectory.resolve("val.jsonl")).exists();
        assertThat(jsonlDirectory.resolve("conversion.json")).exists();
        try (var paths = Files.list(datasetDirectory.resolve("funasr"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".jsonl-staging-"));
        }
    }

    @Test
    void shouldKeepOfficialConverterAndAtomicPublicationBoundary() throws Exception {
        String script = Files.readString(SCRIPT_PATH, UTF_8);

        assertThat(script)
                .contains(
                        "voice-training-input-v3",
                        "funasr-scp-v2",
                        "funasr.datasets.audio_datasets.scp2jsonl",
                        "++data_type_list=[source,target]",
                        "Test-JsonlOutput",
                        "ExpectedTranscripts",
                        "ConversionTimeoutSeconds",
                        "-WorkingDirectory $WorkingDirectory",
                        "[void]$process.Handle",
                        "WaitForExit",
                        "ReparsePoint",
                        "[IO.Directory]::Move($stagingDirectory, $finalDirectory)",
                        "funasr-jsonl-v1",
                        "datasetJsonSha256",
                        "trainJsonlSha256",
                        "validationJsonlSha256",
                        "Get-Sha256Hex",
                        "$ValidateOnly",
                        "$isDriveQualified",
                        "$isUncQualified")
                .doesNotContain(
                        "pip install",
                        "git clone",
                        "Invoke-WebRequest",
                        "Get-FileHash",
                        "IsPathFullyQualified");
    }

    @Test
    void shouldKeepUtf8BomAndForwardCommandArguments() throws Exception {
        byte[] scriptBytes = Files.readAllBytes(SCRIPT_PATH);
        String command = Files.readString(
                Path.of("scripts", "prepare-funasr-training-data.cmd"), UTF_8);

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(command)
                .contains(
                        "prepare-funasr-training-data.ps1",
                        "%*",
                        "exit /b %prepareExitCode%");
    }

    private Path createSyntheticDataset() throws Exception {
        Path datasetDirectory = Files.createDirectory(
                temporaryDirectory.resolve("training dataset").toAbsolutePath());
        Path funAsrDirectory = Files.createDirectories(
                datasetDirectory.resolve("funasr"));
        Path audioDirectory = Files.createDirectories(
                datasetDirectory.resolve("audio"));
        Files.write(audioDirectory.resolve("000000.wav"), new byte[] {1});
        Files.write(audioDirectory.resolve("000001.wav"), new byte[] {2});
        Files.writeString(
                funAsrDirectory.resolve("all_wav.scp"),
                "utt_000000\taudio/000000.wav\n"
                        + "utt_000001\taudio/000001.wav\n",
                UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("all_text.txt"),
                "utt_000000\tcall daughter\n"
                        + "utt_000001\tmessage son\n",
                UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("train_wav.scp"),
                "utt_000000\taudio/000000.wav\n",
                UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("train_text.txt"),
                "utt_000000\tcall daughter\n",
                UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("val_wav.scp"),
                "utt_000001\taudio/000001.wav\n",
                UTF_8);
        Files.writeString(
                funAsrDirectory.resolve("val_text.txt"),
                "utt_000001\tmessage son\n",
                UTF_8);
        Files.writeString(
                datasetDirectory.resolve("dataset.json"),
                """
                {
                  "formatVersion": "voice-training-input-v3",
                  "trainingFormatVersion": "funasr-scp-v2",
                  "sampleCount": 2,
                  "trainSampleCount": 1,
                  "validationSampleCount": 1,
                  "funAsrWavScp": "funasr/all_wav.scp",
                  "funAsrText": "funasr/all_text.txt",
                  "funAsrTrainWavScp": "funasr/train_wav.scp",
                  "funAsrTrainText": "funasr/train_text.txt",
                  "funAsrValidationWavScp": "funasr/val_wav.scp",
                  "funAsrValidationText": "funasr/val_text.txt",
                  "jsonlGenerationRequired": true
                }
                """,
                UTF_8);
        return datasetDirectory;
    }

    private Path createControlledConverter() throws Exception {
        Path converter = temporaryDirectory.resolve("controlled-converter.cmd");
        Files.writeString(
                converter,
                """
                @echo off
                setlocal EnableDelayedExpansion
                set "allArguments=%*"
                set "outputTail=!allArguments:*++jsonl_file_out=!"
                if "!outputTail!"=="!allArguments!" exit /b 4
                if "!outputTail:~0,1!"=="=" set "outputTail=!outputTail:~1!"
                for /f "tokens=1" %%A in ("!outputTail!") do set "outputFile=%%~A"
                echo !outputFile! | findstr /i "train.jsonl" >nul
                if not errorlevel 1 (
                    >"!outputFile!" echo {"key":"utt_000000","source":"audio/000000.wav","source_len":100,"target":"call daughter","target_len":2}
                ) else (
                    >"!outputFile!" echo {"key":"utt_000001","source":"audio/000001.wav","source_len":100,"target":"message son","target_len":2}
                )
                exit /b 0
                """,
                UTF_8);
        return converter.toAbsolutePath();
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
