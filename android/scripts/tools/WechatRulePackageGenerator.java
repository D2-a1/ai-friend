import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 离线生成仅含已验收版本组合与页面摘要的微信规则包。
 *
 * <p>私钥只保存到调用方指定的仓库外目录；输出规则包不含选择器、坐标、页面文字或脚本。
 */
public final class WechatRulePackageGenerator {
    private static final String SCHEMA_VERSION = "WECHAT_CAPABILITY_MATRIX_V1";
    private static final Pattern TOKEN = Pattern.compile("[^\\s:]{1,100}");
    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern APPLICATION_ID =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.]{1,99}");
    private static final String PRIVATE_KEY_FILE = "wechat-rule-private.pk8";
    private static final String PUBLIC_KEY_FILE = "wechat-rule-public.spki";

    private WechatRulePackageGenerator() {
    }

    public static void main(String[] args) {
        try {
            generate(args);
            System.out.println("RESULT=SUCCESS");
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            System.err.println(
                    "ERROR_BASE64=" + Base64.getEncoder().encodeToString(
                            message.getBytes(StandardCharsets.UTF_8)));
            System.exit(2);
        }
    }

    private static void generate(String[] args) throws Exception {
        Map<String, String> arguments = parseArguments(args);
        Path input = absolute(arguments.get("--input"), "输入配置");
        Path keyDirectory = absolute(arguments.get("--key-directory"), "密钥目录");
        Path outputDirectory = absolute(arguments.get("--output-directory"), "输出目录");
        require(Files.isRegularFile(input), "输入配置文件不存在");
        require(
                !containsPath(keyDirectory, outputDirectory)
                        && !containsPath(outputDirectory, keyDirectory),
                "密钥目录与输出目录必须完全分离");

        Properties properties = loadProperties(input);
        String packageVersion = token(properties, "packageVersion");
        String signatureKeyId = value(properties, "signatureKeyId");
        require(KEY_ID.matcher(signatureKeyId).matches(), "signatureKeyId 格式无效");
        String applicationId = value(properties, "applicationId");
        require(APPLICATION_ID.matcher(applicationId).matches(), "applicationId 格式无效");
        String appVersion = token(properties, "appVersion");
        String signingCertificateSha256 = sha256(properties, "signingCertificateSha256");
        String deviceManufacturer = deviceFact(properties, "deviceManufacturer");
        String deviceModel = deviceFact(properties, "deviceModel");
        int androidSdk = integer(properties, "androidSdk", 29, 100);
        String wechatVersion = token(properties, "wechatVersion");
        String ruleVersion = token(properties, "ruleVersion");
        String locatorVersion = token(properties, "locatorVersion");
        String issuedAt = properties.getProperty("issuedAt", "").trim();
        issuedAt = issuedAt.isEmpty() ? Instant.now().toString() : Instant.parse(issuedAt).toString();
        String buildIdentity = properties.getProperty("appBuildIdentitySha256", "").trim();
        if (buildIdentity.isEmpty()) {
            byte[] randomIdentity = new byte[32];
            new SecureRandom().nextBytes(randomIdentity);
            buildIdentity = hex(randomIdentity);
            java.util.Arrays.fill(randomIdentity, (byte) 0);
        }
        require(SHA256.matcher(buildIdentity).matches(),
                "appBuildIdentitySha256 必须是 64 位小写十六进制");

        List<String> compatibleRuleVersions = commaSeparatedTokens(
                properties.getProperty("compatibleMinimumRuleVersions", ruleVersion));
        Map<String, String> signatures = new LinkedHashMap<>();
        signatures.put("CONTACT_PROFILE", sha256(properties, "contactProfileSha256"));
        signatures.put(
                "VOICE_CALL_CONFIRMATION",
                sha256(properties, "voiceCallConfirmationSha256"));
        signatures.put("VOICE_CALL_ACTIVE", sha256(properties, "voiceCallActiveSha256"));
        signatures.put(
                "VIDEO_CALL_CONFIRMATION",
                sha256(properties, "videoCallConfirmationSha256"));
        signatures.put("VIDEO_CALL_ACTIVE", sha256(properties, "videoCallActiveSha256"));

        Path assetsDirectory = outputDirectory.resolve("assets");
        Path ruleRoot = assetsDirectory.resolve("wechat").resolve("rules");
        Path capabilitiesFile = ruleRoot.resolve("capabilities.json");
        Path manifestFile = ruleRoot.resolve("manifest.json");
        Path signatureFile = ruleRoot.resolve("manifest.sig");
        Path releaseConfigFile = outputDirectory.resolve("wechat-rule-release.json");
        refuseOverwrite(capabilitiesFile, manifestFile, signatureFile, releaseConfigFile);
        KeyPair keyPair = loadOrCreateKeyPair(keyDirectory);
        Files.createDirectories(ruleRoot);

        byte[] capabilities = capabilityJson(
                applicationId,
                appVersion,
                buildIdentity,
                signingCertificateSha256,
                deviceManufacturer,
                deviceModel,
                androidSdk,
                wechatVersion,
                ruleVersion,
                locatorVersion,
                compatibleRuleVersions,
                signatures).getBytes(StandardCharsets.UTF_8);
        String capabilitySha256 = hex(
                MessageDigest.getInstance("SHA-256").digest(capabilities));
        byte[] manifest = manifestJson(
                packageVersion,
                capabilitySha256,
                signatureKeyId,
                issuedAt).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(manifest);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(manifest);
        require(verifier.verify(signature), "规则包签名自检失败");

        writeNew(capabilitiesFile, capabilities);
        writeNew(manifestFile, manifest);
        writeNew(signatureFile, Base64.getEncoder().encode(signature));
        writeReleaseConfiguration(
                releaseConfigFile,
                assetsDirectory,
                signatureKeyId,
                keyPair.getPublic(),
                buildIdentity);
        java.util.Arrays.fill(signature, (byte) 0);

    }

    private static Map<String, String> parseArguments(String[] args) {
        require(args.length == 6, usage());
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            require(args[index].startsWith("--"), usage());
            require(!values.containsKey(args[index]), "参数重复：" + args[index]);
            values.put(args[index], args[index + 1]);
        }
        require(values.keySet().equals(
                java.util.Set.of("--input", "--key-directory", "--output-directory")), usage());
        return values;
    }

    private static String usage() {
        return "用法：--input <properties> --key-directory <仓库外目录> "
                + "--output-directory <仓库外目录>";
    }

    private static Path absolute(String rawValue, String label) {
        require(rawValue != null && !rawValue.isBlank(), label + "不能为空");
        Path value = Path.of(rawValue).normalize();
        require(value.isAbsolute(), label + "必须是绝对路径");
        return value;
    }

    private static boolean containsPath(Path root, Path candidate) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        return normalizedCandidate.startsWith(normalizedRoot);
    }

    private static Properties loadProperties(Path input) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String value(Properties properties, String name) {
        String result = properties.getProperty(name);
        require(result != null && !result.isBlank() && result.equals(result.trim()),
                name + " 缺失或格式无效");
        return result;
    }

    private static String token(Properties properties, String name) {
        String result = value(properties, name);
        require(TOKEN.matcher(result).matches(), name + " 格式无效");
        return result;
    }

    private static String sha256(Properties properties, String name) {
        String result = value(properties, name);
        require(SHA256.matcher(result).matches(), name + " 必须是 64 位小写十六进制");
        return result;
    }

    private static String deviceFact(Properties properties, String name) {
        String result = value(properties, name);
        require(result.length() <= 100, name + " 长度无效");
        require(
                result.codePoints().allMatch(codePoint ->
                        !Character.isISOControl(codePoint)
                                && Character.getType(codePoint) != Character.FORMAT),
                name + " 包含无效控制字符");
        return result;
    }

    private static int integer(Properties properties, String name, int minimum, int maximum) {
        int result;
        try {
            result = Integer.parseInt(value(properties, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须是整数", exception);
        }
        require(result >= minimum && result <= maximum, name + " 超出允许范围");
        return result;
    }

    private static List<String> commaSeparatedTokens(String rawValue) {
        List<String> values = java.util.Arrays.stream(rawValue.split(",", -1))
                .map(String::trim)
                .toList();
        require(!values.isEmpty() && values.size() <= 16, "兼容规则版本数量无效");
        require(values.stream().allMatch(value -> TOKEN.matcher(value).matches()),
                "兼容规则版本格式无效");
        require(values.stream().distinct().count() == values.size(), "兼容规则版本不能重复");
        return values;
    }

    private static KeyPair loadOrCreateKeyPair(Path keyDirectory) throws Exception {
        Path privateKeyPath = keyDirectory.resolve(PRIVATE_KEY_FILE);
        Path publicKeyPath = keyDirectory.resolve(PUBLIC_KEY_FILE);
        boolean privateExists = Files.exists(privateKeyPath);
        boolean publicExists = Files.exists(publicKeyPath);
        require(privateExists == publicExists, "规则包密钥不完整，请停止并检查独立密钥目录");
        if (!privateExists) {
            Files.createDirectories(keyDirectory);
            KeyPair generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            writeNew(privateKeyPath, generated.getPrivate().getEncoded());
            writeNew(publicKeyPath, generated.getPublic().getEncoded());
            restrictPrivateKey(privateKeyPath);
            return generated;
        }
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = keyFactory.generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(privateKeyPath)));
        PublicKey publicKey = keyFactory.generatePublic(
                new X509EncodedKeySpec(Files.readAllBytes(publicKeyPath)));
        return new KeyPair(publicKey, privateKey);
    }

    private static void restrictPrivateKey(Path privateKeyPath) {
        try {
            Files.setPosixFilePermissions(
                    privateKeyPath,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 权限由密钥目录的 ACL 管理；工具不会放宽权限。
        }
    }

    private static void refuseOverwrite(Path... files) {
        for (Path file : files) {
            require(!Files.exists(file), "输出文件已存在，拒绝覆盖：" + file.getFileName());
        }
    }

    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void writeReleaseConfiguration(
            Path path,
            Path outputDirectory,
            String keyId,
            PublicKey publicKey,
            String buildIdentity) throws IOException {
        String json = "{"
                + "\"AI_FRIEND_ANDROID_WECHAT_RULE_PACKAGE_DIR\":"
                + quote(outputDirectory.toAbsolutePath().normalize().toString()) + ","
                + "\"AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_KEY_ID\":" + quote(keyId) + ","
                + "\"AI_FRIEND_ANDROID_WECHAT_RULE_TRUSTED_PUBLIC_KEY_BASE64\":"
                + quote(Base64.getEncoder().encodeToString(publicKey.getEncoded())) + ","
                + "\"AI_FRIEND_ANDROID_WECHAT_APP_BUILD_IDENTITY_SHA256\":"
                + quote(buildIdentity)
                + "}";
        writeNew(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String manifestJson(
            String packageVersion,
            String capabilitySha256,
            String signatureKeyId,
            String issuedAt) {
        return "{"
                + "\"packageVersion\":" + quote(packageVersion) + ","
                + "\"schemaVersion\":" + quote(SCHEMA_VERSION) + ","
                + "\"capabilityFile\":\"capabilities.json\","
                + "\"capabilitySha256\":" + quote(capabilitySha256) + ","
                + "\"signatureKeyId\":" + quote(signatureKeyId) + ","
                + "\"issuedAt\":" + quote(issuedAt)
                + "}";
    }

    private static String capabilityJson(
            String applicationId,
            String appVersion,
            String buildIdentity,
            String signingCertificateSha256,
            String deviceManufacturer,
            String deviceModel,
            int androidSdk,
            String wechatVersion,
            String ruleVersion,
            String locatorVersion,
            List<String> compatibleRuleVersions,
            Map<String, String> signatures) {
        List<String> voicePages = List.of(
                "CONTACT_PROFILE", "VOICE_CALL_CONFIRMATION", "VOICE_CALL_ACTIVE");
        List<String> videoPages = List.of(
                "CONTACT_PROFILE", "VIDEO_CALL_CONFIRMATION", "VIDEO_CALL_ACTIVE");
        return "{"
                + "\"schemaVersion\":" + quote(SCHEMA_VERSION) + ","
                + "\"combinations\":[{"
                + "\"applicationId\":" + quote(applicationId) + ","
                + "\"appVersion\":" + quote(appVersion) + ","
                + "\"appBuildSha256\":" + quote(buildIdentity) + ","
                + "\"signingCertificateSha256\":" + quote(signingCertificateSha256) + ","
                + "\"deviceManufacturer\":" + quote(deviceManufacturer) + ","
                + "\"deviceModel\":" + quote(deviceModel) + ","
                + "\"minimumAndroidSdk\":" + androidSdk + ","
                + "\"maximumAndroidSdk\":" + androidSdk + ","
                + "\"packageName\":\"com.tencent.mm\","
                + "\"wechatVersion\":" + quote(wechatVersion) + ","
                + "\"ruleVersion\":" + quote(ruleVersion) + ","
                + "\"locatorVersion\":" + quote(locatorVersion) + ","
                + "\"compatibleMinimumRuleVersions\":" + array(compatibleRuleVersions) + ","
                + "\"allowedActions\":[\"START_VOICE_CALL\",\"START_VIDEO_CALL\"],"
                + "\"allowedPageTypes\":{"
                + "\"START_VOICE_CALL\":" + array(voicePages) + ","
                + "\"START_VIDEO_CALL\":" + array(videoPages) + "},"
                + "\"allowedPageSignatures\":{"
                + "\"START_VOICE_CALL\":" + signatureArray(voicePages, signatures) + ","
                + "\"START_VIDEO_CALL\":" + signatureArray(videoPages, signatures) + "},"
                + "\"allowedPageSignaturesByType\":{"
                + "\"START_VOICE_CALL\":" + signatureMap(voicePages, signatures) + ","
                + "\"START_VIDEO_CALL\":" + signatureMap(videoPages, signatures)
                + "}}]}";
    }

    private static String signatureArray(
            List<String> pageTypes,
            Map<String, String> signatures) {
        return array(pageTypes.stream().map(signatures::get).toList());
    }

    private static String signatureMap(
            List<String> pageTypes,
            Map<String, String> signatures) {
        return "{" + pageTypes.stream()
                .map(pageType -> quote(pageType) + ":[" + quote(signatures.get(pageType)) + "]")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private static String array(List<String> values) {
        return "[" + values.stream().map(WechatRulePackageGenerator::quote)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(String.format("%02x", current & 0xff));
        }
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
