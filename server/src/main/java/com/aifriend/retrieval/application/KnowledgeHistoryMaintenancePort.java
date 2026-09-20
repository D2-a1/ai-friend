package com.aifriend.retrieval.application;

/**
 * 公开知识历史材料有界回收，不删除版本号、导入任务或幂等凭据。
 * @author codex
 * @since 1.0.0
 */
public interface KnowledgeHistoryMaintenancePort {
    /**
     * 执行一个短事务批次；有有效构建租约时不回收。先回收至多一个非活动且无待办使用世代的64个清单引用。
     * @return 实际删除片段数与清空旧原文数，不将清单/向量引用重复计入片段数
     */
    Cleanup sweep();

    /**
     * 仅包含计数，不包含原文。
     * @param chunks 删除的无引用片段，最多64
     * @param versions 清空原文的非活动版本，最多16
     * @param state 是否推进、受阻或在控制锁内证明历史已排空
     */
    record Cleanup(int chunks, int versions, State state) {
        /**
         * 旧调用方只有计数，不能因此声称历史已经排空。
         * @param chunks 片段数
         * @param versions 原文数
         */
        public Cleanup(int chunks, int versions) { this(chunks, versions, State.PROGRESSED); }
        /** 校验单批硬上限。 */
        public Cleanup {
            if (state == null || chunks < 0 || chunks > 64 || versions < 0 || versions > 16
                    || (state != State.PROGRESSED && (chunks != 0 || versions != 0))) {
                throw new IllegalArgumentException("INVALID_KNOWLEDGE_CLEANUP_COUNT");
            }
        }
    }

    /** DEFERRED和零计数都不是完成证明；DRAINED必须排除仍有引用的历史。 */
    enum State {
        /** 本批实际回收了材料，尚不保证全部排空。 */
        PROGRESSED,
        /** 构建租约或历史引用阻止当前回收，需要后续检查。 */
        DEFERRED,
        /** 已在控制锁内确认不存在待回收历史。 */
        DRAINED
    }
}
