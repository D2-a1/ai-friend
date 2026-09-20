package com.aifriend.template.application;

import org.springframework.stereotype.Component;

import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.template.domain.SafetyCommandTemplate;
import com.aifriend.template.domain.SafetyCommandTemplateStatus;

/**
 * 安全指令模板持久化快照与对外元数据映射器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class SafetyCommandTemplateMapper {

    private final AcousticTemplatePort acousticTemplatePort;

    /**
     * 创建安全指令模板映射器。
     *
     * @param acousticTemplatePort 当前已验证方言包兼容性端口
     */
    public SafetyCommandTemplateMapper(AcousticTemplatePort acousticTemplatePort) {
        this.acousticTemplatePort = acousticTemplatePort;
    }

    /**
     * 将有效安全指令模板转为不含敏感材料的清单项。
     *
     * @param template 有效安全指令模板
     * @return 动态兼容性清单项
     * @throws BusinessException 模板非有效状态时抛出
     */
    public VoiceTemplateSummary toSummary(SafetyCommandTemplate template) {
        if (template.status() != SafetyCommandTemplateStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        boolean compatible = template.hasPersistedMaterial()
                && acousticTemplatePort.isCompatible(
                        template.dialectCode(), template.dialectPackageVersion(),
                        template.modelVersion(), template.thresholdVersion());
        return new VoiceTemplateSummary(
                PublicIdCodec.voiceTemplateId(template.id()),
                "SAFETY_COMMAND", null, null, template.commandType(), null,
                template.dialectCode(), template.dialectPackageVersion(),
                template.modelVersion(), template.thresholdVersion(),
                compatible ? "COMPATIBLE" : "INCOMPATIBLE", template.updatedAt());
    }
}
