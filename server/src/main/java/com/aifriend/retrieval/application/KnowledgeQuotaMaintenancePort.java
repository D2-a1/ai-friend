package com.aifriend.retrieval.application;

/**
 * 知识额度有界过期维护，不释放当前额度、不修改任务幂等身份。
 * @author Codex
 * @since 1.0.0
 */
public interface KnowledgeQuotaMaintenancePort {
    /**
     * 使用数据库时间和预留共用的控制锁回收过期账本。
     * @return 本批删除数；仍有过期数据时由下次扫描继续
     */
    Cleanup sweep();

    /**
     * 有界计数，不包含账号、profile或操作身份。
     * @param reservations 删除的过期预留数
     * @param buckets 删除的过期窗口数
     */
    record Cleanup(int reservations, int buckets) {
        /** 每张表单次最多128条。 */
        public Cleanup {
            if (reservations < 0 || reservations > 128 || buckets < 0 || buckets > 128) {
                throw new IllegalArgumentException("INVALID_QUOTA_CLEANUP_COUNT");
            }
        }
    }
}
