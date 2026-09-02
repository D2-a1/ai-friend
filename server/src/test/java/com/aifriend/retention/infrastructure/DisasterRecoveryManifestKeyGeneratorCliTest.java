package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisasterRecoveryManifestKeyGeneratorCliTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void shouldGenerateCompatibleEd25519KeyFilesOutsideRepository() throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path outputDirectory = Files.createDirectory(temporaryRoot.resolve("keys"));

        DisasterRecoveryManifestKeyGeneratorCli.GeneratedKeyFiles generated =
                DisasterRecoveryManifestKeyGeneratorCli.generate(
                        repositoryRoot, outputDirectory);

        byte[] privateKeyBytes = Files.readAllBytes(generated.privateKeyPath());
        byte[] publicKeyBytes = Files.readAllBytes(generated.publicKeyPath());
        var keyFactory = KeyFactory.getInstance("Ed25519");
        var privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        var publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update("manifest".getBytes(UTF_8));
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update("manifest".getBytes(UTF_8));

        assertThat(verifier.verify(signature)).isTrue();
        assertThat(generated.privateKeyPath().getFileName().toString())
                .isEqualTo(DisasterRecoveryManifestKeyGeneratorCli.PRIVATE_KEY_FILE_NAME);
        assertThat(generated.publicKeyPath().getFileName().toString())
                .isEqualTo(DisasterRecoveryManifestKeyGeneratorCli.PUBLIC_KEY_FILE_NAME);
        assertThat(generated.publicKeySha256()).matches("[0-9A-F]{64}");
    }

    @Test
    void shouldRejectRepositoryDirectoryAndNonEmptyOutput() throws Exception {
        Path repositoryRoot = Files.createDirectory(temporaryRoot.resolve("repository"));
        Path insideRepository = Files.createDirectory(repositoryRoot.resolve("keys"));
        Path nonEmptyOutput = Files.createDirectory(temporaryRoot.resolve("existing-keys"));
        Files.writeString(nonEmptyOutput.resolve("existing.txt"), "existing", UTF_8);

        assertThatThrownBy(() -> DisasterRecoveryManifestKeyGeneratorCli.generate(
                repositoryRoot, insideRepository))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仓库之外");
        assertThatThrownBy(() -> DisasterRecoveryManifestKeyGeneratorCli.generate(
                repositoryRoot, nonEmptyOutput))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("全新空目录");
    }

    @Test
    void shouldRequireAbsolutePathsOnlyFromProcessEnvironment() {
        Map<String, String> environment = Map.of(
                "AI_FRIEND_DR_KEY_REPOSITORY_ROOT", "relative-repository",
                "AI_FRIEND_DR_KEY_OUTPUT_DIRECTORY", temporaryRoot.toAbsolutePath().toString());

        assertThatThrownBy(() -> DisasterRecoveryManifestKeyGeneratorCli.options(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_FRIEND_DR_KEY_REPOSITORY_ROOT");
    }

    @Test
    void shouldCompileBeforeCreatingRestrictedDirectoryAndExecutingGenerator() throws Exception {
        Path scriptPath = Path.of(
                "scripts", "generate-disaster-recovery-manifest-key.ps1");
        String script = Files.readString(scriptPath, UTF_8);

        int compileIndex = script.indexOf("-DskipTests compile");
        int createDirectoryIndex = script.indexOf("CreateDirectory");
        int executionIndex = script.indexOf("& java.exe -cp $classesDirectory");

        assertThat(compileIndex).isNotNegative();
        assertThat(createDirectoryIndex).isGreaterThan(compileIndex);
        assertThat(executionIndex).isGreaterThan(createDirectoryIndex);
        assertThat(script).contains(
                "/inheritance:r",
                "$isDriveQualified",
                "$isUncQualified",
                "AI_FRIEND_DR_KEY_REPOSITORY_ROOT",
                "AI_FRIEND_DR_KEY_OUTPUT_DIRECTORY",
                "Remove-Item -LiteralPath \"Env:$environmentName\"");
        assertThat(script).doesNotContain("IsPathFullyQualified");
        assertThat(script).doesNotContain("exec-maven-plugin");
    }

    @Test
    void shouldKeepUtf8BomForWindowsPowerShellChineseTextCompatibility() throws Exception {
        byte[] scriptBytes = Files.readAllBytes(Path.of(
                "scripts", "generate-disaster-recovery-manifest-key.ps1"));

        assertThat(Arrays.copyOf(scriptBytes, 3))
                .containsExactly((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    }
}
