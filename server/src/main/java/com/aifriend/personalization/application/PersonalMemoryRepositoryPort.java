package com.aifriend.personalization.application;

import java.util.Optional;
import java.util.UUID;

import com.aifriend.personalization.domain.PersonalMemoryRecord;

/**
 * 长期个人偏好持久化端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface PersonalMemoryRepositoryPort {

    /**
     * 查询 owner 当前偏好记录。
     *
     * @param ownerUserId owner UUID
     * @return 当前记录或空
     */
    Optional<PersonalMemoryRecord> findByOwner(UUID ownerUserId);

    /**
     * 对 owner 当前偏好记录加写锁。
     *
     * @param ownerUserId owner UUID
     * @return 当前记录或空
     */
    Optional<PersonalMemoryRecord> findByOwnerForUpdate(UUID ownerUserId);

    /**
     * 保存当前偏好或删除墓碑。
     *
     * @param record 新记录
     * @return 已保存记录
     */
    PersonalMemoryRecord save(PersonalMemoryRecord record);
}
