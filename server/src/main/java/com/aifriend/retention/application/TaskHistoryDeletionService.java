package com.aifriend.retention.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 当前 owner 任务历史清除受理与状态查询服务。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskHistoryDeletionService {
    private static final byte[] REQUEST_HASH_SOURCE = "task-history-delete-v1:confirmed=true".getBytes(StandardCharsets.US_ASCII);
    private final TaskHistoryDeletionRepositoryPort repositoryPort;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建任务历史清除服务。
     *
     * @param repositoryPort 清除作业持久化端口
     * @param digestService SHA-256 服务
     * @param clock UTC 时钟
     */
    public TaskHistoryDeletionService(TaskHistoryDeletionRepositoryPort repositoryPort, DigestService digestService, Clock clock) {
        this.repositoryPort = repositoryPort;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 可靠受理清除请求。
     *
     * @param ownerUserId owner UUID
     * @param idempotencyKey 16 至 128 字符幂等键
     * @return 公开清除状态
     */
    public TaskHistoryDeletionView clear(UUID ownerUserId, String idempotencyKey) {
        Instant now = Instant.now(clock);
        return repositoryPort.accept(ownerUserId, digestService.sha256(idempotencyKey), digestService.sha256(REQUEST_HASH_SOURCE), now);
    }

    /**
     * 查询当前或最近一次清除状态。
     *
     * @param ownerUserId owner UUID
     * @return 最近一次公开清除状态
     * @throws BusinessException 没有清除记录时抛出 NOT_FOUND
     */
    public TaskHistoryDeletionView get(UUID ownerUserId) {
        return repositoryPort.findLatest(ownerUserId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
