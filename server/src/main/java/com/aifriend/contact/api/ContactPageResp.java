package com.aifriend.contact.api;

import java.util.List;

/**
 * 联系人分页响应数据。
 *
 * @param items 当前页联系人
 * @param page 分页元数据
 * @author Codex
 * @since 1.0.0
 */
public record ContactPageResp(
        List<ContactResp> items,
        PageMetadataResp page) {

    /**
     * 固化联系人列表。
     */
    public ContactPageResp {
        items = List.copyOf(items);
    }
}
