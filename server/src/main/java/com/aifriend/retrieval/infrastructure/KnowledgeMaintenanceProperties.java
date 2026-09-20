package com.aifriend.retrieval.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 独立维护类型化配置。
 * @param pollInterval 固定延迟，1至300秒
 * @author Codex
 * @since 1.0.0
 */
@ConfigurationProperties("ai-friend.knowledge.maintenance")
public record KnowledgeMaintenanceProperties(@DefaultValue("30s") Duration pollInterval) {
    /** 无效间隔启动失败，不能静默回退。 */
    public KnowledgeMaintenanceProperties {
        if(pollInterval==null || pollInterval.compareTo(Duration.ofSeconds(1))<0
                || pollInterval.compareTo(Duration.ofSeconds(300))>0) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_MAINTENANCE_INTERVAL");
        }
    }
}
