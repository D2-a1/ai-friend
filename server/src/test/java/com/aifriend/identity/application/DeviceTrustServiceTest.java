package com.aifriend.identity.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

class DeviceTrustServiceTest {

    private static final String LOGIN_CODE = "local_owner.12345678";
    private static final String LOGIN_DOMAIN = "ai-friend-device-login-v1";

    @Test
    void shouldVerifyAllowedP256DeviceProof() throws Exception {
        KeyPair keyPair = generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(publicKey));
        DeviceTrustService service = new DeviceTrustService(
                new DeviceTrustProperties(true, List.of(fingerprint)),
                new DigestService());

        DeviceAuthentication authentication = service.verifyLogin(
                LOGIN_CODE,
                Base64.getEncoder().encodeToString(publicKey),
                sign(keyPair, LOGIN_DOMAIN, LOGIN_CODE));

        assertArrayEquals(
                MessageDigest.getInstance("SHA-256").digest(publicKey),
                authentication.publicKeySha256());
    }

    @Test
    void shouldRejectUnlistedDeviceProof() throws Exception {
        KeyPair keyPair = generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        DeviceTrustService service = new DeviceTrustService(
                new DeviceTrustProperties(true, List.of("a".repeat(64))),
                new DigestService());

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> service.verifyLogin(
                        LOGIN_CODE,
                        Base64.getEncoder().encodeToString(publicKey),
                        sign(keyPair, LOGIN_DOMAIN, LOGIN_CODE)));

        assertEquals(ErrorCode.DEVICE_NOT_ALLOWED, failure.errorCode());
    }

    @Test
    void shouldRejectTamperedProofFromAllowedDevice() throws Exception {
        KeyPair keyPair = generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        DeviceTrustService service = new DeviceTrustService(
                new DeviceTrustProperties(true, List.of(
                        HexFormat.of().formatHex(sha256(publicKey)))),
                new DigestService());

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> service.verifyLogin(
                        LOGIN_CODE,
                        Base64.getEncoder().encodeToString(publicKey),
                        sign(keyPair, LOGIN_DOMAIN, LOGIN_CODE + "-other")));

        assertEquals(ErrorCode.DEVICE_NOT_ALLOWED, failure.errorCode());
    }

    @Test
    void shouldKeepOldClientCompatibleWhileGateDisabled() {
        DeviceTrustService service = new DeviceTrustService(
                new DeviceTrustProperties(false, List.of()),
                new DigestService());

        assertNull(service.verifyLogin(LOGIN_CODE, null, null).publicKeySha256());
    }

    @Test
    void shouldRejectEnabledEmptyOrMalformedAllowlist() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeviceTrustProperties(true, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeviceTrustProperties(false, List.of("not-a-sha256")));
    }

    @Test
    void shouldBindTokenFamilyToDeviceDigestWithoutCreatingDeviceLimit() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V31__token_family_device_binding.sql"));

        assertTrue(migration.contains("device_public_key_sha256 BINARY(32) NULL"));
        assertTrue(migration.contains("idx_token_family_device_status"));
        assertFalse(migration.contains("device_limit"));
        assertFalse(migration.contains("device_count"));
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private String sign(KeyPair keyPair, String domain, String secret) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical(domain, secret));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private byte[] canonical(String domain, String secret) throws Exception {
        byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        byte[] secretDigest = MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8));
        byte[] canonical = new byte[domainBytes.length + 1 + secretDigest.length];
        System.arraycopy(domainBytes, 0, canonical, 0, domainBytes.length);
        System.arraycopy(secretDigest, 0, canonical, domainBytes.length + 1, secretDigest.length);
        return canonical;
    }
}
