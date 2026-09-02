package com.aifriend.contact.application;

import java.util.List;

/**
 * 联系人最小展示分页结果。
 *
 * @param items 当前页联系人
 * @param page 当前页码
 * @param size 每页数量
 * @param totalElements 总元素数
 * @param totalPages 总页数
 * @author Codex
 * @since 1.0.0
 */
public record ContactSummaryPage(
        List<ContactSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /**
     * 固化联系人列表。
     */
    public ContactSummaryPage {
        items = List.copyOf(items);
    }
}
