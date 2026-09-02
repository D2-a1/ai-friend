package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.security.DigestService;

/**
 * 灾备实例上线前的删除墓碑全量恢复编排器。
 *
 * <p>可信恢复源读取和 AES-GCM 解密均在数据库事务外完成；仓储端口只为每个不超过
 * 100 条的批次开启短事务。只有顺序、数量、聚合摘要和旧账户复活核验全部通过，才写入
 * 当前启动的最小验证事实。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class DeletionTombstoneRestoreCoordinator {

    /** 单次恢复分页与数据库事务上限。 */
    private static final int BATCH_SIZE = 100;
    /** 灾备配置。 */
    private final DisasterRecoveryProperties properties;
    /** 经身份认证的不可变恢复源。 */
    private final DeletionTombstoneRestoreSourcePort sourcePort;
    /** 墓碑恢复数据库端口。 */
    private final DeletionTombstoneRestoreRepositoryPort repositoryPort;
    /** AES-GCM 信封编解码端口。 */
    private final DeletionTombstoneEnvelopeCodecPort envelopeCodec;
    /** 非秘密标识摘要组件。 */
    private final DigestService digestService;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建删除墓碑恢复编排器。
     *
     * @param properties 灾备配置
     * @param sourcePort 经身份认证的恢复源
     * @param repositoryPort 墓碑恢复数据库端口
     * @param envelopeCodec AES-GCM 信封编解码端口
     * @param digestService SHA-256 摘要组件
     * @param clock UTC 时钟
     */
    public DeletionTombstoneRestoreCoordinator(
            DisasterRecoveryProperties properties,
            DeletionTombstoneRestoreSourcePort sourcePort,
            DeletionTombstoneRestoreRepositoryPort repositoryPort,
            DeletionTombstoneEnvelopeCodecPort envelopeCodec,
            DigestService digestService,
            Clock clock) {
        this.properties = properties;
        this.sourcePort = sourcePort;
        this.repositoryPort = repositoryPort;
        this.envelopeCodec = envelopeCodec;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 对当前启动绑定的可信快照执行全量重放和一致性核验。
     *
     * @return 仅驻留当前启动内存的恢复报告
     * @throws IllegalStateException 快照、分页、信封、数据库事实或复活核验异常时抛出
     */
    public DeletionTombstoneRestoreReport restoreAndVerify() {
        String snapshotId = properties.requireRestoreSnapshotId();
        DeletionTombstoneRestoreManifest manifest = sourcePort.openSnapshot(snapshotId);
        if (!snapshotId.equals(manifest.snapshotId())) {
            throw new IllegalStateException("灾备恢复源返回了非当前绑定快照");
        }

        DeletionTombstoneRestoreDigest aggregate =
                new DeletionTombstoneRestoreDigest(snapshotId, manifest.expectedItemCount());
        Set<String> visitedCursors = new HashSet<>();
        String cursor = null;
        UUID lastTombstoneId = null;
        long replayedCount = 0L;
        long pageCount = 0L;

        while (true) {
            DeletionTombstoneRestoreBatch batch =
                    sourcePort.readBatch(snapshotId, cursor, BATCH_SIZE);
            pageCount++;
            requirePageBound(pageCount, manifest.expectedItemCount());
            List<DeletionTombstoneExportRecord> records = new ArrayList<>(batch.entries().size());
            for (DeletionTombstoneRestoreEntry entry : batch.entries()) {
                if (lastTombstoneId != null && entry.tombstoneId().compareTo(lastTombstoneId) <= 0) {
                    throw new IllegalStateException("灾备恢复墓碑 UUID 未保持全局严格升序");
                }
                DeletionTombstoneExportRecord record = envelopeCodec.open(entry.envelope());
                if (!entry.tombstoneId().equals(record.tombstoneId())) {
                    throw new IllegalStateException("灾备恢复对象与信封墓碑 UUID 不一致");
                }
                aggregate.update(entry);
                records.add(record);
                lastTombstoneId = entry.tombstoneId();
                replayedCount++;
                if (replayedCount > manifest.expectedItemCount()) {
                    throw new IllegalStateException("灾备恢复对象数超过快照清单");
                }
            }
            if (!records.isEmpty()) {
                repositoryPort.replayBatch(List.copyOf(records), Instant.now(clock));
            }
            if (batch.complete()) {
                break;
            }
            if (replayedCount >= manifest.expectedItemCount()
                    || !visitedCursors.add(batch.nextCursor())) {
                throw new IllegalStateException("灾备恢复分页游标无进展或已重复");
            }
            cursor = batch.nextCursor();
        }

        if (replayedCount != manifest.expectedItemCount()
                || !digestService.constantTimeEquals(aggregate.finish(), manifest.aggregateHash())) {
            throw new IllegalStateException("灾备恢复数量或聚合摘要不完整");
        }
        if (repositoryPort.countResurrectedAccounts() != 0L) {
            throw new IllegalStateException("灾备恢复发现旧代在线账号，拒绝上线");
        }

        Instant verifiedAt = Instant.now(clock);
        byte[] manifestHash = DeletionTombstoneRestoreDigest.manifestHash(manifest);
        repositoryPort.recordVerification(
                digestService.sha256(snapshotId),
                manifestHash,
                manifest.expectedItemCount(),
                replayedCount,
                manifest.sourceProofHash(),
                verifiedAt);
        return new DeletionTombstoneRestoreReport(replayedCount, manifestHash, verifiedAt);
    }

    private void requirePageBound(long pageCount, long expectedItemCount) {
        long maximumPages = expectedItemCount == 0L ? 1L : expectedItemCount;
        if (pageCount > maximumPages) {
            throw new IllegalStateException("灾备恢复分页次数超过有界上限");
        }
    }
}
