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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.aifriend.retention.application.DeletionTombstoneEnvelopeCodecPort;
import com.aifriend.retention.application.DeletionTombstoneExportRecord;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.EncryptedDeletionTombstoneEnvelope;
import com.aifriend.shared.security.DigestService;

/**
 * 删除墓碑版本化规范字节与 AES-256-GCM 信封编解码器。
 *
 * <p>灾备密钥与在线数据密钥分离。信封携带非秘密 keyId，AES-GCM AAD 同时绑定
 * 固定协议头和 keyId；任何摘要、协议、长度、尾随字节或认证标签异常均失败关闭。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class AesGcmDeletionTombstoneEnvelopeCodec
        implements DeletionTombstoneEnvelopeCodecPort {

    /** 信封协议头与版本。 */
    private static final byte[] ENVELOPE_MAGIC = "AIFDR001".getBytes(US_ASCII);
    /** 规范墓碑负载版本。 */
    private static final int PAYLOAD_VERSION = 1;
    /** AES-GCM 随机 IV 字节数。 */
    private static final int IV_BYTES = 12;
    /** AES-GCM 认证标签位数。 */
    private static final int GCM_TAG_BITS = 128;
    /** 墓碑密文包数据库上限。 */
    private static final int MAX_ENVELOPE_BYTES = 2048;

    /** 灾备配置。 */
    private final DisasterRecoveryProperties properties;
    /** SHA-256 摘要组件。 */
    private final DigestService digestService;
    /** 密码安全随机源。 */
    private final SecureRandom secureRandom;
    /** 启用导出时加载的专用 AES 密钥。 */
    private final SecretKey exportKey;

    /**
     * 创建灾备墓碑信封编解码器。
     *
     * @param properties 灾备导出配置
     * @param digestService SHA-256 摘要组件
     */
    @Autowired
    public AesGcmDeletionTombstoneEnvelopeCodec(
            DisasterRecoveryProperties properties,
            DigestService digestService) {
        this(properties, digestService, new SecureRandom());
    }

    AesGcmDeletionTombstoneEnvelopeCodec(
            DisasterRecoveryProperties properties,
            DigestService digestService,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.digestService = digestService;
        this.secureRandom = secureRandom;
        this.exportKey = properties.exportEnabled() || properties.restoreMode()
                ? createExportKey(properties)
                : null;
    }

    /** {@inheritDoc} */
    @Override
    public EncryptedDeletionTombstoneEnvelope seal(DeletionTombstoneExportRecord record) {
        requireExportEnabledKey();
        byte[] plainPayload = null;
        try {
            plainPayload = encodePayload(record);
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, exportKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(properties.exportKeyId()));
            byte[] cipherPayload = cipher.doFinal(plainPayload);
            byte[] envelope = encodeEnvelope(properties.exportKeyId(), iv, cipherPayload);
            return new EncryptedDeletionTombstoneEnvelope(
                    properties.exportKeyId(), envelope, digestService.sha256(envelope));
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("删除墓碑灾备信封生成失败", exception);
        } finally {
            if (plainPayload != null) {
                Arrays.fill(plainPayload, (byte) 0);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public DeletionTombstoneExportRecord open(EncryptedDeletionTombstoneEnvelope envelope) {
        requireOpenEnabledKey();
        if (!properties.exportKeyId().equals(envelope.keyId())
                || !MessageDigest.isEqual(
                        digestService.sha256(envelope.encryptedEnvelope()),
                        envelope.envelopeHash())) {
            throw new IllegalStateException("删除墓碑灾备信封摘要或密钥编号无效");
        }
        try {
            ParsedEnvelope parsed = decodeEnvelope(envelope.encryptedEnvelope());
            if (!properties.exportKeyId().equals(parsed.keyId())) {
                throw new IllegalStateException("删除墓碑灾备信封内外密钥编号不一致");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    exportKey,
                    new GCMParameterSpec(GCM_TAG_BITS, parsed.iv()));
            cipher.updateAAD(aad(parsed.keyId()));
            byte[] plainPayload = cipher.doFinal(parsed.cipherPayload());
            try {
                return decodePayload(plainPayload);
            } finally {
                Arrays.fill(plainPayload, (byte) 0);
            }
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("删除墓碑灾备信封解密或解析失败", exception);
        }
    }

    private byte[] encodePayload(DeletionTombstoneExportRecord record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(PAYLOAD_VERSION);
            output.writeLong(record.tombstoneId().getMostSignificantBits());
            output.writeLong(record.tombstoneId().getLeastSignificantBits());
            output.write(record.subjectHash());
            output.writeLong(record.oldAccountGeneration());
            output.writeLong(record.acceptedAt().toEpochMilli());
            output.writeLong(record.completedAt().toEpochMilli());
            output.writeLong(record.reRegistrationNotBefore().toEpochMilli());
            writeString(output, record.policyVersion());
            output.writeLong(record.replayUntil().toEpochMilli());
        }
        return bytes.toByteArray();
    }

    private DeletionTombstoneExportRecord decodePayload(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_VERSION) {
                throw new IllegalStateException("删除墓碑灾备负载版本不受支持");
            }
            UUID tombstoneId = new UUID(input.readLong(), input.readLong());
            byte[] subjectHash = input.readNBytes(32);
            if (subjectHash.length != 32) {
                throw new IllegalStateException("删除墓碑灾备主体摘要长度无效");
            }
            DeletionTombstoneExportRecord record = new DeletionTombstoneExportRecord(
                    tombstoneId,
                    subjectHash,
                    input.readLong(),
                    Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()),
                    readString(input),
                    Instant.ofEpochMilli(input.readLong()));
            if (input.available() != 0) {
                throw new IllegalStateException("删除墓碑灾备负载存在尾随字节");
            }
            return record;
        }
    }

    private byte[] encodeEnvelope(String keyId, byte[] iv, byte[] cipherPayload)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(ENVELOPE_MAGIC);
            writeString(output, keyId);
            output.write(iv);
            output.writeInt(cipherPayload.length);
            output.write(cipherPayload);
        }
        byte[] envelope = bytes.toByteArray();
        if (envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalStateException("删除墓碑灾备信封超过大小上限");
        }
        return envelope;
    }

    private ParsedEnvelope decodeEnvelope(byte[] envelope) throws IOException {
        if (envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalStateException("删除墓碑灾备信封超过大小上限");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            byte[] magic = input.readNBytes(ENVELOPE_MAGIC.length);
            if (!Arrays.equals(ENVELOPE_MAGIC, magic)) {
                throw new IllegalStateException("删除墓碑灾备信封协议头无效");
            }
            String keyId = readString(input);
            byte[] iv = input.readNBytes(IV_BYTES);
            int cipherLength = input.readInt();
            if (iv.length != IV_BYTES || cipherLength < GCM_TAG_BITS / 8
                    || cipherLength > input.available()) {
                throw new IllegalStateException("删除墓碑灾备信封长度无效");
            }
            byte[] cipherPayload = input.readNBytes(cipherLength);
            if (cipherPayload.length != cipherLength || input.available() != 0) {
                throw new IllegalStateException("删除墓碑灾备信封存在截断或尾随字节");
            }
            return new ParsedEnvelope(keyId, iv, cipherPayload);
        }
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(UTF_8);
        if (bytes.length < 1 || bytes.length > 255) {
            throw new IllegalStateException("删除墓碑灾备字符串长度无效");
        }
        output.writeByte(bytes.length);
        output.write(bytes);
    }

    private String readString(DataInputStream input) throws IOException {
        int length = input.readUnsignedByte();
        byte[] bytes = input.readNBytes(length);
        if (length < 1 || bytes.length != length) {
            throw new IllegalStateException("删除墓碑灾备字符串截断");
        }
        return new String(bytes, UTF_8);
    }

    private byte[] aad(String keyId) {
        byte[] keyIdBytes = keyId.getBytes(UTF_8);
        byte[] aad = Arrays.copyOf(ENVELOPE_MAGIC, ENVELOPE_MAGIC.length + keyIdBytes.length);
        System.arraycopy(keyIdBytes, 0, aad, ENVELOPE_MAGIC.length, keyIdBytes.length);
        return aad;
    }

    private void requireExportEnabledKey() {
        if (!properties.exportEnabled() || exportKey == null) {
            throw new IllegalStateException("灾备墓碑导出未启用");
        }
    }

    private void requireOpenEnabledKey() {
        if ((!properties.exportEnabled() && !properties.restoreMode()) || exportKey == null) {
            throw new IllegalStateException("灾备墓碑信封解密未启用");
        }
    }

    private SecretKey createExportKey(DisasterRecoveryProperties currentProperties) {
        byte[] keyBytes = currentProperties.exportEnabled()
                ? currentProperties.requireExportEncryptionKey()
                : currentProperties.requireRestoreEncryptionKey();
        try {
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private record ParsedEnvelope(String keyId, byte[] iv, byte[] cipherPayload) {
    }
}
