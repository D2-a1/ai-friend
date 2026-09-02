package com.aifriend.template.application;

import java.util.List;
import java.util.UUID;

/**
 * 语音模板清单读取联系人称呼元数据的跨域只读端口。
 *
 * @author Codex
 * @since 1.0.0
 */
public interface VoiceTemplateContactProjectionPort {

    /**
     * 查询 owner 的有效称呼模板元数据。
     *
     * @param ownerUserId owner UUID
     * @return 不含展示文字和声学模板的清单项
     */
    List<VoiceTemplateSummary> listContactAliases(UUID ownerUserId);
}
