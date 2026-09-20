package com.aifriend.retrieval.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.aifriend.retrieval.domain.KnowledgeImportJob;

/**
 * 导入任务的持久全局认领与失败释放边界；不能据此绕过发布时再次校验。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeImportLeasePort {
    /**
     * 使用数据库时钟及全局控制行锁认领已登记任务。
     * @param jobId 已登记任务
     * @param token 新随机令牌
     * @param duration 有界租约
     * @return 未轮到、达到期限或其他任务仍持有全局租约时empty；数据库故障抛出
     */
    Optional<Claim> claim(UUID jobId, UUID token, Duration duration);

    /**
     * 当前有效worker失败后原子更新任务并释放全局租约。
     * @param claim 当前认领材料
     * @param reason 固定失败原因
     * @param retryable 是否允许总次数以内的退避
     * @return 持久化后的任务
     */
    KnowledgeImportJob fail(Claim claim, KnowledgeImportJob.Failure reason, boolean retryable);

    /**
     * 认领时冻结的发布条件；所有字段仍须发布事务重新锁定核验。
     * @param job 持久化PROCESSING任务
     * @param controlVersion 认领后全局控制版本
     * @param corpusRevision 当时源修订号
     */
    record Claim(KnowledgeImportJob job, long controlVersion, long corpusRevision) {
        /** 禁止构造未认领的发布材料。 */
        public Claim {
            Objects.requireNonNull(job, "job");
            if (job.state() != KnowledgeImportJob.State.PROCESSING || controlVersion < 1 || corpusRevision < 0) {
                throw new IllegalArgumentException("INVALID_IMPORT_CLAIM");
            }
        }
        @Override public String toString() { return "Claim[lease=redacted]"; }
    }
}
