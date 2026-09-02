package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aifriend.shared.security.DigestService;

/**
 * 删除墓碑可信独立介质导出工作器。
 *
 * <p>密文包先以条件更新固定，再在数据库事务外调用独立介质。只有回读摘要精确匹配，
 * 才将回执证明摘要和导出时间原子写入墓碑；失败按 30—900 秒退避并复用同一密文包。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class DeletionTombstoneExportWorker {

    /** 脱敏记录删除墓碑导出失败的日志组件。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(
            DeletionTombstoneExportWorker.class);
    /** 删除墓碑导出稳定阶段码。 */
    private static final String FAILURE_STAGE = "DELETION_TOMBSTONE_EXPORT";
    /** 单次最多导出墓碑数。 */
    private static final int BATCH_SIZE = 10;

    /** 灾备导出配置。 */
    private final DisasterRecoveryProperties properties;
    /** 墓碑导出状态端口。 */
    private final DeletionTombstoneExportRepositoryPort repositoryPort;
    /** 墓碑密文信封编解码端口。 */
    private final DeletionTombstoneEnvelopeCodecPort envelopeCodecPort;
    /** 独立灾备介质端口。 */
    private final DeletionTombstoneExportPort exportPort;
    /** SHA-256 摘要组件。 */
    private final DigestService digestService;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建删除墓碑灾备导出工作器。
     *
     * @param properties 灾备导出配置
     * @param repositoryPort 墓碑导出状态端口
     * @param envelopeCodecPort 墓碑密文信封编解码端口
     * @param exportPort 独立灾备介质端口
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     */
    public DeletionTombstoneExportWorker(
            DisasterRecoveryProperties properties,
            DeletionTombstoneExportRepositoryPort repositoryPort,
            DeletionTombstoneEnvelopeCodecPort envelopeCodecPort,
            DeletionTombstoneExportPort exportPort,
            DigestService digestService,
            Clock clock) {
        this.properties = properties;
        this.repositoryPort = repositoryPort;
        this.envelopeCodecPort = envelopeCodecPort;
        this.exportPort = exportPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 有界导出一批到达重试时间的删除墓碑。
     *
     * @return 本次完成可信导出确认的墓碑数；导出未启用时返回 0
     * @throws RuntimeException 数据库读取、固定包生成或状态写入失败时抛出
     */
    public int processReady() {
        if (!properties.exportEnabled()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        List<DeletionTombstoneExportCandidate> candidates =
                repositoryPort.listReady(now, BATCH_SIZE);
        int exported = 0;
        for (DeletionTombstoneExportCandidate candidate : candidates) {
            EncryptedDeletionTombstoneEnvelope envelope = prepare(candidate, now);
            if (envelope != null && export(candidate, envelope, now)) {
                exported++;
            }
        }
        return exported;
    }

    private EncryptedDeletionTombstoneEnvelope prepare(
            DeletionTombstoneExportCandidate candidate,
            Instant now) {
        if (candidate.preparedEnvelope() != null) {
            return candidate.preparedEnvelope();
        }
        EncryptedDeletionTombstoneEnvelope envelope =
                envelopeCodecPort.seal(candidate.record());
        return repositoryPort.prepare(
                candidate.record().tombstoneId(), envelope, now) ? envelope : null;
    }

    private boolean export(
            DeletionTombstoneExportCandidate candidate,
            EncryptedDeletionTombstoneEnvelope envelope,
            Instant now) {
        try {
            DeletionTombstoneExportReceipt receipt = exportPort.export(
                    candidate.record().tombstoneId(), envelope);
            if (!digestService.constantTimeEquals(
                    envelope.envelopeHash(), receipt.storedEnvelopeHash())) {
                throw new IllegalStateException("独立灾备介质回读摘要不一致");
            }
            byte[] receiptHash = digestService.sha256(receipt.receiptProof());
            return repositoryPort.markExported(
                    candidate.record().tombstoneId(),
                    envelope.envelopeHash(),
                    now,
                    receiptHash);
        } catch (RuntimeException exception) {
            Instant nextAttemptAt = now.plus(retryDelay(candidate.retryCount()));
            LOGGER.warn(
                    "可恢复后台任务失败 stage={} errorType={} retryCount={} nextAttemptAt={}",
                    FAILURE_STAGE,
                    exception.getClass().getSimpleName(),
                    candidate.retryCount(),
                    nextAttemptAt);
            repositoryPort.markRetry(
                    candidate.record().tombstoneId(),
                    envelope.envelopeHash(),
                    now,
                    nextAttemptAt);
            return false;
        }
    }

    private Duration retryDelay(int retryCount) {
        long delaySeconds = Math.min(900L, 30L << Math.min(retryCount, 5));
        return Duration.ofSeconds(delaySeconds);
    }
}
