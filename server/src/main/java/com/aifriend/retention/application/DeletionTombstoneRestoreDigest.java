package com.aifriend.retention.application;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 删除墓碑恢复快照的版本化规范摘要计算器。
 *
 * <p>聚合摘要绑定快照编号、预期数量、每个墓碑 UUID 和密文信封摘要；清单摘要再绑定
 * 聚合摘要与可信恢复源证明。所有整数使用大端序，字符串使用 UTF-8 长度前缀。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
public final class DeletionTombstoneRestoreDigest {

    /** 有序对象聚合摘要协议头。 */
    private static final byte[] AGGREGATE_MAGIC = "AIFDRM01".getBytes(US_ASCII);
    /** 已验证清单摘要协议头。 */
    private static final byte[] MANIFEST_MAGIC = "AIFDRV01".getBytes(US_ASCII);
    /** 当前聚合摘要状态。 */
    private final MessageDigest digest;
    /** 是否已完成摘要。 */
    private boolean finished;

    /**
     * 创建当前快照的流式聚合摘要。
     *
     * @param snapshotId 快照编号
     * @param expectedItemCount 清单声明的对象总数
     */
    public DeletionTombstoneRestoreDigest(String snapshotId, long expectedItemCount) {
        digest = sha256();
        digest.update(AGGREGATE_MAGIC);
        updateString(digest, snapshotId);
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(expectedItemCount).array());
    }

    /**
     * 按清单顺序加入一个墓碑对象。
     *
     * @param entry 已受边界约束的恢复对象
     */
    public void update(DeletionTombstoneRestoreEntry entry) {
        update(entry.tombstoneId(), entry.envelope().envelopeHash());
    }

    /**
     * 按清单顺序加入一个墓碑编号和信封摘要。
     *
     * <p>该入口供离线快照发布器复用恢复端的同一聚合协议，不要求发布器构造或
     * 解密信封业务对象。</p>
     *
     * @param tombstoneId 墓碑 UUID
     * @param envelopeHash 32 字节密文信封 SHA-256
     */
    public void update(java.util.UUID tombstoneId, byte[] envelopeHash) {
        if (finished) {
            throw new IllegalStateException("灾备恢复聚合摘要已完成");
        }
        if (tombstoneId == null || envelopeHash == null || envelopeHash.length != 32) {
            throw new IllegalArgumentException("灾备恢复聚合摘要条目无效");
        }
        digest.update(ByteBuffer.allocate(Long.BYTES * 2)
                .putLong(tombstoneId.getMostSignificantBits())
                .putLong(tombstoneId.getLeastSignificantBits())
                .array());
        digest.update(envelopeHash);
    }

    /**
     * 完成并返回当前聚合摘要。
     *
     * @return 32 字节聚合摘要
     */
    public byte[] finish() {
        if (finished) {
            throw new IllegalStateException("灾备恢复聚合摘要不可重复完成");
        }
        finished = true;
        return digest.digest();
    }

    /**
     * 计算经恢复源认证且经应用层复核的规范清单摘要。
     *
     * @param manifest 已认证的不可变快照清单
     * @return 32 字节清单摘要
     */
    static byte[] manifestHash(DeletionTombstoneRestoreManifest manifest) {
        MessageDigest manifestDigest = sha256();
        manifestDigest.update(MANIFEST_MAGIC);
        updateString(manifestDigest, manifest.snapshotId());
        manifestDigest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(manifest.expectedItemCount())
                .array());
        manifestDigest.update(manifest.aggregateHash());
        manifestDigest.update(manifest.sourceProofHash());
        return manifestDigest.digest();
    }

    private static void updateString(MessageDigest target, String value) {
        byte[] bytes = value.getBytes(UTF_8);
        target.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        target.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
