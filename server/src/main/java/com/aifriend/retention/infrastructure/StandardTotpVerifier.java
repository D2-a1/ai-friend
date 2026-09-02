package com.aifriend.retention.infrastructure;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 标准六位 TOTP 验证器。
 *
 * <p>使用兼容主流认证器的 HMAC-SHA1、三十秒时间步和前后各一个时间窗口。
 * 返回匹配时间步而非布尔值，供 Redis 完成跨实例单次消费。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
final class StandardTotpVerifier {

    /** TOTP HMAC 算法。 */
    private static final String HMAC_ALGORITHM = "HmacSHA1";
    /** 标准 TOTP 时间步秒数。 */
    private static final long TIME_STEP_SECONDS = 30L;
    /** 六位验证码模数。 */
    private static final int CODE_MODULUS = 1_000_000;
    /** 允许当前时间步前后各一个窗口。 */
    private static final int ALLOWED_SKEW_WINDOWS = 1;

    /** 解码后的 TOTP 秘密，仅驻留当前适配器进程内存。 */
    private final byte[] secret;

    StandardTotpVerifier(String secretBase32) {
        this.secret = decodeBase32(secretBase32);
    }

    long findMatchingCounter(byte[] credentialProof, Instant now) {
        if (!isSixAsciiDigits(credentialProof)) {
            return -1L;
        }
        long currentCounter = now.getEpochSecond() / TIME_STEP_SECONDS;
        long matchedCounter = -1L;
        for (int offset = -ALLOWED_SKEW_WINDOWS;
                offset <= ALLOWED_SKEW_WINDOWS;
                offset++) {
            long candidateCounter = currentCounter + offset;
            byte[] expected = codeAt(candidateCounter);
            if (MessageDigest.isEqual(expected, credentialProof)) {
                matchedCounter = candidateCounter;
            }
            Arrays.fill(expected, (byte) 0);
        }
        return matchedCounter;
    }

    byte[] codeAt(long counter) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES)
                    .putLong(counter)
                    .array());
            int offset = digest[digest.length - 1] & 0x0F;
            int binaryCode = ((digest[offset] & 0x7F) << 24)
                    | ((digest[offset + 1] & 0xFF) << 16)
                    | ((digest[offset + 2] & 0xFF) << 8)
                    | (digest[offset + 3] & 0xFF);
            String code = String.format(java.util.Locale.ROOT, "%06d", binaryCode % CODE_MODULUS);
            Arrays.fill(digest, (byte) 0);
            return code.getBytes(US_ASCII);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("运行环境无法验证 TOTP", exception);
        }
    }

    private static boolean isSixAsciiDigits(byte[] credentialProof) {
        if (credentialProof == null || credentialProof.length != 6) {
            return false;
        }
        int invalid = 0;
        for (byte character : credentialProof) {
            invalid |= character < '0' || character > '9' ? 1 : 0;
        }
        return invalid == 0;
    }

    private static byte[] decodeBase32(String encoded) {
        byte[] output = new byte[encoded.length() * 5 / 8];
        int buffer = 0;
        int bitsInBuffer = 0;
        int outputIndex = 0;
        for (int index = 0; index < encoded.length(); index++) {
            buffer = (buffer << 5) | base32Value(encoded.charAt(index));
            bitsInBuffer += 5;
            if (bitsInBuffer >= 8) {
                bitsInBuffer -= 8;
                output[outputIndex++] = (byte) (buffer >> bitsInBuffer);
                buffer &= (1 << bitsInBuffer) - 1;
            }
        }
        if (outputIndex != output.length || buffer != 0) {
            Arrays.fill(output, (byte) 0);
            throw new IllegalArgumentException("TOTP Base32 秘密无法完整解码");
        }
        return output;
    }

    private static int base32Value(char character) {
        if (character >= 'A' && character <= 'Z') {
            return character - 'A';
        }
        if (character >= '2' && character <= '7') {
            return character - '2' + 26;
        }
        throw new IllegalArgumentException("TOTP Base32 秘密包含非法字符");
    }
}
