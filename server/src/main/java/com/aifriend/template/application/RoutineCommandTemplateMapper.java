package com.aifriend.template.application;

import org.springframework.stereotype.Component;

import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.shared.security.PublicIdCodec;

/**
 * 日常指令模板持久化快照到最小公开元数据的映射器。
 *
 * <p>响应只包含有限动作和版本信息，不解密或返回 MFCC 模板材料。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class RoutineCommandTemplateMapper {

    private final DialectPackageRegistry packageRegistry;

    /**
     * 创建日常指令模板映射器。
     *
     * @param packageRegistry 已验签方言包注册表
     */
    public RoutineCommandTemplateMapper(DialectPackageRegistry packageRegistry) {
        this.packageRegistry = packageRegistry;
    }

    /**
     * 映射 owner 范围的日常模板元数据。
     *
     * @param template 不含 owner 的模板快照
     * @return 不含声学材料和原始短语的清单项
     */
    public VoiceTemplateSummary toSummary(RoutineCommandTemplateRecord template) {
        boolean compatible = packageRegistry.findActive()
                .map(packageItem -> packageItem.manifest())
                .map(manifest -> compatible(template, manifest))
                .orElse(false);
        return new VoiceTemplateSummary(
                PublicIdCodec.voiceTemplateId(template.id()),
                "ROUTINE_COMMAND", null, null, null, template.intent(),
                template.dialectCode(), template.dialectPackageVersion(),
                template.templateModelVersion(), template.thresholdVersion(),
                compatible ? "COMPATIBLE" : "INCOMPATIBLE", template.updatedAt());
    }

    private boolean compatible(
            RoutineCommandTemplateRecord template,
            DialectPackageManifest manifest) {
        return manifest.dialectCode().equals(template.dialectCode())
                && manifest.packageVersion().equals(template.dialectPackageVersion())
                && manifest.acousticModelVersion().equals(
                        template.templateModelVersion())
                && manifest.thresholdVersion().equals(template.thresholdVersion());
    }
}
