package com.aifriend.retrieval.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨进程持久额度预留；只有首次GRANTED可立即进行一次调用。
 * DUPLICATE不是再次外发许可，任何存储异常都不得降级为GRANTED。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeQuotaPort {
    /**
     * 以持久操作身份预留一次请求/收费尝试；超时及无usage也不退款。
     * @param reservation 已授权应用层创建的操作身份，不接收正文
     * @return 固定决定
     */
    Decision reserve(Reservation reservation);

    /** 只有GRANTED允许一次新调用。 */
    enum Decision {
        /** 首次预留成功，允许一次调用。 */
        GRANTED,
        /** 相同操作已预留，不允许再次外发。 */
        DUPLICATE,
        /** 持久额度不足。 */
        LIMIT_EXCEEDED,
        /** 操作期限已到。 */
        EXPIRED,
        /** 相关额度通道未启用。 */
        DISABLED
    }
    /** 请求速率与三种收费阶段；图谱不包含任何收费阶段。 */
    enum Phase {
        /** 在线请求速率预留。 */
        REQUEST,
        /** 在线问题向量化费用预留。 */
        QUERY_EMBEDDING,
        /** 公开知识答案生成费用预留。 */
        ANSWER_GENERATION,
        /** 后台公开文档向量化费用预留。 */
        IMPORT_EMBEDDING
    }

    /**
     * 单次可持久识别的尝试；相同身份不同profile/owner/deadline为冲突。
     * @param operationId 服务端持久request/job ID
     * @param ownerId 请求主体；公开导入不保存普通用户身份
     * @param phase 固定调用阶段
     * @param profileId 外部配置版本；REQUEST固定LOCAL
     * @param attempt 跨进程保留的尝试序号
     * @param sequence 导入批次0到1999，在线阶段固定0
     * @param deadline 持久任务截止，不能重试时顺延
     */
    record Reservation(UUID operationId, Optional<UUID> ownerId, Phase phase,
            String profileId, int attempt, int sequence, Instant deadline) {
        /** 固定阶段/尝试上界，阻止应用误传无限重试或私人查询到导入通道。 */
        public Reservation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(deadline, "deadline");
            if (profileId == null || !profileId.matches("[A-Za-z0-9._:-]{1,100}")
                    || deadline.getNano() % 1_000_000 != 0 || attempt < 1 || sequence < 0
                    || (phase == Phase.IMPORT_EMBEDDING
                        ? ownerId.isPresent() || attempt > 3 || sequence >= 2000
                        : ownerId.isEmpty() || sequence != 0 || attempt > (phase == Phase.ANSWER_GENERATION ? 2 : 1))
                    || (phase == Phase.REQUEST && !"LOCAL".equals(profileId))) {
                throw new IllegalArgumentException("INVALID_QUOTA_RESERVATION");
            }
        }
        @Override public String toString() { return "Reservation[phase=" + phase + ", values=redacted]"; }
    }
}
