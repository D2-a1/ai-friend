package com.aifriend.contact.application;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.WechatActionContactProjectionPort;
import com.aifriend.task.application.WechatActionContactSnapshot;

/**
 * 将联系人域的已验证稳定定位投影给当前任务动作计划。
 *
 * <p>通话动作只返回本机验证时的历史版本作诊断；消息动作要求本服务在解密定位
 * 前精确比较历史验证版本与当前客户端版本。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class ContactWechatActionProjectionService
        implements WechatActionContactProjectionPort {

    private final ContactBindingRepositoryPort repositoryPort;
    private final SensitiveDataProtector sensitiveDataProtector;

    /**
     * 创建联系人动作计划只读投影服务。
     *
     * @param repositoryPort owner 范围联系人仓储端口
     * @param sensitiveDataProtector 稳定定位解密器
     */
    public ContactWechatActionProjectionService(
            ContactBindingRepositoryPort repositoryPort,
            SensitiveDataProtector sensitiveDataProtector) {
        this.repositoryPort = repositoryPort;
        this.sensitiveDataProtector = sensitiveDataProtector;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<WechatActionContactSnapshot> findVerifiedForUpdate(
            UUID ownerUserId,
            UUID contactId,
            String expectedWechatVersion,
            String expectedLocatorVersion,
            boolean requireExactWechatVersion) {
        Optional<ContactBinding> found = repositoryPort.findByOwnerAndIdForUpdate(
                ownerUserId, contactId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ContactBinding binding = found.get();
        boolean invalid = binding.status() != ContactStatus.ACTIVE
                || binding.wechatLocatorCipher() == null
                || binding.wechatLocatorHash() == null
                || !expectedLocatorVersion.equals(binding.localVerificationVersion())
                || (requireExactWechatVersion
                        && !expectedWechatVersion.equals(binding.wechatVersion()));
        if (invalid) {
            return Optional.empty();
        }
        try {
            String stableLocator = sensitiveDataProtector.decrypt(
                    binding.wechatLocatorCipher());
            if (!StringUtils.hasText(stableLocator)) {
                return Optional.empty();
            }
            return Optional.of(new WechatActionContactSnapshot(
                    binding.id(), binding.version(), stableLocator,
                    binding.wechatVersion(), binding.localVerificationVersion()));
        } catch (IllegalStateException exception) {
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isDebugDemoContact(UUID ownerUserId, UUID contactId) {
        return repositoryPort.findByOwnerAndId(ownerUserId, contactId)
                .filter(binding -> binding.status() == ContactStatus.ACTIVE
                        || binding.status() == ContactStatus.ACTIVE_NO_ALIAS)
                .map(binding -> Arrays.equals(
                        binding.contactSubjectHash(),
                        sensitiveDataProtector.subjectHmac(DebugDemoContactService.SUBJECT)))
                .orElse(false);
    }
}
