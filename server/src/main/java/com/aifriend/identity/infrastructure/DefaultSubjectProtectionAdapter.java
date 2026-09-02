package com.aifriend.identity.infrastructure;

import org.springframework.stereotype.Component;

import com.aifriend.identity.application.SubjectProtectionPort;
import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 默认微信主体保护适配器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class DefaultSubjectProtectionAdapter implements SubjectProtectionPort {

    private final SensitiveDataProtector protector;

    /**
     * 创建主体保护适配器。
     *
     * @param protector 敏感数据保护器
     */
    public DefaultSubjectProtectionAdapter(SensitiveDataProtector protector) {
        this.protector = protector;
    }

    /**
     * 加密并摘要微信主体。
     *
     * @param subject 微信稳定主体
     * @return 受保护主体
     */
    @Override
    public ProtectedWechatSubject protect(String subject) {
        return new ProtectedWechatSubject(protector.encrypt(subject), protector.subjectHmac(subject));
    }
}
