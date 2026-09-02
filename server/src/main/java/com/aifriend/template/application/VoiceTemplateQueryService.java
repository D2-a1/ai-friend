package com.aifriend.template.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前 owner 的语音模板最小元数据清单服务。
 *
 * <p>返回联系人称呼、四类安全指令和已可靠学习的日常动作模板元数据。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class VoiceTemplateQueryService {

    private final VoiceTemplateContactProjectionPort contactProjectionPort;
    private final SafetyCommandTemplateRepositoryPort safetyRepositoryPort;
    private final SafetyCommandTemplateMapper safetyTemplateMapper;

    private final RoutineCommandTemplateStorePort routineTemplateStorePort;
    private final RoutineCommandTemplateMapper routineTemplateMapper;
    /**
     * 创建语音模板清单服务。
     *
     * @param contactProjectionPort 联系人称呼只读投影端口
     * @param safetyRepositoryPort 安全指令模板持久化端口
     * @param safetyTemplateMapper 安全指令元数据映射器
     * @param routineTemplateStorePort 日常指令模板只读端口
     * @param routineTemplateMapper 日常指令最小元数据映射器
     */
    public VoiceTemplateQueryService(
            VoiceTemplateContactProjectionPort contactProjectionPort,
            SafetyCommandTemplateRepositoryPort safetyRepositoryPort,
            SafetyCommandTemplateMapper safetyTemplateMapper,
            RoutineCommandTemplateStorePort routineTemplateStorePort,
            RoutineCommandTemplateMapper routineTemplateMapper) {
        this.contactProjectionPort = contactProjectionPort;
        this.safetyRepositoryPort = safetyRepositoryPort;
        this.safetyTemplateMapper = safetyTemplateMapper;
        this.routineTemplateStorePort = routineTemplateStorePort;
        this.routineTemplateMapper = routineTemplateMapper;

    }
    /**
     * 查询当前 owner 的全部已实现有效语音模板。
     *
     * @param ownerUserId 已验证 JWT 派生的 owner UUID
     * @return 按分类与公开编号稳定排序的清单
     */
    @Transactional(readOnly = true)
    public List<VoiceTemplateSummary> list(UUID ownerUserId) {
        List<VoiceTemplateSummary> summaries = new ArrayList<>();
        summaries.addAll(contactProjectionPort.listContactAliases(ownerUserId));
        summaries.addAll(safetyRepositoryPort.findActiveByOwner(ownerUserId).stream()
                .map(safetyTemplateMapper::toSummary)
                .toList());
        summaries.addAll(routineTemplateStorePort.findAllByOwner(ownerUserId).stream()
                .map(routineTemplateMapper::toSummary)
                .toList());
        return summaries.stream()
                .sorted(Comparator.comparing(VoiceTemplateSummary::category)
                        .thenComparing(VoiceTemplateSummary::templateId))
                .toList();
    }
}
