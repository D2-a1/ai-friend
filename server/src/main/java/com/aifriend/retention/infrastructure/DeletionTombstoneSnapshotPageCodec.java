package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 删除墓碑快照有界页索引编解码器。
 *
 * <p>每页最多描述一百个墓碑 UUID 与对应密文信封 SHA-256；页文件自身的摘要由
 * Ed25519 根清单绑定。页内必须保持 UUID 严格升序且不得包含尾随字节。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
final class DeletionTombstoneSnapshotPageCodec {

    static final int MAXIMUM_PAGE_BYTES = 8_192;
    private static final byte[] PAGE_MAGIC = "AIFDRP01".getBytes(US_ASCII);
    private static final int MAXIMUM_PAGE_ITEMS = 100;

    private DeletionTombstoneSnapshotPageCodec() {
    }

    static List<IndexedEnvelope> decode(
            byte[] pageContent,
            String expectedSnapshotId,
            int expectedPageNumber,
            int expectedItemCount) {
        if (pageContent == null || pageContent.length == 0
                || pageContent.length > MAXIMUM_PAGE_BYTES) {
            throw invalid();
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(pageContent))) {
            byte[] magic = input.readNBytes(PAGE_MAGIC.length);
            String snapshotId = readString(input);
            int pageNumber = input.readInt();
            int itemCount = input.readInt();
            if (!Arrays.equals(magic, PAGE_MAGIC)
                    || !expectedSnapshotId.equals(snapshotId)
                    || pageNumber != expectedPageNumber
                    || itemCount != expectedItemCount
                    || itemCount < 1
                    || itemCount > MAXIMUM_PAGE_ITEMS) {
                throw invalid();
            }
            List<IndexedEnvelope> entries = new ArrayList<>(itemCount);
            UUID previousId = null;
            for (int index = 0; index < itemCount; index++) {
                UUID tombstoneId = new UUID(input.readLong(), input.readLong());
                byte[] envelopeHash = input.readNBytes(32);
                if (envelopeHash.length != 32
                        || previousId != null
                        && tombstoneId.compareTo(previousId) <= 0) {
                    throw invalid();
                }
                entries.add(new IndexedEnvelope(tombstoneId, envelopeHash));
                previousId = tombstoneId;
            }
            if (input.available() != 0) {
                throw invalid();
            }
            return List.copyOf(entries);
        } catch (IOException exception) {
            throw invalid();
        }
    }

    static byte[] encode(
            String snapshotId,
            int pageNumber,
            List<IndexedEnvelope> entries) {
        if (snapshotId == null
                || pageNumber < 0
                || entries == null
                || entries.isEmpty()
                || entries.size() > MAXIMUM_PAGE_ITEMS) {
            throw invalid();
        }
        UUID previousId = null;
        for (IndexedEnvelope entry : entries) {
            if (entry == null
                    || previousId != null
                    && entry.tombstoneId().compareTo(previousId) <= 0) {
                throw invalid();
            }
            previousId = entry.tombstoneId();
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(PAGE_MAGIC);
                writeString(output, snapshotId);
                output.writeInt(pageNumber);
                output.writeInt(entries.size());
                for (IndexedEnvelope entry : entries) {
                    output.writeLong(entry.tombstoneId().getMostSignificantBits());
                    output.writeLong(entry.tombstoneId().getLeastSignificantBits());
                    output.write(entry.envelopeHash());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAXIMUM_PAGE_BYTES) {
                throw invalid();
            }
            return encoded;
        } catch (IOException exception) {
            throw new IllegalStateException("删除墓碑快照页编码失败", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(UTF_8);
        if (bytes.length < 1 || bytes.length > 128) {
            throw invalid();
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 128) {
            throw invalid();
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw invalid();
        }
        return new String(bytes, UTF_8);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("删除墓碑快照页索引无效");
    }

    record IndexedEnvelope(UUID tombstoneId, byte[] envelopeHash) {

        IndexedEnvelope {
            if (tombstoneId == null || envelopeHash == null || envelopeHash.length != 32) {
                throw invalid();
            }
            envelopeHash = Arrays.copyOf(envelopeHash, envelopeHash.length);
        }

        @Override
        public byte[] envelopeHash() {
            return Arrays.copyOf(envelopeHash, envelopeHash.length);
        }
    }
}
