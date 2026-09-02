package com.aifriend.dialect.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.dialect.application.DialectAcousticCalibration;
import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageProperties;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.dialect.application.DialectPackageState;
import com.aifriend.dialect.application.VerifiedDialectPackage;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 从部署资源加载并校验 Ed25519 签名方言包的注册表。
 *
 * <p>清单签名、校准文件摘要、固定主方言或版本兼容性任一校验失败时，
 * 注册表保持 {@link DialectPackageState#INVALID}，不会把未验证参数交给声学引擎。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@ConditionalOnProperty(prefix = "ai-friend.basic-experience", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class SignedDialectPackageRegistry implements DialectPackageRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            SignedDialectPackageRegistry.class);
    private static final int MAX_MANIFEST_BYTES = 65_536;
    private static final int MAX_SIGNATURE_BYTES = 512;
    private static final int MAX_CALIBRATION_BYTES = 65_536;
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String SIGNATURE_FILE = "manifest.sig";
    private static final String SUPPORTED_ENGINE = "MFCC_DTW_V1";
    private static final Pattern SAFE_FILE_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,100}");
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._+-]{0,59}");
    private static final ObjectMapper STRICT_MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final DialectPackageState packageState;
    private final VerifiedDialectPackage activePackage;

    /**
     * 创建只加载一次的随应用发布方言包注册表。
     *
     * <p>v1.0 不支持运行时下载或热切换。校验失败只停用方言语音能力，
     * 不阻止账号、授权、绑定、删除等非语音能力启动。
     *
     * @param properties 方言包路径、固定方言和信任公钥配置
     * @param resourceLoader Spring 资源加载器
     */
    public SignedDialectPackageRegistry(
            DialectPackageProperties properties,
            ResourceLoader resourceLoader) {
        LoadResult loadResult = load(properties, resourceLoader);
        this.packageState = loadResult.state();
        this.activePackage = loadResult.activePackage();
    }

    /** {@inheritDoc} */
    @Override
    public DialectPackageState state() {
        return packageState;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<VerifiedDialectPackage> findActive() {
        return Optional.ofNullable(activePackage);
    }

    private LoadResult load(
            DialectPackageProperties properties,
            ResourceLoader resourceLoader) {
        if (!properties.enabled()) {
            return new LoadResult(DialectPackageState.DISABLED, null);
        }
        try {
            validateTrustConfiguration(properties);
            Resource packageRoot = resourceLoader.getResource(normalizeRoot(
                    properties.packageRoot()));
            byte[] manifestBytes = readBounded(
                    packageRoot.createRelative(MANIFEST_FILE), MAX_MANIFEST_BYTES);
            byte[] signatureBytes = decodeSignature(readBounded(
                    packageRoot.createRelative(SIGNATURE_FILE), MAX_SIGNATURE_BYTES));
            verifySignature(manifestBytes, signatureBytes,
                    properties.trustedPublicKeyBase64());

            DialectPackageManifest manifest = STRICT_MAPPER.readValue(
                    manifestBytes, DialectPackageManifest.class);
            validateManifest(manifest, properties);
            byte[] calibrationBytes = readBounded(
                    packageRoot.createRelative(manifest.calibrationFile()),
                    MAX_CALIBRATION_BYTES);
            verifyCalibrationHash(calibrationBytes, manifest.calibrationSha256());
            DialectAcousticCalibration calibration = STRICT_MAPPER.readValue(
                    calibrationBytes, DialectAcousticCalibration.class);
            validateCalibration(calibration, manifest);

            return new LoadResult(DialectPackageState.ACTIVE,
                    new VerifiedDialectPackage(manifest, calibration));
        } catch (IOException | GeneralSecurityException
                | IllegalArgumentException exception) {
            LOGGER.warn("方言包校验失败，语音能力保持关闭 reason={}",
                    safeReason(exception));
            return new LoadResult(DialectPackageState.INVALID, null);
        }
    }

    private void validateTrustConfiguration(DialectPackageProperties properties) {
        requireText(properties.requiredDialectCode(), "DIALECT_CODE_MISSING");
        requireText(properties.packageRoot(), "PACKAGE_ROOT_MISSING");
        requireText(properties.trustedKeyId(), "TRUSTED_KEY_ID_MISSING");
        requireText(properties.trustedPublicKeyBase64(), "TRUSTED_KEY_MISSING");
        parseSemanticVersion(properties.serverVersion(), "SERVER_VERSION_INVALID");
    }

    private String normalizeRoot(String packageRoot) {
        return packageRoot.endsWith("/") ? packageRoot : packageRoot + "/";
    }

    private byte[] readBounded(Resource resource, int maximumBytes) throws IOException {
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("PACKAGE_RESOURCE_UNAVAILABLE");
        }
        try (InputStream input = resource.getInputStream()) {
            byte[] content = input.readNBytes(maximumBytes + 1);
            if (content.length == 0 || content.length > maximumBytes) {
                throw new IllegalArgumentException("PACKAGE_RESOURCE_SIZE_INVALID");
            }
            return content;
        }
    }

    private byte[] decodeSignature(byte[] encodedSignature) {
        try {
            return Base64.getDecoder().decode(new String(
                    encodedSignature, java.nio.charset.StandardCharsets.US_ASCII).strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("SIGNATURE_ENCODING_INVALID", exception);
        }
    }

    private void verifySignature(
            byte[] manifestBytes,
            byte[] signatureBytes,
            String trustedPublicKeyBase64) throws GeneralSecurityException {
        byte[] encodedKey;
        try {
            encodedKey = Base64.getDecoder().decode(trustedPublicKeyBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("TRUSTED_KEY_ENCODING_INVALID", exception);
        }
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encodedKey));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(manifestBytes);
        if (!verifier.verify(signatureBytes)) {
            throw new IllegalArgumentException("MANIFEST_SIGNATURE_INVALID");
        }
    }

    private void validateManifest(
            DialectPackageManifest manifest,
            DialectPackageProperties properties) {
        if (manifest == null) {
            throw new IllegalArgumentException("MANIFEST_MISSING");
        }
        requireToken(manifest.dialectCode(), 40, "MANIFEST_DIALECT_INVALID");
        requireToken(manifest.packageVersion(), 60, "PACKAGE_VERSION_INVALID");
        requireToken(manifest.acousticEngine(), 60, "ACOUSTIC_ENGINE_INVALID");
        requireToken(manifest.acousticModelVersion(), 60, "MODEL_VERSION_INVALID");
        requireToken(manifest.thresholdVersion(), 60, "THRESHOLD_VERSION_INVALID");
        requireToken(manifest.primaryAsrModelVersion(), 60, "ASR_VERSION_INVALID");
        requireSha256(manifest.primaryAsrModelSha256(), "ASR_SHA256_INVALID");
        requireToken(manifest.mandarinAssistVersion(), 60, "ASSIST_VERSION_INVALID");
        requireSha256(manifest.mandarinAssistSha256(), "ASSIST_SHA256_INVALID");
        requireToken(manifest.fusionRuleVersion(), 60, "FUSION_VERSION_INVALID");
        requireToken(manifest.alignmentVersion(), 60, "ALIGNMENT_VERSION_INVALID");
        requireToken(manifest.signatureKeyId(), 60, "SIGNATURE_KEY_ID_INVALID");
        if (!properties.requiredDialectCode().equals(manifest.dialectCode())
                || !properties.trustedKeyId().equals(manifest.signatureKeyId())
                || !SUPPORTED_ENGINE.equals(manifest.acousticEngine())) {
            throw new IllegalArgumentException("MANIFEST_COMPATIBILITY_INVALID");
        }
        if (!StringUtils.hasText(manifest.calibrationFile())
                || !SAFE_FILE_NAME.matcher(manifest.calibrationFile()).matches()
                || MANIFEST_FILE.equals(manifest.calibrationFile())
                || SIGNATURE_FILE.equals(manifest.calibrationFile())
                || !StringUtils.hasText(manifest.calibrationSha256())
                || !SHA_256_HEX.matcher(manifest.calibrationSha256()).matches()) {
            throw new IllegalArgumentException("CALIBRATION_REFERENCE_INVALID");
        }
        int[] minimumServerVersion = parseSemanticVersion(
                manifest.minimumServerVersion(), "MINIMUM_SERVER_VERSION_INVALID");
        int[] currentServerVersion = parseSemanticVersion(
                properties.serverVersion(), "SERVER_VERSION_INVALID");
        parseSemanticVersion(manifest.minimumAndroidAppVersion(),
                "MINIMUM_ANDROID_VERSION_INVALID");
        if (compareVersions(currentServerVersion, minimumServerVersion) < 0) {
            throw new IllegalArgumentException("SERVER_VERSION_INCOMPATIBLE");
        }
        try {
            Instant.parse(manifest.issuedAt());
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("MANIFEST_TIME_INVALID", exception);
        }
    }

    private void verifyCalibrationHash(byte[] calibrationBytes, String expectedHex) {
        try {
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(calibrationBytes);
            byte[] expected = HexFormat.of().parseHex(expectedHex);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalArgumentException("CALIBRATION_HASH_INVALID");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private void validateCalibration(
            DialectAcousticCalibration calibration,
            DialectPackageManifest manifest) {
        if (calibration == null
                || !manifest.acousticEngine().equals(calibration.acousticEngine())
                || calibration.sampleRateHz() != 16_000
                || calibration.frameLengthMs() < 20
                || calibration.frameLengthMs() > 40
                || calibration.frameShiftMs() < 5
                || calibration.frameShiftMs() > 20
                || calibration.frameShiftMs() >= calibration.frameLengthMs()
                || calibration.melFilterCount() < 20
                || calibration.melFilterCount() > 40
                || calibration.coefficientCount() < 10
                || calibration.coefficientCount() > 20
                || calibration.coefficientCount() > calibration.melFilterCount()
                || calibration.minimumDurationMs() < 300
                || calibration.minimumDurationMs() > 2_000
                || calibration.maximumDurationMs() < calibration.minimumDurationMs()
                || calibration.maximumDurationMs() > 5_000
                || !within(calibration.minimumPeakDbfs(), -80.0D, -3.0D)
                || !within(calibration.vadRelativeFloorDb(), 5.0D, 80.0D)
                || !withinExclusive(calibration.minimumActiveFrameRatio(), 0.0D, 1.0D)
                || !within(calibration.maximumClippedSampleRatio(), 0.0D, 0.1D)
                || !within(calibration.dtwWindowRatio(), 0.05D, 1.0D)
                || !within(calibration.enrollmentConsistencyMaxDistance(), 0.0D, 1_000.0D)
                || !within(calibration.uniquenessConflictMaxDistance(), 0.0D, 1_000.0D)
                || !within(calibration.uniquenessDistinctMinDistance(), 0.0D, 1_000.0D)
                || !within(calibration.taskAliasUniqueMaxDistance(), 0.0D, 1_000.0D)
                || !within(calibration.taskAliasCandidateMaxDistance(), 0.0D, 1_000.0D)
                || !withinExclusive(calibration.taskAliasMinimumMargin(), 0.0D, 1_000.0D)
                || calibration.uniquenessConflictMaxDistance()
                >= calibration.uniquenessDistinctMinDistance()
                || calibration.taskAliasUniqueMaxDistance()
                >= calibration.taskAliasCandidateMaxDistance()) {
            throw new IllegalArgumentException("CALIBRATION_VALUES_INVALID");
        }
    }

    private void requireText(String value, String reason) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(reason);
        }
    }

    private void requireToken(String value, int maximumLength, String reason) {
        if (!StringUtils.hasText(value)
                || value.length() > maximumLength
                || !VERSION_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(reason);
        }
    }

    private void requireSha256(String value, String reason) {
        if (!StringUtils.hasText(value) || !SHA_256_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(reason);
        }
    }

    private int[] parseSemanticVersion(String value, String reason) {
        if (!StringUtils.hasText(value) || !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException(reason);
        }
        String[] parts = value.split("\\.");
        try {
            return new int[] {
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            };
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(reason, exception);
        }
    }

    private int compareVersions(int[] left, int[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Integer.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private boolean within(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value >= minimum && value <= maximum;
    }

    private boolean withinExclusive(double value, double minimum, double maximum) {
        return Double.isFinite(value) && value > minimum && value < maximum;
    }

    private String safeReason(Exception exception) {
        String message = exception.getMessage();
        if (!StringUtils.hasText(message)
                || !message.matches("[A-Z0-9_]{3,80}")) {
            return "PACKAGE_INVALID";
        }
        return message;
    }

    private record LoadResult(
            DialectPackageState state,
            VerifiedDialectPackage activePackage) {
    }
}
