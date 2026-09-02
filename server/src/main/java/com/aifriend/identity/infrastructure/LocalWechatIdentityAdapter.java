package com.aifriend.identity.infrastructure;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.aifriend.identity.application.WechatIdentityPort;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 仅供 dev/test 使用的本地微信身份适配器。
 *
 * <p>只接受 {@code local_<subject>.<nonce>} 测试 code，并在当前进程内保证一次性消费；
 * 同一 subject 的不同 nonce 映射到同一个本地账号，不调用真实微信服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
@Profile({"dev", "test"})
public class LocalWechatIdentityAdapter implements WechatIdentityPort {

    /** 当前进程已消费测试 code 的 SHA-256 摘要集合。 */
    private final Set<String> consumedCodeDigests = ConcurrentHashMap.newKeySet();

    /**
     * 创建仅限 dev/test profile 的本地微信身份适配器。
     */
    public LocalWechatIdentityAdapter() {
    }

    /**
     * 消费本地测试 code。
     *
     * @param code local_ 前缀且包含一次性 nonce 的测试 code
     * @return 不暴露原 code 的稳定本地主体
     * @throws BusinessException 当 code 格式错误或已被消费时抛出
     */
    @Override
    public WechatIdentity exchange(String code) {
        if (code == null || !code.startsWith("local_") || !code.contains(".")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信授权信息无效");
        }
        int nonceSeparator = code.lastIndexOf('.');
        String localSubject = code.substring("local_".length(), nonceSeparator);
        String nonce = code.substring(nonceSeparator + 1);
        if (localSubject.isBlank() || nonce.length() < 8) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信授权信息无效");
        }
        String codeDigest = sha256Base64(code);
        if (!consumedCodeDigests.add(codeDigest)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信授权信息已使用或失效");
        }
        return new WechatIdentity("local-subject:" + sha256Base64(localSubject));
    }

    /**
     * 计算不含填充的 URL 安全 SHA-256 摘要文本。
     *
     * @param value 待摘要字符串
     * @return URL 安全 Base64 摘要
     * @throws IllegalStateException 当运行环境不支持 SHA-256 时抛出
     */
    private String sha256Base64(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
