package com.aifriend.knowledge.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.aifriend.retrieval.domain.KnowledgeText;

/**
 * 仅用于当前授权响应的亲友显示信息，禁止写入图谱投影或外发。
 * @param contactId 权威绑定标识
 * @param contactVersion 当前绑定版本
 * @param aliases 当前有效称呼，不含音素/声学模板
 * @author codex
 * @since 1.0.0
 */
public record ContactDisplay(UUID contactId, long contactVersion, List<String> aliases) {
    /** 防御性复制并限制输出。 */
    public ContactDisplay {
        Objects.requireNonNull(contactId, "contactId");
        if (contactVersion < 0 || aliases == null || aliases.size() > 5) {
            throw new IllegalArgumentException("INVALID_CONTACT_DISPLAY");
        }
        aliases = List.copyOf(aliases);
        aliases.forEach(alias -> KnowledgeText.require(alias, 100, 400, false));
    }

    /** 私人称呼不进入默认日志。 */
    @Override public String toString() { return "ContactDisplay[private=redacted]"; }
}
