package com.aifriend.retention.infrastructure;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Ed25519 删除墓碑快照根清单签名器。
 *
 * <p>签名器只处理已经规范化的二进制负载，并在返回 JSON 前使用对应公钥调用 B1
 * 验证器自检，防止私钥、公钥不匹配或协议编码漂移。</p>
 *
 * @author codex
 * @since 1.0.0
 */
final class SignedDeletionTombstoneManifestWriter {

    private final ObjectMapper objectMapper;
    private final SignedDeletionTombstoneManifestVerifier verifier;

    SignedDeletionTombstoneManifestWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 编码器不能为空").copy();
        this.verifier = new SignedDeletionTombstoneManifestVerifier(objectMapper);
    }

    byte[] write(
            String snapshotId,
            Instant createdAt,
            long expectedItemCount,
            int pageSize,
            byte[] aggregateHash,
            List<SignedDeletionTombstoneManifestVerifier.PageDescriptor> pages,
            PrivateKey privateKey,
            PublicKey publicKey) {
        Objects.requireNonNull(privateKey, "清单私钥不能为空");
        Objects.requireNonNull(publicKey, "清单公钥不能为空");
        byte[] payload = SignedDeletionTombstoneManifestVerifier.encodePayload(
                snapshotId,
                createdAt,
                expectedItemCount,
                pageSize,
                aggregateHash,
                pages);
        byte[] signature = sign(payload, privateKey);
        try {
            byte[] document = objectMapper.writeValueAsBytes(new SignedDocument(
                    SignedDeletionTombstoneManifestVerifier.SCHEMA_VERSION,
                    Base64.getEncoder().encodeToString(payload),
                    SignedDeletionTombstoneManifestVerifier.SIGNATURE_ALGORITHM,
                    Base64.getEncoder().encodeToString(signature)));
            verifier.verify(document, publicKey, snapshotId);
            return document;
        } catch (IOException exception) {
            throw new IllegalStateException("删除墓碑快照根清单编码失败", exception);
        } finally {
            java.util.Arrays.fill(payload, (byte) 0);
            java.util.Arrays.fill(signature, (byte) 0);
        }
    }

    private static byte[] sign(byte[] payload, PrivateKey privateKey) {
        try {
            Signature signer = Signature.getInstance(
                    SignedDeletionTombstoneManifestVerifier.SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            signer.update(payload);
            return signer.sign();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境无法签发 Ed25519 清单", exception);
        }
    }

    private record SignedDocument(
            String schemaVersion,
            String payloadBase64,
            String signatureAlgorithm,
            String signatureBase64) {
    }
}
