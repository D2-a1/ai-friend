package com.aifriend.contact.api;

/**
 * 通用分页元数据响应。
 *
 * @param page 当前页码
 * @param size 每页数量
 * @param totalElements 总元素数
 * @param totalPages 总页数
 * @author Codex
 * @since 1.0.0
 */
public record PageMetadataResp(
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
