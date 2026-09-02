package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 删除墓碑灾备根清单 Ed25519 密钥生成命令行入口。
 *
 * <p>该入口只在本机生成 PKCS#8 DER 私钥和 X.509 DER 公钥，不启动 Spring，
 * 不连接数据库、Redis 或腾讯云。输出目录必须已存在、为空并位于项目仓库之外；
 * 目标文件使用禁止覆盖方式创建。</p>
 *
 * @author codex
 @since 1.0.0
 */
public final class DisasterRecoveryManifestKeyGeneratorCli {

    static final String PRIVATE_KEY_FILE_NAME =
            "ai-friend-disaster-recovery-ed25519-private.pk8";
    static final String PUBLIC_KEY_FILE_NAME =
            "ai-friend-disaster-recovery-ed25519-public.der";
    private static final byte[] PAIR_PROOF =
            "ai-friend-disaster-recovery-manifest-key-pair-v1".getBytes(UTF_8);

    private DisasterRecoveryManifestKeyGeneratorCli() {
    }

    /**
     * 从当前进程环境读取非秘密路径并生成一对清单签名密钥。
     *
     * @param arguments 不接受任何命令行参数
     */
    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("灾备清单密钥生成工具不接受命令行参数");
        }
        KeyGenerationOptions options = options(System.getenv());
        GeneratedKeyFiles generated = generate(
                options.repositoryRoot(), options.outputDirectory());
        System.out.println("灾备清单 Ed25519 密钥生成成功");
        System.out.println("私钥文件：" + generated.privateKeyPath());
        System.out.println("公钥文件：" + generated.publicKeyPath());
        System.out.println("公钥 SHA-256：" + generated.publicKeySha256());
    }

    /**
     * 从指定进程环境读取生成参数。
     *
     * @param environment 当前进程环境
     * @return 已解析的非秘密路径参数
     */
    static KeyGenerationOptions options(Map<String, String> environment) {
        Objects.requireNonNull(environment, "进程环境不能为空");
        return new KeyGenerationOptions(
                absolutePath(environment, "AI_FRIEND_DR_KEY_REPOSITORY_ROOT"),
                absolutePath(environment, "AI_FRIEND_DR_KEY_OUTPUT_DIRECTORY"));
    }

    /**
     * 在受控仓库外目录生成并写入一对 Ed25519 密钥。
     *
     * @param repositoryRoot 项目仓库根目录
     * @param outputDirectory 已创建且为空的仓库外输出目录
     * @return 生成文件路径与公钥摘要
     */
    static GeneratedKeyFiles generate(Path repositoryRoot, Path outputDirectory) {
        Path repositoryRealPath = requireDirectory(repositoryRoot, "项目仓库根目录");
        Path outputRealPath = requireDirectory(outputDirectory, "密钥输出目录");
        if (outputRealPath.startsWith(repositoryRealPath)) {
            throw new IllegalArgumentException("灾备清单密钥输出目录必须位于项目仓库之外");
        }
        requireEmptyDirectory(outputRealPath);

        KeyPair keyPair = generateKeyPair();
        verifyKeyPair(keyPair);
        byte[] privateKey = requireEncodedKey(keyPair.getPrivate().getEncoded(), "Ed25519 私钥");
        byte[] publicKey = requireEncodedKey(keyPair.getPublic().getEncoded(), "Ed25519 公钥");
        Path privateKeyPath = outputRealPath.resolve(PRIVATE_KEY_FILE_NAME);
        Path publicKeyPath = outputRealPath.resolve(PUBLIC_KEY_FILE_NAME);
        try {
            Files.write(publicKeyPath, publicKey, CREATE_NEW, WRITE);
            Files.write(privateKeyPath, privateKey, CREATE_NEW, WRITE);
            return new GeneratedKeyFiles(
                    privateKeyPath,
                    publicKeyPath,
                    sha256Hex(publicKey));
        } catch (IOException exception) {
            throw new IllegalStateException("灾备清单密钥文件写入失败；禁止覆盖既有文件", exception);
        } finally {
            Arrays.fill(privateKey, (byte) 0);
            Arrays.fill(publicKey, (byte) 0);
        }
    }

    /**
     * 解析并约束必填绝对路径环境变量。
     *
     * @param environment 当前进程环境
     * @param name 环境变量名
     * @return 规范化绝对路径
     */
    private static Path absolutePath(Map<String, String> environment, String name) {
        String value = Objects.requireNonNullElse(environment.get(name), "").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少灾备清单密钥路径环境变量：" + name);
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("灾备清单密钥路径必须是绝对路径：" + name);
        }
        return path.normalize();
    }

    /**
     * 要求路径为真实存在的目录并解析链接后的实际位置。
     *
     * @param path 待检查路径
     * @param description 路径用途说明
     * @return 实际目录路径
     */
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

    /**
     * 要求输出目录为空，避免混入旧密钥或覆盖既有材料。
     *
     * @param outputDirectory 已解析的输出目录
     */
    private static void requireEmptyDirectory(Path outputDirectory) {
        try (var entries = Files.list(outputDirectory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("灾备清单密钥输出目录必须是全新空目录");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("灾备清单密钥输出目录无法读取", exception);
        }
    }

    /**
     * 使用当前 Java 17 安全提供方生成 Ed25519 密钥对。
     *
     * @return 新生成的密钥对
     */
    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 Ed25519", exception);
        }
    }

    /**
     * 在落盘前完成一次签名和验签，证明公私钥确实配对。
     *
     * @param keyPair 待验证密钥对
     */
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

    /**
     * 要求密钥提供方返回非空标准编码。
     *
     * @param encodedKey 标准编码密钥
     * @param description 密钥用途说明
     * @return 原编码数组
     */
    private static byte[] requireEncodedKey(byte[] encodedKey, String description) {
        if (encodedKey == null || encodedKey.length == 0) {
            throw new IllegalStateException(description + "缺少标准编码");
        }
        return encodedKey;
    }

    /**
     * 计算公开材料的 SHA-256 十六进制摘要。
     *
     * @param value 待摘要字节
     * @return 大写十六进制摘要
     */
    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
        }
    }

    /**
     * 本机密钥生成所需的非秘密路径参数。
     *
     * @param repositoryRoot 项目仓库根目录
     * @param outputDirectory 仓库外全新输出目录
     */
    record KeyGenerationOptions(Path repositoryRoot, Path outputDirectory) {
    }

    /**
     * 已生成密钥文件的最小公开结果。
     *
     * @param privateKeyPath PKCS#8 DER 私钥文件路径
     * @param publicKeyPath X.509 DER 公钥文件路径
     * @param publicKeySha256 公钥文件 SHA-256
     */
    record GeneratedKeyFiles(
            Path privateKeyPath,
            Path publicKeyPath,
            String publicKeySha256) {
    }
}
