package com.aifriend.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.identity.domain.RefreshTokenStatus;
import com.aifriend.identity.domain.StoredRefreshToken;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.IdentitySecurityProperties;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void shouldRotateRefreshTokenAndRevokeFamilyOnReuse() {
        UserAccount user = new UserAccount(UUID.randomUUID(), UserStatus.ACTIVE, 1, NOW);
        InMemoryRefreshTokenRepository refreshTokens = new InMemoryRefreshTokenRepository();
        TokenTransactionService transactionService = new TokenTransactionService(
                refreshTokens,
                new FixedUserAccountPort(user),
                new RefreshTokenCodec(new DigestService()),
                (account, issuedAt, deviceDigest) ->
                        new AccessToken("access-" + UUID.randomUUID(), issuedAt.plusSeconds(900)),
                (actorUserId, action, result, reasonCode, occurredAt) -> { },
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TokenService tokenService = new TokenService(
                transactionService,
                new DeviceTrustService(
                        new DeviceTrustProperties(false, List.of()),
                        new DigestService()));

        TokenPairResult first = tokenService.issue(user, null);
        TokenPairResult rotated = tokenService.rotate(first.refreshToken(), null, null);

        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        BusinessException reuse = assertThrows(
                BusinessException.class,
                () -> tokenService.rotate(first.refreshToken(), null, null));
        assertEquals(ErrorCode.AUTH_REQUIRED, reuse.errorCode());
        assertTrue(refreshTokens.familyRevoked);
        assertThrows(
                BusinessException.class,
                () -> tokenService.rotate(rotated.refreshToken(), null, null));
    }

    @Test
    void shouldRevokeFamilyWhenAllowedDeviceDiffersFromBoundDevice() throws Exception {
        UserAccount user = new UserAccount(UUID.randomUUID(), UserStatus.ACTIVE, 1, NOW);
        KeyPair boundDevice = generateKeyPair();
        KeyPair otherAllowedDevice = generateKeyPair();
        byte[] boundDigest = sha256(boundDevice.getPublic().getEncoded());
        InMemoryRefreshTokenRepository refreshTokens = new InMemoryRefreshTokenRepository();
        TokenTransactionService transactionService = new TokenTransactionService(
                refreshTokens,
                new FixedUserAccountPort(user),
                new RefreshTokenCodec(new DigestService()),
                (account, issuedAt, deviceDigest) ->
                        new AccessToken("access-" + UUID.randomUUID(), issuedAt.plusSeconds(900)),
                (actorUserId, action, result, reasonCode, occurredAt) -> { },
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DeviceTrustService deviceTrustService = new DeviceTrustService(
                new DeviceTrustProperties(true, List.of(
                        HexFormat.of().formatHex(boundDigest),
                        HexFormat.of().formatHex(sha256(otherAllowedDevice.getPublic().getEncoded())))),
                new DigestService());
        TokenService tokenService = new TokenService(transactionService, deviceTrustService);
        TokenPairResult first = tokenService.issue(user, boundDigest);

        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> tokenService.rotate(
                        first.refreshToken(),
                        Base64.getEncoder().encodeToString(otherAllowedDevice.getPublic().getEncoded()),
                        signRefresh(otherAllowedDevice, first.refreshToken())));

        assertEquals(ErrorCode.AUTH_REQUIRED, failure.errorCode());
        assertTrue(refreshTokens.familyRevoked);
    }

    private IdentitySecurityProperties properties() {
        return new IdentitySecurityProperties(
                "ai-friend",
                "ai-friend-android",
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                "",
                "",
                "");
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private String signRefresh(KeyPair keyPair, String refreshToken) throws Exception {
        byte[] domain = "ai-friend-device-refresh-v1".getBytes(StandardCharsets.US_ASCII);
        byte[] tokenDigest = sha256(refreshToken.getBytes(StandardCharsets.UTF_8));
        byte[] canonical = new byte[domain.length + 1 + tokenDigest.length];
        System.arraycopy(domain, 0, canonical, 0, domain.length);
        System.arraycopy(tokenDigest, 0, canonical, domain.length + 1, tokenDigest.length);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static final class FixedUserAccountPort implements UserAccountPort {

        private final UserAccount user;

        private FixedUserAccountPort(UserAccount user) {
            this.user = user;
        }

        @Override
        public Optional<UserAccount> findByWechatSubjectHash(byte[] subjectHash) {
            return Optional.of(user);
        }

        @Override
        public Optional<UserAccount> findById(UUID userId) {
            return user.id().equals(userId) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public UserAccount create(
                ProtectedWechatSubject protectedSubject,
                long accountGeneration,
                Instant createdAt) {
            return user;
        }
    }

    private static final class InMemoryRefreshTokenRepository implements RefreshTokenRepositoryPort {

        private final Map<String, StoredRefreshToken> tokens = new HashMap<>();
        private final Map<UUID, byte[]> familyDeviceDigests = new HashMap<>();
        private boolean familyRevoked;

        @Override
        public void createFamily(
                UUID familyId,
                UUID userId,
                byte[] devicePublicKeySha256,
                Instant createdAt) {
            familyDeviceDigests.put(
                    familyId,
                    devicePublicKeySha256 == null ? null : devicePublicKeySha256.clone());
        }

        @Override
        public void saveToken(StoredRefreshToken token, Instant createdAt) {
            tokens.put(key(token.tokenHash()), new StoredRefreshToken(
                    token.id(),
                    token.familyId(),
                    token.userId(),
                    familyDeviceDigests.get(token.familyId()),
                    token.tokenHash(),
                    token.status(),
                    token.expiresAt()));
        }

        @Override
        public Optional<StoredRefreshToken> findByTokenHashForUpdate(byte[] tokenHash) {
            return Optional.ofNullable(tokens.get(key(tokenHash)));
        }

        @Override
        public void markRotated(UUID tokenId, Instant rotatedAt) {
            replaceStatus(tokenId, RefreshTokenStatus.ROTATED);
        }

        @Override
        public void revokeFamily(UUID familyId, Instant revokedAt) {
            familyRevoked = true;
            tokens.replaceAll((key, token) -> token.familyId().equals(familyId)
                    ? copyWithStatus(token, RefreshTokenStatus.REVOKED)
                    : token);
        }

        private void replaceStatus(UUID tokenId, RefreshTokenStatus status) {
            tokens.replaceAll((key, token) -> token.id().equals(tokenId) ? copyWithStatus(token, status) : token);
        }

        private StoredRefreshToken copyWithStatus(StoredRefreshToken token, RefreshTokenStatus status) {
            return new StoredRefreshToken(
                    token.id(),
                    token.familyId(),
                    token.userId(),
                    token.devicePublicKeySha256(),
                    token.tokenHash(),
                    status,
                    token.expiresAt());
        }

        private String key(byte[] hash) {
            return Base64.getEncoder().encodeToString(hash);
        }
    }
}
