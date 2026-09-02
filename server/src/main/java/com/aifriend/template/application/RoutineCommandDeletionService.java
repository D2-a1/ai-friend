package com.aifriend.template.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 当前 owner 日常指令模板全量清除服务。
 *
 * <p>服务在短事务内锁定 owner 命名空间，复验可选版本和幂等语义，
 * 物理删除最多三十条模板密文后记录不可逆的删除数量与时间。
 * 联系人、称呼和四类安全指令不在本服务的访问范围内。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RoutineCommandDeletionService {

    /** 单个 owner 允许的日常指令模板硬上限。 */
    private static final int MAX_ROUTINE_COMMAND_TEMPLATES = 30;

    private final RoutineCommandTemplateStorePort storePort;
    private final RoutineCommandLearningQueuePort learningQueuePort;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建日常指令模板清除服务。
     *
     * @param storePort 日常模板持久化端口
     * @param learningQueuePort 未完成学习任务作废端口
     * @param digestService 摘要与常量时间比较服务
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public RoutineCommandDeletionService(
            RoutineCommandTemplateStorePort storePort,
            RoutineCommandLearningQueuePort learningQueuePort,
            DigestService digestService,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.storePort = storePort;
        this.learningQueuePort = learningQueuePort;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 清除当前 owner 的全部日常指令模板。
     *
     * @param ownerUserId 已验证 JWT 派生的 owner UUID
     * @param idempotencyKey 删除幂等键，不得进入日志
     * @param command 二次确认与可选命名空间版本
     * @return 首次实际删除数量和完成时间；同键同正文重放原结果
     * @throws BusinessException 未确认、版本冲突、幂等冲突或数据超出硬上限时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public RoutineCommandDeletionView deleteAll(
            UUID ownerUserId,
            String idempotencyKey,
            RoutineCommandDeletionCommand command) {
        validateRequest(ownerUserId, idempotencyKey, command);
        byte[] idempotencyKeyHash = digestService.sha256(idempotencyKey);
        byte[] requestHash = digestService.sha256(command.fingerprintInput());
        Instant now = Instant.now(clock);
        long namespaceVersion = storePort.lockNamespace(ownerUserId, now);

        RoutineCommandDeletionRecord replay = storePort.findDeletion(
                ownerUserId, idempotencyKeyHash).orElse(null);
        if (replay != null) {
            if (!digestService.constantTimeEquals(replay.requestHash(), requestHash)) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return new RoutineCommandDeletionView(
                    replay.deletedCount(), replay.deletedAt());
        }
        if (command.expectedVersion() != null
                && command.expectedVersion().longValue() != namespaceVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }

        int existingCount = storePort.countByOwner(ownerUserId);
        if (existingCount < 0 || existingCount > MAX_ROUTINE_COMMAND_TEMPLATES) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        learningQueuePort.cancelOutstandingByOwner(
                ownerUserId, "USER_CLEARED", now);
        int deletedCount = storePort.deleteAllByOwner(ownerUserId);
        if (deletedCount != existingCount) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        long nextVersion = Math.addExact(namespaceVersion, 1L);
        storePort.updateNamespace(ownerUserId, namespaceVersion, nextVersion, now);
        storePort.saveDeletion(
                ownerUserId,
                idempotencyKeyHash,
                requestHash,
                command.expectedVersion(),
                deletedCount,
                nextVersion,
                now);
        auditEventPort.append(
                ownerUserId, "ROUTINE_COMMAND_DELETE_ALL", "SUCCESS", null, now);
        return new RoutineCommandDeletionView(deletedCount, now);
    }

    private void validateRequest(
            UUID ownerUserId,
            String idempotencyKey,
            RoutineCommandDeletionCommand command) {
        if (ownerUserId == null
                || command == null
                || !command.confirmed()
                || !StringUtils.hasText(idempotencyKey)
                || idempotencyKey.length() < 16
                || idempotencyKey.length() > 128
                || (command.expectedVersion() != null && command.expectedVersion() < 1)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
