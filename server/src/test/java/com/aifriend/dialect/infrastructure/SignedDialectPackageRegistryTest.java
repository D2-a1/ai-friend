package com.aifriend.dialect.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.aifriend.dialect.application.DialectPackageProperties;
import com.aifriend.dialect.application.DialectPackageState;

class SignedDialectPackageRegistryTest {

    private static final String KEY_ID = "wugang-test-ed25519-v1";

    @TempDir
    private Path packageRoot;

    private KeyPair keyPair;

    @BeforeEach
    void createSigningKey() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void shouldLoadPackageWhenSignatureHashAndVersionsAreValid() throws Exception {
        createPackage("1.0.0");

        SignedDialectPackageRegistry registry = registry(true, "1.0.0");

        assertEquals(DialectPackageState.ACTIVE, registry.state());
        assertTrue(registry.findActive().isPresent());
        assertEquals("zh-Hans-CN-x-wugang",
                registry.findActive().orElseThrow().manifest().dialectCode());
    }

    @Test
    void shouldFailClosedWhenCalibrationFileIsTampered() throws Exception {
        createPackage("1.0.0");
        Files.writeString(packageRoot.resolve("acoustic-calibration.json"),
                calibrationJson().replace("0.20", "0.21"), UTF_8);

        SignedDialectPackageRegistry registry = registry(true, "1.0.0");

        assertEquals(DialectPackageState.INVALID, registry.state());
        assertTrue(registry.findActive().isEmpty());
    }

    @Test
    void shouldFailClosedWhenSignedManifestIsTampered() throws Exception {
        createPackage("1.0.0");
        Path manifestPath = packageRoot.resolve("manifest.json");
        Files.writeString(manifestPath,
                Files.readString(manifestPath, UTF_8)
                        .replace("wugang-package-test-v1", "wugang-package-test-v2"),
                UTF_8);

        SignedDialectPackageRegistry registry = registry(true, "1.0.0");

        assertEquals(DialectPackageState.INVALID, registry.state());
        assertTrue(registry.findActive().isEmpty());
    }

    @Test
    void shouldFailClosedWhenServerVersionIsBelowManifestMinimum() throws Exception {
        createPackage("2.0.0");

        SignedDialectPackageRegistry registry = registry(true, "1.0.0");

        assertEquals(DialectPackageState.INVALID, registry.state());
        assertTrue(registry.findActive().isEmpty());
    }

    @Test
    void shouldRemainDisabledWithoutReadingPackageWhenFeatureIsOff() {
        SignedDialectPackageRegistry registry = registry(false, "1.0.0");

        assertEquals(DialectPackageState.DISABLED, registry.state());
        assertTrue(registry.findActive().isEmpty());
    }

    private SignedDialectPackageRegistry registry(boolean enabled, String serverVersion) {
        DialectPackageProperties properties = new DialectPackageProperties(
                enabled,
                "zh-Hans-CN-x-wugang",
                packageRoot.toUri().toString(),
                KEY_ID,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                serverVersion);
        return new SignedDialectPackageRegistry(
                properties, new DefaultResourceLoader());
    }

    private void createPackage(String minimumServerVersion) throws Exception {
        byte[] calibrationBytes = calibrationJson().getBytes(UTF_8);
        String calibrationHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(calibrationBytes));
        String manifest = """
                {
                  "dialectCode": "zh-Hans-CN-x-wugang",
                  "packageVersion": "wugang-package-test-v1",
                  "acousticEngine": "MFCC_DTW_V1",
                  "acousticModelVersion": "mfcc-dtw-test-v1",
                  "thresholdVersion": "threshold-test-v1",
                  "primaryAsrModelVersion": "RESERVED_DISABLED",
                  "primaryAsrModelSha256": "%s",
                  "mandarinAssistVersion": "RESERVED_DISABLED",
                  "mandarinAssistSha256": "%s",
                  "fusionRuleVersion": "RESERVED_DISABLED",
                  "alignmentVersion": "RESERVED_DISABLED",
                  "minimumServerVersion": "%s",
                  "minimumAndroidAppVersion": "1.0.0",
                  "calibrationFile": "acoustic-calibration.json",
                  "calibrationSha256": "%s",
                  "signatureKeyId": "%s",
                  "issuedAt": "2026-08-12T14:00:00Z"
                }
                """.formatted("0".repeat(64), "1".repeat(64),
                        minimumServerVersion, calibrationHash, KEY_ID);
        byte[] manifestBytes = manifest.getBytes(UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifestBytes);

        Files.write(packageRoot.resolve("acoustic-calibration.json"), calibrationBytes);
        Files.write(packageRoot.resolve("manifest.json"), manifestBytes);
        Files.writeString(packageRoot.resolve("manifest.sig"),
                Base64.getEncoder().encodeToString(signer.sign()), UTF_8);
    }

    private String calibrationJson() {
        return """
                {
                  "acousticEngine": "MFCC_DTW_V1",
                  "sampleRateHz": 16000,
                  "frameLengthMs": 25,
                  "frameShiftMs": 10,
                  "melFilterCount": 26,
                  "coefficientCount": 13,
                  "minimumDurationMs": 300,
                  "maximumDurationMs": 5000,
                  "minimumPeakDbfs": -60.0,
                  "vadRelativeFloorDb": 35.0,
                  "minimumActiveFrameRatio": 0.20,
                  "maximumClippedSampleRatio": 0.01,
                  "dtwWindowRatio": 0.20,
                  "enrollmentConsistencyMaxDistance": 1.00,
                  "uniquenessConflictMaxDistance": 0.20,
                  "uniquenessDistinctMinDistance": 1.50,
                  "taskAliasUniqueMaxDistance": 0.50,
                  "taskAliasCandidateMaxDistance": 1.50,
                  "taskAliasMinimumMargin": 0.20
                }
                """;
    }
}
