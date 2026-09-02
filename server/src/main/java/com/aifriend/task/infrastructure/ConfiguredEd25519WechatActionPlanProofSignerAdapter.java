package com.aifriend.task.infrastructure;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.task.application.WechatActionPlanProofSignerPort;
import com.aifriend.task.application.WechatActionPlanSigningProperties;

/**
 * 从受控配置加载 PKCS#8 Ed25519 私钥的动作计划签名适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class ConfiguredEd25519WechatActionPlanProofSignerAdapter
        implements WechatActionPlanProofSignerPort {

    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,59}");

    private final WechatActionPlanSigningProperties properties;

    /**
     * 创建配置驱动的 Ed25519 签名适配器。
     *
     * @param properties 动作计划签名配置
     */
    public ConfiguredEd25519WechatActionPlanProofSignerAdapter(
            WechatActionPlanSigningProperties properties) {
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public boolean available() {
        return StringUtils.hasText(properties.keyId())
                && KEY_ID.matcher(properties.keyId()).matches()
                && StringUtils.hasText(properties.privateKeyPkcs8Base64());
    }

    /** {@inheritDoc} */
    @Override
    public String keyId() {
        if (!available()) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        return properties.keyId();
    }

    /** {@inheritDoc} */
    @Override
    public byte[] sign(byte[] canonicalBytes) {
        if (!available() || canonicalBytes == null || canonicalBytes.length == 0) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        }
        byte[] encodedPrivateKey = null;
        try {
            encodedPrivateKey = Base64.getDecoder()
                    .decode(properties.privateKeyPkcs8Base64());
            PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(canonicalBytes);
            return signature.sign();
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.ACTION_UNSUPPORTED);
        } finally {
            if (encodedPrivateKey != null) {
                Arrays.fill(encodedPrivateKey, (byte) 0);
            }
        }
    }
}
