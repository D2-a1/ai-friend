package com.aifriend.template.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 日常指令学习任务的短事务领取、快照、提交和退避服务。
 *
 * <p>音频读取、解码、MFCC/DTW 和模板加解密都不得在本服务事务内执行。
 * 模板提交与全量清除共用 owner 命名空间锁，防止旧任务在清除后复活模板。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RoutineCommandLearningTransactionService {

    private static final int MAXIMUM_TEMPLATES = 30;
    private static final int MAXIMUM_ATTEMPTS = 8;
    private static final Duration MAXIMUM_BACKOFF = Duration.ofMinutes(15);

    private static final Comparator<RoutineCommandTemplateRecord> EVICTION_ORDER =
            Comparator.comparingInt(RoutineCommandTemplateRecord::usageCount)
                    .thenComparing(RoutineCommandTemplateRecord::lastConfirmedAt)
                    .thenComparing(RoutineCommandTemplateRecord::id);

    private final RoutineCommandLearningQueuePort queuePort;
    private final RoutineCommandTemplateStorePort templateStorePort;
    private final AuditEventPort auditEventPort;
    private final Clock clock;

    /**
     * 创建日常指令学习短事务服务。
     *
     * @param queuePort 学习 Outbox 端口
     * @param templateStorePort owner 模板命名空间端口
     * @param auditEventPort 去标识化审计端口
     * @param clock UTC 时钟
     */
    public RoutineCommandLearningTransactionService(
            RoutineCommandLearningQueuePort queuePort,
            RoutineCommandTemplateStorePort templateStorePort,
            AuditEventPort auditEventPort,
            Clock clock) {
        this.queuePort = queuePort;
        this.templateStorePort = templateStorePort;
        this.auditEventPort = auditEventPort;
        this.clock = clock;
    }

    /**
     * 有界领取一批学习任务。
     *
     * @param leaseDuration 单次租约时长
     * @param limit 最大领取数
     * @return 已取得随机租约的任务
     */
    @Transactional(rollbackFor = Exception.class)
    public List<RoutineCommandLearningJob> claimReady(
            Duration leaseDuration,
            int limit) {
        Instant now = Instant.now(clock);
        return queuePort.claimReady(now, now.plus(leaseDuration), limit);
    }

    /**
     * 锁定命名空间并取得后续事务外计算使用的模板快照。
     *
     * @param claimedJob 已取得租约的任务
     * @return 仍有效的模板快照；任务已被清除或音频过期时为空
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<RoutineCommandTemplateSnapshot> snapshot(
            RoutineCommandLearningJob claimedJob) {
        Instant now = Instant.now(clock);
        long namespaceVersion = templateStorePort.lockNamespace(
                claimedJob.ownerUserId(), now);
        RoutineCommandLearningJob locked = queuePort.findClaimedForUpdate(
                        claimedJob.id(), claimedJob.leaseToken())
                .orElse(null);
        if (locked == null) {
            return Optional.empty();
        }
        verifySameJob(claimedJob, locked);
        if (!locked.sourceRetentionUntil().isAfter(now)) {
            queuePort.markSkipped(
                    locked.id(), locked.leaseToken(), "SOURCE_EXPIRED", now);
            return Optional.empty();
        }
        List<RoutineCommandTemplateRecord> templates =
                templateStorePort.findByOwnerAndIntent(
                        locked.ownerUserId(), locked.intent());
        if (templates.size() > MAXIMUM_TEMPLATES) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return Optional.of(new RoutineCommandTemplateSnapshot(
                namespaceVersion, templates));
    }

    /**
     * 锁内复验模板快照后合并或新增模板，并原子完成学习任务。
     *
     * @param claimedJob 已取得租约的任务
     * @param snapshotVersion 事务外计算所依据的命名空间版本
     * @param mergeTarget 可空的重复模板
     * @param newTemplate 不重复时的加密新模板；合并时必须为空
     */
    @Transactional(rollbackFor = Exception.class)
    public void apply(
            RoutineCommandLearningJob claimedJob,
            long snapshotVersion,
            RoutineCommandTemplateMatch mergeTarget,
            RoutineCommandTemplateWrite newTemplate) {
        Instant now = Instant.now(clock);
        long currentVersion = templateStorePort.lockNamespace(
                claimedJob.ownerUserId(), now);
        if (currentVersion != snapshotVersion) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        RoutineCommandLearningJob locked = queuePort.findClaimedForUpdate(
                        claimedJob.id(), claimedJob.leaseToken())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SESSION_CONFLICT));
        verifySameJob(claimedJob, locked);
        if (!locked.sourceRetentionUntil().isAfter(now)
                || (mergeTarget == null) == (newTemplate == null)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }

        if (mergeTarget != null) {
            templateStorePort.incrementUsage(
                    locked.ownerUserId(), mergeTarget.templateId(),
                    mergeTarget.templateVersion(), now);
        } else {
            validateNewTemplate(locked, newTemplate);
            List<RoutineCommandTemplateRecord> allTemplates =
                    templateStorePort.findAllByOwner(locked.ownerUserId());
            if (allTemplates.size() > MAXIMUM_TEMPLATES) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            if (allTemplates.size() == MAXIMUM_TEMPLATES) {
                RoutineCommandTemplateRecord victim = allTemplates.stream()
                        .min(EVICTION_ORDER)
                        .orElseThrow(() -> new BusinessException(
                                ErrorCode.SESSION_CONFLICT));
                templateStorePort.deleteExact(
                        locked.ownerUserId(), victim.id(), victim.version());
            }
            templateStorePort.insert(locked.ownerUserId(), newTemplate);
        }
        long nextVersion = Math.addExact(currentVersion, 1L);
        templateStorePort.updateNamespace(
                locked.ownerUserId(), currentVersion, nextVersion, now);
        queuePort.markDone(locked.id(), locked.leaseToken(), now);
        auditEventPort.append(
                locked.ownerUserId(), "ROUTINE_COMMAND_LEARN", "SUCCESS", null, now);
    }

    /**
     * 在独立短事务内将失败任务永久跳过或有界退避。
     *
     * @param claimedJob 已取得租约的任务
     * @param reasonCode 不含敏感内容的稳定原因码
     * @param permanent 是否为永久失败
     */
    @Transactional(rollbackFor = Exception.class)
    public void settleFailure(
            RoutineCommandLearningJob claimedJob,
            String reasonCode,
            boolean permanent) {
        RoutineCommandLearningJob locked = queuePort.findClaimedForUpdate(
                        claimedJob.id(), claimedJob.leaseToken())
                .orElse(null);
        if (locked == null) {
            return;
        }
        verifySameJob(claimedJob, locked);
        Instant now = Instant.now(clock);
        boolean exhausted = locked.attempts() >= MAXIMUM_ATTEMPTS;
        if (permanent || exhausted || !locked.sourceRetentionUntil().isAfter(now)) {
            queuePort.markSkipped(
                    locked.id(), locked.leaseToken(), reasonCode, now);
            return;
        }
        long seconds = Math.min(
                MAXIMUM_BACKOFF.toSeconds(),
                30L << Math.min(locked.attempts() - 1, 5));
        queuePort.markRetry(
                locked.id(), locked.leaseToken(), reasonCode,
                now.plusSeconds(seconds), now);
    }

    private void validateNewTemplate(
            RoutineCommandLearningJob job,
            RoutineCommandTemplateWrite template) {
        boolean valid = template != null
                && template.intent() == job.intent()
                && template.dialectCode().equals(job.dialectCode())
                && template.dialectPackageVersion().equals(
                        job.dialectPackageVersion())
                && template.templateModelVersion().equals(
                        job.templateModelVersion())
                && template.thresholdVersion().equals(job.thresholdVersion())
                && template.templateCipher().length > 28
                && template.templateCipher().length <= 262_172
                && template.templateDigest().length == 32;
        if (!valid) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    private void verifySameJob(
            RoutineCommandLearningJob expected,
            RoutineCommandLearningJob actual) {
        boolean same = expected.id().equals(actual.id())
                && expected.taskSessionId().equals(actual.taskSessionId())
                && expected.ownerUserId().equals(actual.ownerUserId())
                && expected.audioObjectId().equals(actual.audioObjectId())
                && expected.intent() == actual.intent()
                && expected.actionStartMs() == actual.actionStartMs()
                && expected.actionEndMs() == actual.actionEndMs()
                && expected.dialectCode().equals(actual.dialectCode())
                && expected.dialectPackageVersion().equals(
                        actual.dialectPackageVersion())
                && expected.templateModelVersion().equals(
                        actual.templateModelVersion())
                && expected.thresholdVersion().equals(actual.thresholdVersion())
                && expected.leaseToken().equals(actual.leaseToken());
        if (!same) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
    }
}
