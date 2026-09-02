package com.aifriend.identity.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * Android Keystore 设备证明与服务器公钥白名单门禁。
 *
 * <p>登录证明绑定一次性微信 code，刷新证明绑定一次性轮换 refresh token。
 * 原文只在调用内存短暂存在，不写入数据库、日志或审计。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class DeviceTrustService {

    private static final String LOGIN_DOMAIN = "ai-friend-device-login-v1";
    private static final String REFRESH_DOMAIN = "ai-friend-device-refresh-v1";
    private static final int MAX_PUBLIC_KEY_BYTES = 256;
    private static final int MAX_SIGNATURE_BYTES = 128;

    private final DeviceTrustProperties properties;
    private final DigestService digestService;
    private final ECParameterSpec expectedCurve;

    /**
     * 创建设备信任服务。
     *
     * @param properties 设备白名单配置
     * @param digestService SHA-256 服务
     */
    public DeviceTrustService(DeviceTrustProperties properties, DigestService digestService) {
        this.properties = properties;
        this.digestService = digestService;
        this.expectedCurve = loadExpectedCurve();
    }

    /**
     * 校验登录设备证明。
     *
     * @param code 微信一次性 code
     * @param publicKeySpkiBase64 Android Keystore X.509 SPKI 公钥
     * @param proofBase64 SHA256withECDSA 签名
     * @return 已验证设备身份；门禁关闭时摘要为空
     * @throws BusinessException 设备未放行或证明无效时抛出
     */
    public DeviceAuthentication verifyLogin(
            String code,
            String publicKeySpkiBase64,
            String proofBase64) {
        return verify(LOGIN_DOMAIN, code, publicKeySpkiBase64, proofBase64);
    }

    /**
     * 校验刷新令牌设备证明。
     *
     * @param refreshToken 一次性轮换刷新令牌
     * @param publicKeySpkiBase64 Android Keystore X.509 SPKI 公钥
     * @param proofBase64 SHA256withECDSA 签名
     * @return 已验证设备身份；门禁关闭时摘要为空
     * @throws BusinessException 设备未放行或证明无效时抛出
     */
    public DeviceAuthentication verifyRefresh(
            String refreshToken,
            String publicKeySpkiBase64,
            String proofBase64) {
        return verify(REFRESH_DOMAIN, refreshToken, publicKeySpkiBase64, proofBase64);
    }

    /**
     * 判断 JWT 中的设备摘要当前是否仍被放行。
     *
     * @param digestHex JWT 设备摘要
     * @return 门禁关闭或摘要仍在白名单时返回 true
     */
    public boolean isAllowedJwtDevice(String digestHex) {
        return !properties.enabled() || properties.isAllowed(digestHex);
    }

    private DeviceAuthentication verify(
            String domain,
            String oneTimeSecret,
            String publicKeySpkiBase64,
            String proofBase64) {
        if (!properties.enabled()) {
            return new DeviceAuthentication(null);
        }
        try {
            byte[] publicKeyBytes = decode(publicKeySpkiBase64, MAX_PUBLIC_KEY_BYTES);
            byte[] signatureBytes = decode(proofBase64, MAX_SIGNATURE_BYTES);
            ECPublicKey publicKey = parsePublicKey(publicKeyBytes);
            byte[] publicKeySha256 = digestService.sha256(publicKeyBytes);
            String digestHex = HexFormat.of().formatHex(publicKeySha256);
            if (!properties.isAllowed(digestHex)
                    || !verifySignature(publicKey, signatureBytes, canonical(domain, oneTimeSecret))) {
                throw new BusinessException(ErrorCode.DEVICE_NOT_ALLOWED);
            }
            return new DeviceAuthentication(publicKeySha256);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new BusinessException(ErrorCode.DEVICE_NOT_ALLOWED);
        }
    }

    private byte[] decode(String value, int maximumBytes) {
        if (value == null || value.isBlank() || value.length() > maximumBytes * 2) {
            throw new IllegalArgumentException("设备证明字段无效");
        }
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length == 0 || decoded.length > maximumBytes) {
            throw new IllegalArgumentException("设备证明字段无效");
        }
        return decoded;
    }

    private ECPublicKey parsePublicKey(byte[] encoded) throws GeneralSecurityException {
        var parsedPublicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(encoded));
        if (!(parsedPublicKey instanceof ECPublicKey publicKey)) {
            throw new GeneralSecurityException("设备公钥类型不受支持");
        }
        if (!sameCurve(publicKey.getParams(), expectedCurve)) {
            throw new GeneralSecurityException("设备公钥曲线不受支持");
        }
        return publicKey;
    }

    private boolean verifySignature(ECPublicKey publicKey, byte[] proof, byte[] canonical)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(canonical);
        return verifier.verify(proof);
    }

    private byte[] canonical(String domain, String secret) {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] secretDigest = digestService.sha256(
                secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                domainBytes.length + 1 + secretDigest.length);
        output.writeBytes(domainBytes);
        output.write(0);
        output.writeBytes(secretDigest);
        return output.toByteArray();
    }

    private ECParameterSpec loadExpectedCurve() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 secp256r1", exception);
        }
    }

    private boolean sameCurve(ECParameterSpec actual, ECParameterSpec expected) {
        return actual != null
                && actual.getCofactor() == expected.getCofactor()
                && actual.getOrder().equals(expected.getOrder())
                && actual.getGenerator().equals(expected.getGenerator())
                && actual.getCurve().equals(expected.getCurve());
    }
}
