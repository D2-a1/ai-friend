package com.aifriend.retrieval.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立知识预算；默认关闭，零费用上限表示禁止收费调用。
 * @param enabled 是否允许预留
 * @param ownerRequestsPerMinute 每owner每UTC分钟请求数
 * @param globalHourlyCalls 所有模型/通道全局小时上限
 * @param globalDailyCalls 所有模型/通道全局日上限
 * @param onlineHourlyCalls 在线问答小时上限
 * @param onlineDailyCalls 在线问答日上限
 * @param importHourlyCalls 后台导入小时上限
 * @param importDailyCalls 后台导入日上限
 * @author codex
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "ai-friend.knowledge.quota")
public record KnowledgeQuotaProperties(boolean enabled, int ownerRequestsPerMinute,
        long globalHourlyCalls, long globalDailyCalls, long onlineHourlyCalls,
        long onlineDailyCalls, long importHourlyCalls, long importDailyCalls) {
    /** 不注入供应商或真实预算默认值；启用时校验配置组合。 */
    public KnowledgeQuotaProperties {
        if (enabled && (ownerRequestsPerMinute < 1 || ownerRequestsPerMinute > 60
                || !pair(globalHourlyCalls, globalDailyCalls)
                || !pair(onlineHourlyCalls, onlineDailyCalls) || !pair(importHourlyCalls, importDailyCalls)
                || onlineHourlyCalls > globalHourlyCalls || importHourlyCalls > globalHourlyCalls
                || onlineDailyCalls > globalDailyCalls || importDailyCalls > globalDailyCalls)) {
            throw new IllegalArgumentException("INVALID_KNOWLEDGE_QUOTA_CONFIGURATION");
        }
    }

    private static boolean pair(long hourly, long daily) {
        return hourly >= 0 && daily >= hourly && daily <= 1_000_000;
    }
}
