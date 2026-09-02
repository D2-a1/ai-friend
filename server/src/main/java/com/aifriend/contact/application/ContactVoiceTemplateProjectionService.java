package com.aifriend.contact.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.template.application.VoiceTemplateContactProjectionPort;
import com.aifriend.template.application.VoiceTemplateSummary;

/**
 * 向语音模板清单提供 owner 范围联系人称呼元数据的只读服务。
 *
 * <p>本服务封装联系人称呼持久化端口，模板域不直接依赖 contact JPA。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactVoiceTemplateProjectionService
        implements VoiceTemplateContactProjectionPort {

    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final ContactAliasMapper aliasMapper;

    /**
     * 创建联系人称呼语音模板投影服务。
     *
     * @param aliasRepositoryPort owner 范围称呼持久化端口
     * @param aliasMapper 称呼元数据映射器
     */
    public ContactVoiceTemplateProjectionService(
            ContactAliasRepositoryPort aliasRepositoryPort,
            ContactAliasMapper aliasMapper) {
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.aliasMapper = aliasMapper;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<VoiceTemplateSummary> listContactAliases(UUID ownerUserId) {
        return aliasRepositoryPort.findActiveByOwner(ownerUserId).stream()
                .map(alias -> {
                    ContactAliasSummary summary = aliasMapper.toSummary(alias);
                    return new VoiceTemplateSummary(
                            summary.id(), "CONTACT_ALIAS",
                            PublicIdCodec.contactId(alias.bindingId()), summary.id(), null, null,
                            summary.dialectCode(), summary.dialectPackageVersion(),
                            summary.modelVersion(), summary.thresholdVersion(),
                            summary.compatibility(), alias.updatedAt());
                })
                .toList();
    }
}
