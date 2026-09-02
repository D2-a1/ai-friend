package com.aifriend.retention.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.security.DigestService;

/**
 * 当前 owner 账号注销可靠受理服务。
 *
 * <p>本服务只返回可靠受理事实，不把异步在线数据删除误报为已经完成。</p>
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class AccountClosureService {
    private static final Duration RE_REGISTRATION_DELAY = Duration.ofHours(72);

    private final AccountClosureRepositoryPort repositoryPort;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建账号注销受理服务。
     *
     * @param repositoryPort 注销可靠受理事务端口
     * @param digestService SHA-256 服务
     * @param clock UTC 时钟
     */
    public AccountClosureService(
            AccountClosureRepositoryPort repositoryPort,
            DigestService digestService,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 可靠受理当前 owner 的永久注销请求。
     *
     * @param ownerUserId 当前认证 owner UUID
     * @param idempotencyKey 16 至 128 字符幂等键
     * @param expectedVersion 可选的当前账号版本前置条件
     * @return 受理时间和最早重新注册时间
     */
    public AccountClosureView close(
            UUID ownerUserId,
            String idempotencyKey,
            Long expectedVersion) {
        Instant acceptedAt = Instant.now(clock);
        byte[] requestSource = ("account-closure-v1:confirmed=true;expectedVersion="
                + (expectedVersion == null ? "null" : expectedVersion))
                .getBytes(StandardCharsets.US_ASCII);
        return repositoryPort.accept(
                ownerUserId,
                digestService.sha256(idempotencyKey),
                digestService.sha256(requestSource),
                expectedVersion,
                acceptedAt,
                acceptedAt.plus(RE_REGISTRATION_DELAY));
    }
}
