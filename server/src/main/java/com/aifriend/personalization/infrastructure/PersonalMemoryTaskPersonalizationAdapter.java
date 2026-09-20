package com.aifriend.personalization.infrastructure;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.aifriend.personalization.application.PersonalMemoryService;
import com.aifriend.personalization.application.PersonalMemorySnapshot;
import com.aifriend.personalization.domain.PersonalMemoryPreferences;
import com.aifriend.task.application.TaskConversationPreferences;
import com.aifriend.task.application.TaskPersonalizationPort;

/**
 * 只把当前开关已开启、当前政策已授权且可解密的三项枚举交给任务草稿层。
 */
@Component
public class PersonalMemoryTaskPersonalizationAdapter
        implements TaskPersonalizationPort {

    private final PersonalMemoryService service;

    /**
     * 创建任务个性化适配器。
     *
     * @param service 长期偏好管理服务
     */
    public PersonalMemoryTaskPersonalizationAdapter(PersonalMemoryService service) {
        this.service = service;
    }

    /** {@inheritDoc} */
    @Override
    public TaskConversationPreferences currentPreferences(UUID ownerUserId) {
        try {
            PersonalMemorySnapshot snapshot = service.get(ownerUserId);
            if (!snapshot.featureEnabled() || !snapshot.consentGranted()
                    || snapshot.preferences() == null) {
                return TaskConversationPreferences.safeDefaults();
            }
            return toTaskPreferences(snapshot.preferences());
        } catch (RuntimeException exception) {
            // 长期偏好是低权限可选上下文；读取不确定时不阻断明确任务，
            // 也不把错误、owner 或偏好内容写日志。
            return TaskConversationPreferences.safeDefaults();
        }
    }

    private TaskConversationPreferences toTaskPreferences(
            PersonalMemoryPreferences preferences) {
        return new TaskConversationPreferences(
                preferences.speechRate().name(),
                preferences.dialogueStyle().name(),
                preferences.ambiguousCall().name());
    }
}