package com.aifriend.retention.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.aifriend.shared.config.AiFriendProperties;

/**
 * 统一推进在线敏感数据与删除墓碑生命周期清理。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RetentionLifecycleWorker {
    /** 每类数据单次最多处理的主记录数。 */
    private static final int BATCH_SIZE = 100;

    /** 生命周期存储端口。 */
    private final RetentionLifecyclePort lifecyclePort;
    /** 应用留存配置。 */
    private final AiFriendProperties properties;
    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建统一生命周期工作器。
     *
     * @param lifecyclePort 生命周期存储端口
     * @param properties 应用留存配置
     * @param clock UTC 时钟
     */
    public RetentionLifecycleWorker(
            RetentionLifecyclePort lifecyclePort,
            AiFriendProperties properties,
            Clock clock) {
        this.lifecyclePort = lifecyclePort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 推进一批到期音频、任务密文、邀请临时数据和已安全导出的删除墓碑。
     *
     * @return 本次成功清理或推进的主记录总数
     * @throws RuntimeException 当数据库扫描或清理失败时抛出
     */
    public int processReady() {
        Instant now = Instant.now(clock);
        Instant taskContentCutoff = now.minus(
                properties.retention().messageContentMaxAge());
        return lifecyclePort.cleanupBatch(now, taskContentCutoff, BATCH_SIZE);
    }
}
