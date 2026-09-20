package com.aifriend.knowledge.domain;

/**
 * 服务端固定只读查询，不接受SQL、Cypher或任意深度。
 * @author codex
 * @since 1.0.0
 */
public enum GraphQueryType {
    /** 列出当前有效亲友。 */ LIST_CONTACTS,
    /** 列出一位当前有效亲友的称呼。 */ LIST_ALIASES,
    /** 按明确称呼找候选，不据此授权联系。 */ FIND_CONTACT_BY_ALIAS
}
