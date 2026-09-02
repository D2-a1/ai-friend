package com.aifriend.contact.application;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.task.application.TaskContinuationContactProjectionPort;

/**
 * 向任务继续窗口提供最小 ACTIVE 联系人事实。
 *
 * <p>只使用 owner 范围内部 UUID 和状态，不解密微信主体、定位或备注。
 *
 * @author codex
 * @since 1.0.0
 */
@Component
public class ContactTaskContinuationProjectionService
        implements TaskContinuationContactProjectionPort {

    private final ContactBindingRepositoryPort repositoryPort;

    /**
     * 创建继续窗口联系人投影。
     *
     * @param repositoryPort owner 范围联系人持久化端口
     */
    public ContactTaskContinuationProjectionService(
            ContactBindingRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID ownerUserId, UUID contactId) {
        return repositoryPort.findByOwnerAndId(ownerUserId, contactId)
                .filter(binding -> binding.status() == ContactStatus.ACTIVE)
                .isPresent();
    }
}
