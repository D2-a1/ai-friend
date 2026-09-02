package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DisasterRecoverySnapshotPublisherCliTest {

    private static final byte[] UTF_8_BOM = {
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF
    };

    @Test
    void shouldBuildValidatedOptionsOnlyFromProcessEnvironment() throws Exception {
        Map<String, String> environment = environment();

        TencentCosSnapshotPublisherOptions options =
                DisasterRecoverySnapshotPublisherCli.options(environment);

        assertThat(options.snapshotId()).isEqualTo("snapshot-20260828");
        assertThat(options.pageSize()).isEqualTo(100);
        assertThat(options.connectTimeout()).hasSeconds(2);
        assertThat(options.readTimeout()).hasSeconds(5);
    }

    @Test
    void shouldBuildReadOnlyPreflightOptionsWithoutSigningMaterial() {
        Map<String, String> environment = preflightEnvironment();

        TencentCosDisasterRecoveryPreflightOptions options =
                DisasterRecoverySnapshotPublisherCli.preflightOptions(environment);

        assertThat(DisasterRecoverySnapshotPublisherCli.preflightOnly(environment))
                .isTrue();
        assertThat(options.region()).isEqualTo("ap-shanghai");
        assertThat(options.connectTimeout()).hasSeconds(2);
        assertThat(options.readTimeout()).hasSeconds(5);
    }

    @Test
    void shouldRejectInvalidPreflightSwitch() {
        Map<String, String> environment = new HashMap<>();
        environment.put("AI_FRIEND_DR_PREFLIGHT_ONLY", "yes");

        assertThatThrownBy(() ->
                DisasterRecoverySnapshotPublisherCli.preflightOnly(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true 或 false");
    }

    @Test
    void shouldNameMissingEnvironmentVariableWithoutLeakingOtherSecrets() throws Exception {
        Map<String, String> environment = environment();
        environment.remove("AI_FRIEND_DR_PUBLISH_BUCKET");

        assertThatThrownBy(() -> DisasterRecoverySnapshotPublisherCli.options(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI_FRIEND_DR_PUBLISH_BUCKET")
                .hasMessageNotContaining("publisher-secret-key");
    }

    @Test
    void shouldCompileCliBeforeStartingOfflinePublisher() throws Exception {
        String script = Files.readString(Path.of(
                "scripts", "publish-disaster-recovery-snapshot.ps1"), UTF_8);
        String quotedMainClassArgument =
                "'-Dexec.mainClass=com.aifriend.retention.infrastructure."
                        + "DisasterRecoverySnapshotPublisherCli'";

        int compileIndex = script.indexOf("-DskipTests compile");
        int executionIndex = script.indexOf(
                "org.codehaus.mojo:exec-maven-plugin:3.5.0:java");

        assertThat(compileIndex).isNotNegative();
        assertThat(executionIndex).isGreaterThan(compileIndex);
        assertThat(script)
                .contains(quotedMainClassArgument)
                .doesNotContain(
                        "compile -Dexec.mainClass=com.aifriend.retention.infrastructure."
                                + "DisasterRecoverySnapshotPublisherCli");
    }

    @Test
    void shouldKeepUtf8BomForWindowsPowerShellChineseTextCompatibility()
            throws Exception {
        byte[] script = Files.readAllBytes(Path.of(
                "scripts", "publish-disaster-recovery-snapshot.ps1"));

        assertThat(script).startsWith(UTF_8_BOM);
    }

    @Test
    void shouldExposePreflightOnlyBranchWithoutReadingManifestKey() throws Exception {
        String script = Files.readString(Path.of(
                "scripts", "publish-disaster-recovery-snapshot.ps1"), UTF_8);

        int preflightBranch = script.indexOf("if ($PreflightOnly)");
        int privateKeyPrompt = script.indexOf("请输入仓库外 Ed25519 PKCS#8 私钥文件路径");

        assertThat(script).contains("[switch]$PreflightOnly");
        assertThat(preflightBranch).isNotNegative();
        assertThat(privateKeyPrompt).isGreaterThan(preflightBranch);
        assertThat(script).contains("不会写入或删除 COS 对象");
    }

    @Test
    void shouldExposeReadOnlyRestoreVerificationWithoutPrivateKeyOrDatabase() throws Exception {
        String script = Files.readString(Path.of(
                "scripts", "publish-disaster-recovery-snapshot.ps1"), UTF_8);
        int verificationBranch = script.indexOf("if ($VerifyRestoreOnly)");
        int privateKeyPrompt = script.indexOf("请输入仓库外 Ed25519 PKCS#8 私钥文件路径");
        String quotedMainClassArgument =
                "'-Dexec.mainClass=com.aifriend.retention.infrastructure."
                        + "DisasterRecoverySnapshotRestoreVerifierCli'";

        assertThat(script).contains("[switch]$VerifyRestoreOnly");
        assertThat(verificationBranch).isNotNegative();
        assertThat(privateKeyPrompt).isGreaterThan(verificationBranch);
        assertThat(script)
                .contains(quotedMainClassArgument)
                .contains("不会连接数据库，也不会写入或删除 COS 对象");
    }

    private static Map<String, String> environment() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String, String> environment = new HashMap<>();
        environment.put("AI_FRIEND_DR_PUBLISH_REGION", "ap-shanghai");
        environment.put(
                "AI_FRIEND_DR_PUBLISH_BUCKET",
                "ai-friend-tombstones-1250000000");
        environment.put("AI_FRIEND_DR_PUBLISH_SECRET_ID", "publisher-secret-id");
        environment.put("AI_FRIEND_DR_PUBLISH_SECRET_KEY", "publisher-secret-key");
        environment.put("AI_FRIEND_DR_EXPORT_SECRET_ID", "export-secret-id");
        environment.put("AI_FRIEND_DR_RESTORE_SECRET_ID", "restore-secret-id");
        environment.put("AI_FRIEND_DR_PUBLISH_SNAPSHOT_ID", "snapshot-20260828");
        environment.put("AI_FRIEND_DR_PUBLISH_CREATED_AT", "2026-08-28T00:00:00Z");
        environment.put(
                "AI_FRIEND_DR_MANIFEST_PRIVATE_KEY_PKCS8_BASE64",
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        environment.put(
                "AI_FRIEND_DR_MANIFEST_PUBLIC_KEY_X509_BASE64",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return environment;
    }

    private static Map<String, String> preflightEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("AI_FRIEND_DR_PREFLIGHT_ONLY", "true");
        environment.put("AI_FRIEND_DR_PREFLIGHT_REGION", "ap-shanghai");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_BUCKET",
                "ai-friend-tombstones-1250000000");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_ID",
                "publisher-secret-id");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_PUBLISH_SECRET_KEY",
                "publisher-secret-key");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_ID",
                "export-secret-id");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_EXPORT_SECRET_KEY",
                "export-secret-key");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_ID",
                "restore-secret-id");
        environment.put(
                "AI_FRIEND_DR_PREFLIGHT_RESTORE_SECRET_KEY",
                "restore-secret-key");
        return environment;
    }
}
