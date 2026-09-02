package com.aifriend.contact.application;

import java.util.List;

import com.aifriend.contact.domain.ContactBinding;

/**
 * owner 范围联系人绑定分页结果。
 *
 * @param items 当前页绑定快照
 * @param page 当前页码，从 0 开始
 * @param size 每页数量
 * @param totalElements owner 范围总数
 * @param totalPages 总页数
 * @author Codex
 * @since 1.0.0
 */
public record ContactBindingPage(
        List<ContactBinding> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * 固化当前页列表，避免调用方修改分页结果。
     */
    public ContactBindingPage {
        items = List.copyOf(items);
    }
}
