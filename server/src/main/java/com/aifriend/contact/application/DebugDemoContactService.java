package com.aifriend.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 为缺少真实微信资料的个人 Debug MVP 准备唯一合成联系人。
 *
 * <p>该服务只在 dev/test Profile 注册。合成主体只以密文和 HMAC 形式进入既有联系人表，
 * 不携带微信定位，也不冒充亲友同意或真实本机验证。
 *
 * @author Codex
 * @since 1.0.0
 */
@Profile({"dev", "test"})
@Service
public class DebugDemoContactService {

    static final String SUBJECT = "debug-mvp-demo-contact-v1";

    private static final int MAX_TOTAL_CONTACT_OBJECTS = 20;
    private static final String REMARK = "体验联系人";
    private static final String RELATIONSHIP = "仅用于本机体验";
    private static final String POLICY_VERSION = "debug-mvp-demo-v1";

    private final ContactBindingRepositoryPort bindingRepositoryPort;
    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final ContactSummaryMapper summaryMapper;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final Clock clock;

    /**
     * 创建 Debug 体验联系人服务。
     *
     * @param bindingRepositoryPort owner 范围联系人仓储
     * @param aliasRepositoryPort owner 范围称呼仓储
     * @param summaryMapper 联系人最小响应映射器
     * @param sensitiveDataProtector 敏感字段加密与摘要器
     * @param clock UTC 时钟
     */
    public DebugDemoContactService(
            ContactBindingRepositoryPort bindingRepositoryPort,
            ContactAliasRepositoryPort aliasRepositoryPort,
            ContactSummaryMapper summaryMapper,
            SensitiveDataProtector sensitiveDataProtector,
            Clock clock) {
        this.bindingRepositoryPort = bindingRepositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.summaryMapper = summaryMapper;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.clock = clock;
    }

    /**
     * 创建、恢复或返回当前 owner 的唯一体验联系人。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @return 不含主体或定位的联系人最小结果
     * @throws BusinessException 联系人数量已满或记录状态异常时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ContactSummary ensure(UUID ownerUserId) {
        bindingRepositoryPort.lockOwner(ownerUserId);
        byte[] subjectHash = sensitiveDataProtector.subjectHmac(SUBJECT);
        ContactBinding saved;
        Optional<ContactBinding> existing = bindingRepositoryPort.findByOwnerAndSubjectForUpdate(
                ownerUserId, subjectHash);
        if (existing.isPresent()) {
            ContactBinding current = existing.get();
            saved = requireActive(current);
        } else {
            saved = bindingRepositoryPort.save(create(ownerUserId, subjectHash));
        }
        List<ContactAlias> aliases = saved.status() == ContactStatus.ACTIVE
                ? aliasRepositoryPort.findActiveByOwner(ownerUserId).stream()
                        .filter(alias -> alias.bindingId().equals(saved.id()))
                        .toList()
                : List.of();
        return summaryMapper.toSummary(saved, aliases);
    }

    private ContactBinding create(UUID ownerUserId, byte[] subjectHash) {
        if (bindingRepositoryPort.countOccupying(ownerUserId)
                >= MAX_TOTAL_CONTACT_OBJECTS) {
            throw new BusinessException(ErrorCode.CONTACT_LIMIT_REACHED);
        }
        Instant now = Instant.now(clock);
        return new ContactBinding(
                UUID.randomUUID(), ownerUserId, subjectHash,
                sensitiveDataProtector.encrypt(SUBJECT), null, null,
                sensitiveDataProtector.encrypt(REMARK), null, null,
                null, null, null, null, null, RELATIONSHIP,
                POLICY_VERSION, now, ContactStatus.ACTIVE_NO_ALIAS,
                ownerUserId, 0L, now, now, null);
    }

    private ContactBinding requireActive(ContactBinding existing) {
        if (existing.status() == ContactStatus.ACTIVE
                || existing.status() == ContactStatus.ACTIVE_NO_ALIAS) {
            return existing;
        }
        throw new BusinessException(ErrorCode.SESSION_CONFLICT);
    }
}
