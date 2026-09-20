package com.aifriend.contact.application;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.WechatActionContactProjectionPort;
import com.aifriend.task.application.WechatActionContactSnapshot;

/**
 * 将联系人域的已验证稳定定位投影给当前任务动作计划。
 *
 * <p>稳定定位来源版本与联系人资料页的本机验证规则版本是两套独立契约。动作计划
 * 只接受邀请页明确提交的微信号定位来源，并要求联系人已完成本机资料页验证。当前
 * 客户端微信版本和页面规则由任务域执行能力白名单校验；本服务不会把邀请绑定时可空的
 * 历史微信版本误当成当前执行环境门禁。
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
            String expectedLocatorVersion) {
        Optional<ContactBinding> found = repositoryPort.findByOwnerAndIdForUpdate(
                ownerUserId, contactId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ContactBinding binding = found.get();
        boolean invalid = binding.status() != ContactStatus.ACTIVE
                || binding.wechatLocatorCipher() == null
                || binding.wechatLocatorHash() == null
                || binding.verifiedAt() == null
                || !StringUtils.hasText(binding.localVerificationVersion())
                || !WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION.equals(
                        expectedLocatorVersion);
        if (invalid) {
            return Optional.empty();
        }
        try {
            String stableLocator = sensitiveDataProtector.decrypt(
                    binding.wechatLocatorCipher());
            String normalizedLocator = WechatLocatorPolicy.normalizeInvitationWechatId(
                    stableLocator);
            if (!stableLocator.equals(normalizedLocator)) {
                return Optional.empty();
            }
            byte[] expectedLocatorHash = sensitiveDataProtector.subjectHmac(
                    WechatLocatorPolicy.HMAC_DOMAIN + normalizedLocator);
            if (!MessageDigest.isEqual(
                    binding.wechatLocatorHash(), expectedLocatorHash)) {
                return Optional.empty();
            }
            return Optional.of(new WechatActionContactSnapshot(
                    binding.id(), binding.version(), normalizedLocator,
                    binding.wechatVersion(), expectedLocatorVersion));
        } catch (IllegalStateException | BusinessException exception) {
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
