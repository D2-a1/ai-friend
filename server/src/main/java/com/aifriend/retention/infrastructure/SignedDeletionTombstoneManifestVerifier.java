package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ed25519 删除墓碑快照根清单验证器。
 *
 * <p>外层 JSON 只承载版本、规范二进制负载和签名。签名覆盖快照编号、生成时间、数量、
 * 聚合摘要及每一页的数量和 SHA-256，避免依赖 JSON 字段顺序或空白规则。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
final class SignedDeletionTombstoneManifestVerifier {

    static final String SCHEMA_VERSION = "AIFDRS01";
    static final String SIGNATURE_ALGORITHM = "Ed25519";
    static final int MAXIMUM_DOCUMENT_BYTES = 1_500_000;
    private static final byte[] PAYLOAD_MAGIC = SCHEMA_VERSION.getBytes(US_ASCII);
    private static final int MAXIMUM_PAYLOAD_BYTES = 1_048_576;
    private static final int MAXIMUM_PAGE_COUNT = 10_000;
    private static final int MAXIMUM_ITEM_COUNT = 1_000_000;
    private static final int MAXIMUM_PAGE_SIZE = 100;
    private static final String SNAPSHOT_ID_PATTERN = "[A-Za-z0-9._:-]{8,128}";
    private final ObjectMapper objectMapper;

    SignedDeletionTombstoneManifestVerifier(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 解析器不能为空")
                .copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    VerifiedManifest verify(
            byte[] document,
            PublicKey publicKey,
            String expectedSnapshotId) {
        Objects.requireNonNull(publicKey, "清单公钥不能为空");
        if (document == null || document.length == 0
                || document.length > MAXIMUM_DOCUMENT_BYTES) {
            throw invalid();
        }
        SignedDocument signedDocument;
        try {
            signedDocument = objectMapper.readValue(document, SignedDocument.class);
        } catch (IOException exception) {
            throw invalid();
        }
        if (!SCHEMA_VERSION.equals(signedDocument.schemaVersion())
                || !SIGNATURE_ALGORITHM.equals(signedDocument.signatureAlgorithm())) {
            throw invalid();
        }
        byte[] payload = decodeBase64(signedDocument.payloadBase64(), MAXIMUM_PAYLOAD_BYTES);
        byte[] signature = decodeBase64(signedDocument.signatureBase64(), 64);
        if (signature.length != 64 || !verifySignature(payload, signature, publicKey)) {
            throw invalid();
        }
        VerifiedManifest manifest = decodePayload(payload, signature);
        if (!manifest.snapshotId().equals(expectedSnapshotId)) {
            throw invalid();
        }
        return manifest;
    }

    static byte[] encodePayload(
            String snapshotId,
            Instant createdAt,
            long expectedItemCount,
            int pageSize,
            byte[] aggregateHash,
            List<PageDescriptor> pages) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(PAYLOAD_MAGIC);
                writeString(output, snapshotId);
                writeString(output, createdAt.toString());
                output.writeLong(expectedItemCount);
                output.writeInt(pageSize);
                output.writeInt(pages.size());
                output.write(aggregateHash);
                for (PageDescriptor page : pages) {
                    output.writeInt(page.pageNumber());
                    output.writeInt(page.itemCount());
                    output.write(page.pageHash());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("删除墓碑根清单负载编码失败", exception);
        }
    }

    private VerifiedManifest decodePayload(byte[] payload, byte[] signature) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] magic = input.readNBytes(PAYLOAD_MAGIC.length);
            if (!Arrays.equals(magic, PAYLOAD_MAGIC)) {
                throw invalid();
            }
            String snapshotId = readString(input);
            String createdAtText = readString(input);
            long expectedItemCount = input.readLong();
            int pageSize = input.readInt();
            int pageCount = input.readInt();
            byte[] aggregateHash = input.readNBytes(32);
            if (!snapshotId.matches(SNAPSHOT_ID_PATTERN)
                    || expectedItemCount < 0L
                    || expectedItemCount > MAXIMUM_ITEM_COUNT
                    || pageSize < 1
                    || pageSize > MAXIMUM_PAGE_SIZE
                    || pageCount < 0
                    || pageCount > MAXIMUM_PAGE_COUNT
                    || aggregateHash.length != 32) {
                throw invalid();
            }
            Instant createdAt;
            try {
                createdAt = Instant.parse(createdAtText);
            } catch (DateTimeParseException exception) {
                throw invalid();
            }
            List<PageDescriptor> pages = new ArrayList<>(pageCount);
            long describedItems = 0L;
            for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
                int actualPageNumber = input.readInt();
                int itemCount = input.readInt();
                byte[] pageHash = input.readNBytes(32);
                if (actualPageNumber != pageNumber
                        || itemCount < 1
                        || itemCount > pageSize
                        || pageHash.length != 32) {
                    throw invalid();
                }
                describedItems += itemCount;
                pages.add(new PageDescriptor(actualPageNumber, itemCount, pageHash));
            }
            if (input.available() != 0
                    || describedItems != expectedItemCount
                    || (expectedItemCount == 0L) != pages.isEmpty()) {
                throw invalid();
            }
            return new VerifiedManifest(
                    snapshotId,
                    createdAt,
                    expectedItemCount,
                    pageSize,
                    aggregateHash,
                    pages,
                    payload,
                    signature);
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private static boolean verifySignature(
            byte[] payload,
            byte[] signatureBytes,
            PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境无法验证 Ed25519 清单签名", exception);
        }
    }

    private static byte[] decodeBase64(String encoded, int maximumBytes) {
        try {
            byte[] decoded = Base64.getDecoder().decode(
                    Objects.requireNonNullElse(encoded, ""));
            if (decoded.length == 0 || decoded.length > maximumBytes) {
                throw invalid();
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(UTF_8);
        if (bytes.length < 1 || bytes.length > 256) {
            throw invalid();
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 256) {
            throw invalid();
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw invalid();
        }
        return new String(bytes, UTF_8);
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("删除墓碑快照签名清单无效");
    }

    record PageDescriptor(int pageNumber, int itemCount, byte[] pageHash) {

        PageDescriptor {
            pageHash = Arrays.copyOf(pageHash, pageHash.length);
        }

        @Override
        public byte[] pageHash() {
            return Arrays.copyOf(pageHash, pageHash.length);
        }
    }

    record VerifiedManifest(
            String snapshotId,
            Instant createdAt,
            long expectedItemCount,
            int pageSize,
            byte[] aggregateHash,
            List<PageDescriptor> pages,
            byte[] signedPayload,
            byte[] signature) {

        VerifiedManifest {
            aggregateHash = Arrays.copyOf(aggregateHash, aggregateHash.length);
            pages = List.copyOf(pages);
            signedPayload = Arrays.copyOf(signedPayload, signedPayload.length);
            signature = Arrays.copyOf(signature, signature.length);
        }

        @Override
        public byte[] aggregateHash() {
            return Arrays.copyOf(aggregateHash, aggregateHash.length);
        }

        @Override
        public byte[] signedPayload() {
            return Arrays.copyOf(signedPayload, signedPayload.length);
        }

        @Override
        public byte[] signature() {
            return Arrays.copyOf(signature, signature.length);
        }
    }

    private record SignedDocument(
            String schemaVersion,
            String payloadBase64,
            String signatureAlgorithm,
            String signatureBase64) {
    }
}
