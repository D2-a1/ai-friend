package com.aifriend.retention.infrastructure;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.aifriend.retention.application.DeletionTombstoneEnvelopeCodecPort;
import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DeletionTombstoneRestoreBatch;
import com.aifriend.retention.application.DeletionTombstoneRestoreDigest;
import com.aifriend.retention.application.DeletionTombstoneRestoreEntry;
import com.aifriend.retention.application.DeletionTombstoneRestoreManifest;
import com.aifriend.retention.application.DeletionTombstoneRestoreSourcePort;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryRestoreProperties;
import com.aifriend.shared.security.DigestService;

/**
 * 删除墓碑真实快照的本机只读恢复校验入口。
 *
 * <p>入口使用独立恢复 CAM 读取并认证显式快照，复用生产恢复源完成 Ed25519、
 * SSE-COS AES256、页与信封摘要核验，再使用灾备 AES-GCM 密钥逐条解密并复核全量
 * 聚合摘要。该进程不启动 Spring，不连接 MySQL 或 Redis，也没有 COS 写入或删除路径。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public final class DisasterRecoverySnapshotRestoreVerifierCli {

    /** 单次只读校验批次上限。 */
    private static final int BATCH_SIZE = 100;

    private DisasterRecoverySnapshotRestoreVerifierCli() {
    }

    /**
     * 从当前进程环境读取瞬时秘密并校验一个固定快照。
     *
     * @param arguments 不接受任何命令行参数
     */
    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("只读恢复校验工具不接受命令行参数");
        }
        VerificationOptions options = options(System.getenv());
        DigestService digestService = new DigestService();
        DisasterRecoveryProperties coreProperties = options.coreProperties();
        try (TencentCosDeletionTombstoneRestoreSourceAdapter source =
                new TencentCosDeletionTombstoneRestoreSourceAdapter(
                        options.restoreProperties(),
                        options.exportProperties(),
                        coreProperties,
                        digestService,
                        new ObjectMapper())) {
            AesGcmDeletionTombstoneEnvelopeCodec envelopeCodec =
                    new AesGcmDeletionTombstoneEnvelopeCodec(
                            coreProperties, digestService);
            SnapshotVerificationReceipt receipt = verifySnapshot(
                    source, envelopeCodec, digestService, options.snapshotId());
            System.out.println("COS 灾备快照只读恢复校验通过");
            System.out.println("快照编号：" + receipt.snapshotId());
            System.out.println("墓碑数量：" + receipt.itemCount());
            System.out.println("聚合摘要：" + HexFormat.of().withUpperCase()
                    .formatHex(receipt.aggregateHash()));
            System.out.println("未连接数据库，未创建、修改或删除任何 COS 对象");
        } catch (RuntimeException exception) {
            System.err.println("COS 灾备快照只读恢复校验失败");
            throw exception;
        }
    }

    /**
     * 从进程环境构建脱敏且边界完整的校验参数。
     *
     * @param environment 当前进程环境
     * @return 已完成格式校验的参数
     */
    static VerificationOptions options(Map<String, String> environment) {
        Objects.requireNonNull(environment, "进程环境不能为空");
        return new VerificationOptions(
                required(environment, "AI_FRIEND_DR_VERIFY_REGION"),
                required(environment, "AI_FRIEND_DR_VERIFY_BUCKET"),
                required(environment, "AI_FRIEND_DR_VERIFY_SNAPSHOT_ID"),
                required(environment, "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_VERIFY_RESTORE_SECRET_KEY"),
                optional(environment, "AI_FRIEND_DR_VERIFY_RESTORE_SESSION_TOKEN"),
                required(environment, "AI_FRIEND_DR_VERIFY_EXPORT_SECRET_ID"),
                required(environment, "AI_FRIEND_DR_VERIFY_EXPORT_KEY_ID"),
                required(environment, "AI_FRIEND_DR_VERIFY_EXPORT_KEY_BASE64"),
                required(environment, "AI_FRIEND_DR_VERIFY_MANIFEST_PUBLIC_KEY_X509_BASE64"),
                duration(environment, "AI_FRIEND_DR_VERIFY_CONNECT_TIMEOUT", "PT2S"),
                duration(environment, "AI_FRIEND_DR_VERIFY_READ_TIMEOUT", "PT5S"));
    }

    /**
     * 对已认证恢复源执行与正式恢复一致的只读完整性校验。
     *
     * @param sourcePort 可信只读恢复源
     * @param envelopeCodec AES-GCM 信封解密器
     * @param digestService SHA-256 服务
     * @param snapshotId 固定快照编号
     * @return 不含墓碑内容的校验回执
     */
    static SnapshotVerificationReceipt verifySnapshot(
            DeletionTombstoneRestoreSourcePort sourcePort,
            DeletionTombstoneEnvelopeCodecPort envelopeCodec,
            DigestService digestService,
            String snapshotId) {
        Objects.requireNonNull(sourcePort, "恢复源不能为空");
        Objects.requireNonNull(envelopeCodec, "信封解密器不能为空");
        Objects.requireNonNull(digestService, "摘要服务不能为空");
        DeletionTombstoneRestoreManifest manifest = sourcePort.openSnapshot(snapshotId);
        if (!snapshotId.equals(manifest.snapshotId())) {
            throw new IllegalStateException("只读恢复源返回了非当前绑定快照");
        }

        DeletionTombstoneRestoreDigest aggregate =
                new DeletionTombstoneRestoreDigest(snapshotId, manifest.expectedItemCount());
        Set<String> visitedCursors = new HashSet<>();
        String cursor = null;
        UUID lastTombstoneId = null;
        long verifiedCount = 0L;
        long batchCount = 0L;

        while (true) {
            DeletionTombstoneRestoreBatch batch =
                    sourcePort.readBatch(snapshotId, cursor, BATCH_SIZE);
            batchCount++;
            requireBatchBound(batchCount, manifest.expectedItemCount());
            for (DeletionTombstoneRestoreEntry entry : batch.entries()) {
                if (lastTombstoneId != null
                        && entry.tombstoneId().compareTo(lastTombstoneId) <= 0) {
                    throw new IllegalStateException("只读恢复墓碑 UUID 未保持全局严格升序");
                }
                DeletionTombstoneExportRecord record = envelopeCodec.open(entry.envelope());
                if (!entry.tombstoneId().equals(record.tombstoneId())) {
                    throw new IllegalStateException("只读恢复对象与信封墓碑 UUID 不一致");
                }
                aggregate.update(entry);
                lastTombstoneId = entry.tombstoneId();
                verifiedCount++;
                if (verifiedCount > manifest.expectedItemCount()) {
                    throw new IllegalStateException("只读恢复对象数超过快照清单");
                }
            }
            if (batch.complete()) {
                break;
            }
            if (verifiedCount >= manifest.expectedItemCount()
                    || !visitedCursors.add(batch.nextCursor())) {
                throw new IllegalStateException("只读恢复分页游标无进展或已重复");
            }
            cursor = batch.nextCursor();
        }

        byte[] actualAggregateHash = aggregate.finish();
        if (verifiedCount != manifest.expectedItemCount()
                || !digestService.constantTimeEquals(
                        manifest.aggregateHash(), actualAggregateHash)) {
            throw new IllegalStateException("只读恢复数量或聚合摘要不完整");
        }
        return new SnapshotVerificationReceipt(
                snapshotId, verifiedCount, actualAggregateHash);
    }

    private static void requireBatchBound(long batchCount, long expectedItemCount) {
        long maximumBatches = expectedItemCount == 0L ? 1L : expectedItemCount;
        if (batchCount > maximumBatches) {
            throw new IllegalStateException("只读恢复分页次数超过有界上限");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = optional(environment, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少只读恢复校验环境变量：" + name);
        }
        return value;
    }

    private static String optional(Map<String, String> environment, String name) {
        return Objects.requireNonNullElse(environment.get(name), "").trim();
    }

    private static Duration duration(
            Map<String, String> environment,
            String name,
            String defaultValue) {
        try {
            return Duration.parse(Objects.requireNonNullElse(
                    environment.get(name), defaultValue).trim());
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("只读恢复校验超时配置无效：" + name);
        }
    }

    /**
     * 只读恢复校验的瞬时进程参数。
     *
     * @param region COS 地域
     * @param bucket 完整私有 Bucket
     * @param snapshotId 固定快照编号
     * @param restoreSecretId 恢复身份 SecretId
     * @param restoreSecretKey 恢复身份 SecretKey
     * @param restoreSessionToken 可选临时令牌
     * @param exportSecretId 日常导出身份 SecretId
     * @param exportKeyId 灾备信封密钥编号
     * @param exportKeyBase64 灾备信封 AES 密钥
     * @param manifestPublicKeyBase64 Ed25519 清单公钥
     * @param connectTimeout 建连超时
     * @param readTimeout 读取超时
     */
    record VerificationOptions(
            String region,
            String bucket,
            String snapshotId,
            String restoreSecretId,
            String restoreSecretKey,
            String restoreSessionToken,
            String exportSecretId,
            String exportKeyId,
            String exportKeyBase64,
            String manifestPublicKeyBase64,
            Duration connectTimeout,
            Duration readTimeout) {

        VerificationOptions {
            TencentCosDisasterRecoveryRestoreProperties restore =
                    restoreProperties(
                            region,
                            bucket,
                            restoreSecretId,
                            restoreSecretKey,
                            restoreSessionToken,
                            manifestPublicKeyBase64,
                            connectTimeout,
                            readTimeout);
            DisasterRecoveryProperties core =
                    coreProperties(snapshotId, exportKeyId, exportKeyBase64);
            if (restore.secretId().equals(exportSecretId)) {
                throw new IllegalArgumentException("恢复身份不得复用日常导出身份");
            }
            region = restore.region();
            bucket = restore.bucket();
            snapshotId = core.restoreSnapshotId();
            restoreSecretId = restore.secretId();
            restoreSecretKey = restore.secretKey();
            restoreSessionToken = restore.sessionToken();
            exportSecretId = Objects.requireNonNullElse(exportSecretId, "").trim();
            exportKeyId = core.exportKeyId();
            exportKeyBase64 = Objects.requireNonNullElse(exportKeyBase64, "").trim();
            manifestPublicKeyBase64 = Objects.requireNonNullElse(
                    manifestPublicKeyBase64, "").trim();
            connectTimeout = restore.connectTimeout();
            readTimeout = restore.readTimeout();
        }

        TencentCosDisasterRecoveryRestoreProperties restoreProperties() {
            return restoreProperties(
                    region,
                    bucket,
                    restoreSecretId,
                    restoreSecretKey,
                    restoreSessionToken,
                    manifestPublicKeyBase64,
                    connectTimeout,
                    readTimeout);
        }

        TencentCosDisasterRecoveryProperties exportProperties() {
            return new TencentCosDisasterRecoveryProperties(
                    false,
                    region,
                    bucket,
                    exportSecretId,
                    "",
                    "",
                    connectTimeout,
                    readTimeout);
        }

        DisasterRecoveryProperties coreProperties() {
            return coreProperties(snapshotId, exportKeyId, exportKeyBase64);
        }

        @Override
        public String toString() {
            return "VerificationOptions[region=" + region
                    + ", bucket=***"
                    + ", snapshotId=***"
                    + ", restoreSecretId=***"
                    + ", restoreSecretKey=***"
                    + ", restoreSessionToken=***"
                    + ", exportSecretId=***"
                    + ", exportKeyId=" + exportKeyId
                    + ", exportKeyBase64=***"
                    + ", manifestPublicKeyBase64=***"
                    + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout + "]";
        }

        private static TencentCosDisasterRecoveryRestoreProperties restoreProperties(
                String region,
                String bucket,
                String restoreSecretId,
                String restoreSecretKey,
                String restoreSessionToken,
                String manifestPublicKeyBase64,
                Duration connectTimeout,
                Duration readTimeout) {
            return new TencentCosDisasterRecoveryRestoreProperties(
                    true,
                    region,
                    bucket,
                    restoreSecretId,
                    restoreSecretKey,
                    restoreSessionToken,
                    manifestPublicKeyBase64,
                    connectTimeout,
                    readTimeout);
        }

        private static DisasterRecoveryProperties coreProperties(
                String snapshotId,
                String exportKeyId,
                String exportKeyBase64) {
            return new DisasterRecoveryProperties(
                    true,
                    snapshotId,
                    false,
                    exportKeyId,
                    exportKeyBase64);
        }
    }

    /**
     * 不包含墓碑内容和秘密的只读校验回执。
     *
     * @param snapshotId 快照编号
     * @param itemCount 已校验墓碑数
     * @param aggregateHash 全量聚合摘要
     */
    record SnapshotVerificationReceipt(
            String snapshotId,
            long itemCount,
            byte[] aggregateHash) {

        SnapshotVerificationReceipt {
            Objects.requireNonNull(snapshotId, "快照编号不能为空");
            if (itemCount < 0L || aggregateHash == null || aggregateHash.length != 32) {
                throw new IllegalArgumentException("只读恢复校验回执无效");
            }
            aggregateHash = Arrays.copyOf(aggregateHash, aggregateHash.length);
        }

        @Override
        public byte[] aggregateHash() {
            return Arrays.copyOf(aggregateHash, aggregateHash.length);
        }
    }
}
