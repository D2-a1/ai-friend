package com.aifriend.task.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.aifriend.task.application.WechatSemanticCallContract;

/**
 * 在仓库外生成微信动作计划 Ed25519 密钥和两端配置文件。
 *
 * <p>生成器不启动 Spring，不连接数据库、Redis、微信或云端。私钥只写入受限目录内
 * 的服务端环境片段；Android JSON 只包含公开 keyId 和 X.509 公钥。目标目录必须已
 * 存在、为空并位于项目仓库之外，全部目标文件都使用禁止覆盖方式创建。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public final class WechatActionPlanKeyGeneratorCli {

    static final String SERVER_ENV_FILE_NAME = "wechat-action-plan-server.env";
    static final String ANDROID_TRUST_FILE_NAME = "wechat-action-plan-android-trust.json";
    private static final String KEY_ID_PREFIX = "wechat-action-plan-";
    private static final Pattern VERSION_TOKEN = Pattern.compile("[^\\s:]{1,100}");
    private static final byte[] PAIR_PROOF =
            "ai-friend-wechat-action-plan-key-pair-v1".getBytes(UTF_8);

    private WechatActionPlanKeyGeneratorCli() {
    }

    /**
     * 读取当前进程中的非秘密路径和版本参数并生成配置。
     *
     * @param arguments 不接受命令行参数
     */
    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("微信动作计划密钥生成工具不接受命令行参数");
        }
        GenerationOptions options = options(System.getenv());
        GeneratedConfiguration generated = generate(options);
        System.out.println("wechat_action_plan_key_generation=success");
        System.out.println("key_id=" + generated.keyId());
        System.out.println("public_key_sha256=" + generated.publicKeySha256());
        System.out.println("execution_enabled=false");
    }

    /**
     * 解析生成参数。
     *
     * @param environment 当前进程环境
     * @return 已校验的生成参数
     */
    static GenerationOptions options(Map<String, String> environment) {
        Objects.requireNonNull(environment, "进程环境不能为空");
        return new GenerationOptions(
                absolutePath(environment, "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_REPOSITORY_ROOT"),
                absolutePath(environment, "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_OUTPUT_DIRECTORY"),
                versionToken(environment, "AI_FRIEND_WECHAT_ACTION_PLAN_APP_VERSION"),
                versionToken(environment, "AI_FRIEND_WECHAT_ACTION_PLAN_WECHAT_VERSION"));
    }

    /**
     * 生成配对密钥并写入服务端私密片段和 Android 公开 JSON。
     *
     * @param options 已校验的路径和版本参数
     * @return 公开生成结果
     */
    static GeneratedConfiguration generate(GenerationOptions options) {
        Objects.requireNonNull(options, "生成参数不能为空");
        Path repositoryRoot = requireDirectory(options.repositoryRoot(), "项目仓库根目录");
        Path outputDirectory = requireDirectory(options.outputDirectory(), "密钥输出目录");
        if (outputDirectory.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("微信动作计划密钥输出目录必须位于项目仓库之外");
        }
        requireEmptyDirectory(outputDirectory);

        KeyPair keyPair = generateKeyPair();
        verifyKeyPair(keyPair);
        byte[] privateKey = requireEncodedKey(keyPair.getPrivate().getEncoded(), "Ed25519 私钥");
        byte[] publicKey = requireEncodedKey(keyPair.getPublic().getEncoded(), "Ed25519 公钥");
        byte[] privateKeyBase64 = Base64.getEncoder().encode(privateKey);
        byte[] publicKeyBase64 = Base64.getEncoder().encode(publicKey);
        byte[] publicKeySha256 = sha256(publicKey);
        String publicKeySha256Hex = HexFormat.of().withUpperCase()
                .formatHex(publicKeySha256);
        String keyId = KEY_ID_PREFIX + HexFormat.of()
                .formatHex(publicKeySha256, 0, 8);
        Path serverEnvironmentPath = outputDirectory.resolve(SERVER_ENV_FILE_NAME);
        Path androidTrustPath = outputDirectory.resolve(ANDROID_TRUST_FILE_NAME);
        byte[] serverEnvironment = null;
        byte[] androidTrust = null;
        try {
            serverEnvironment = serverEnvironment(
                    keyId,
                    privateKeyBase64,
                    options.appVersion(),
                    options.wechatVersion());
            androidTrust = androidTrust(keyId, publicKeyBase64);
            Files.write(serverEnvironmentPath, serverEnvironment, CREATE_NEW, WRITE);
            Files.write(androidTrustPath, androidTrust, CREATE_NEW, WRITE);
            return new GeneratedConfiguration(
                    serverEnvironmentPath,
                    androidTrustPath,
                    keyId,
                    publicKeySha256Hex);
        } catch (IOException exception) {
            throw new IllegalStateException("微信动作计划密钥配置写入失败；禁止覆盖既有文件", exception);
        } finally {
            Arrays.fill(privateKey, (byte) 0);
            Arrays.fill(publicKey, (byte) 0);
            Arrays.fill(privateKeyBase64, (byte) 0);
            Arrays.fill(publicKeyBase64, (byte) 0);
            Arrays.fill(publicKeySha256, (byte) 0);
            if (serverEnvironment != null) {
                Arrays.fill(serverEnvironment, (byte) 0);
            }
            if (androidTrust != null) {
                Arrays.fill(androidTrust, (byte) 0);
            }
        }
    }

    private static byte[] serverEnvironment(
            String keyId,
            byte[] privateKeyBase64,
            String appVersion,
            String wechatVersion) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeAscii(output, "AI_FRIEND_WECHAT_EXECUTION_ENABLED=false\n");
            writeAscii(output, "AI_FRIEND_WECHAT_ACTION_PLAN_KEY_ID=" + keyId + "\n");
            writeAscii(output, "AI_FRIEND_WECHAT_ACTION_PLAN_PRIVATE_KEY_PKCS8_BASE64=");
            output.write(privateKeyBase64);
            writeAscii(output, "\n");
            writeAscii(output,
                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_APP_VERSION="
                            + appVersion + "\n");
            writeAscii(output,
                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_WECHAT_VERSION="
                            + wechatVersion + "\n");
            writeAscii(output,
                    "AI_FRIEND_WECHAT_EXECUTION_APPROVED_CLIENT_COMBINATIONS_0_RULE_VERSION="
                            + WechatSemanticCallContract.RULE_VERSION + "\n");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成服务端动作计划配置", exception);
        }
    }

    private static byte[] androidTrust(String keyId, byte[] publicKeyBase64) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeAscii(output, "{\n");
            writeAscii(output,
                    "  \"AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_KEY_ID\": \""
                            + keyId + "\",\n");
            writeAscii(output,
                    "  \"AI_FRIEND_ANDROID_WECHAT_ACTION_PLAN_TRUSTED_PUBLIC_KEY_BASE64\": \"");
            output.write(publicKeyBase64);
            writeAscii(output, "\"\n}\n");
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成 Android 动作计划信任配置", exception);
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value)
            throws IOException {
        output.write(value.getBytes(US_ASCII));
    }

    private static Path absolutePath(Map<String, String> environment, String name) {
        String value = Objects.requireNonNullElse(environment.get(name), "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少微信动作计划密钥路径环境变量：" + name);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("微信动作计划密钥路径必须是绝对路径：" + name);
        }
        return path.normalize();
    }

    private static String versionToken(Map<String, String> environment, String name) {
        String value = Objects.requireNonNullElse(environment.get(name), "");
        if (!VERSION_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("微信动作计划版本参数无效：" + name);
        }
        return value;
    }

    private static Path requireDirectory(Path path, String description) {
        Objects.requireNonNull(path, description + "不能为空");
        try {
            Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException(description + "不是目录");
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException(description + "不存在或无法读取", exception);
        }
    }

    private static void requireEmptyDirectory(Path outputDirectory) {
        try (var entries = Files.list(outputDirectory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("微信动作计划密钥输出目录必须是全新空目录");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("微信动作计划密钥输出目录无法读取", exception);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 Ed25519", exception);
        }
    }

    private static void verifyKeyPair(KeyPair keyPair) {
        byte[] signatureBytes = null;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(PAIR_PROOF);
            signatureBytes = signer.sign();

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(keyPair.getPublic());
            verifier.update(PAIR_PROOF);
            if (!verifier.verify(signatureBytes)) {
                throw new IllegalStateException("生成的 Ed25519 公私钥配对验证失败");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("生成的 Ed25519 公私钥无法通过配对验证", exception);
        } finally {
            if (signatureBytes != null) {
                Arrays.fill(signatureBytes, (byte) 0);
            }
        }
    }

    private static byte[] requireEncodedKey(byte[] encodedKey, String description) {
        if (encodedKey == null || encodedKey.length == 0) {
            throw new IllegalStateException(description + "缺少标准编码");
        }
        return encodedKey;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 生成所需的仓库路径、仓库外目录和已验收版本。
     *
     * @param repositoryRoot 项目仓库根目录
     * @param outputDirectory 仓库外全新输出目录
     * @param appVersion Android Release 版本
     * @param wechatVersion 当前验收微信版本
     */
    record GenerationOptions(
            Path repositoryRoot,
            Path outputDirectory,
            String appVersion,
            String wechatVersion) {
    }

    /**
     * 不含秘密值的生成结果。
     *
     * @param serverEnvironmentPath 服务端私密环境片段
     * @param androidTrustPath Android 公开信任 JSON
     * @param keyId 密钥编号
     * @param publicKeySha256 公钥摘要
     */
    record GeneratedConfiguration(
            Path serverEnvironmentPath,
            Path androidTrustPath,
            String keyId,
            String publicKeySha256) {
    }
}
