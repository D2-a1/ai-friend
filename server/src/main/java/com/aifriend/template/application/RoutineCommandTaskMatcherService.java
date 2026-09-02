package com.aifriend.template.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aifriend.dialect.application.DialectPackageManifest;
import com.aifriend.dialect.application.DialectPackageRegistry;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.task.application.RoutineCommandLearningEvidence;
import com.aifriend.task.application.TaskClientContext;
import com.aifriend.task.application.TaskRoutineCommandMatchDecision;
import com.aifriend.task.application.TaskRoutineCommandMatcherPort;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.voice.application.ValidatedAudioObject;

/**
 * 日常指令模板运行时声学复核编排服务。
 *
 * <p>短事务只取得 owner ACTIVE 模板快照；模板解密、摘要复验和声学分类均在
 * 事务外执行并覆盖明文。模板只可否决冲突任务，不能单独产生动作授权。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class RoutineCommandTaskMatcherService
        implements TaskRoutineCommandMatcherPort {

    private final RoutineCommandRuntimeSnapshotService snapshotService;
    private final RoutineCommandRuntimeAcousticPort acousticPort;
    private final DialectPackageRegistry packageRegistry;
    private final SensitiveDataProtector protector;
    private final DigestService digestService;

    /**
     * 创建日常指令运行时复核服务。
     *
     * @param snapshotService owner 模板短事务快照服务
     * @param acousticPort 本地动作片段声学分类端口
     * @param packageRegistry 已验签方言包注册表
     * @param protector 模板 AES-GCM 解密器
     * @param digestService 模板完整性摘要服务
     */
    public RoutineCommandTaskMatcherService(
            RoutineCommandRuntimeSnapshotService snapshotService,
            RoutineCommandRuntimeAcousticPort acousticPort,
            DialectPackageRegistry packageRegistry,
            SensitiveDataProtector protector,
            DigestService digestService) {
        this.snapshotService = snapshotService;
        this.acousticPort = acousticPort;
        this.packageRegistry = packageRegistry;
        this.protector = protector;
        this.digestService = digestService;
    }

    /** {@inheritDoc} */
    @Override
    public TaskRoutineCommandMatchDecision match(
            UUID ownerUserId,
            ValidatedAudioObject audio,
            TaskClientContext clientContext,
            TaskIntent transcriptIntent,
            RoutineCommandLearningEvidence learningEvidence) {
        if (learningEvidence == null
                || learningEvidence.intent() != transcriptIntent
                || !isCommunicationIntent(transcriptIntent)) {
            return TaskRoutineCommandMatchDecision.notApplicable();
        }
        Optional<RoutineCommandRuntimeSnapshot> optionalSnapshot =
                snapshotService.snapshot(ownerUserId);
        if (optionalSnapshot.isEmpty()
                || optionalSnapshot.orElseThrow().templates().isEmpty()) {
            return TaskRoutineCommandMatchDecision.notApplicable();
        }
        RoutineCommandRuntimeSnapshot snapshot = optionalSnapshot.orElseThrow();
        DialectPackageManifest manifest = packageRegistry.findActive()
                .map(item -> item.manifest())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.TEMPLATE_INCOMPATIBLE));
        requireCompatibleContext(clientContext, manifest);

        List<RoutineCommandRuntimeTemplate> templates = new ArrayList<>();
        try {
            loadCompatibleTemplates(snapshot.templates(), clientContext, templates);
            if (templates.isEmpty()) {
                return TaskRoutineCommandMatchDecision.notApplicable();
            }
            RoutineCommandRuntimeMatch acousticMatch = acousticPort.classify(
                    audio, learningEvidence.actionAudioRange(), templates, clientContext);
            if (!snapshotService.isCurrent(
                    ownerUserId, snapshot.namespaceVersion())) {
                throw new BusinessException(ErrorCode.SESSION_CONFLICT);
            }
            return resolve(transcriptIntent, acousticMatch);
        } finally {
            templates.forEach(RoutineCommandRuntimeTemplate::close);
        }
    }

    private void loadCompatibleTemplates(
            List<RoutineCommandTemplateRecord> records,
            TaskClientContext context,
            List<RoutineCommandRuntimeTemplate> result) {
        for (RoutineCommandTemplateRecord record : records) {
            if (!compatible(record, context)) {
                continue;
            }
            byte[] cipher = record.templateCipher();
            byte[] plain = null;
            byte[] actualDigest = null;
            try {
                plain = protector.decryptBytes(cipher);
                actualDigest = digestService.sha256(plain);
                if (!digestService.constantTimeEquals(
                        record.templateDigest(), actualDigest)) {
                    throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
                }
                result.add(new RoutineCommandRuntimeTemplate(
                        record.id(), record.intent(), plain));
            } finally {
                Arrays.fill(cipher, (byte) 0);
                if (plain != null) {
                    Arrays.fill(plain, (byte) 0);
                }
                if (actualDigest != null) {
                    Arrays.fill(actualDigest, (byte) 0);
                }
            }
        }
    }

    private TaskRoutineCommandMatchDecision resolve(
            TaskIntent transcriptIntent,
            RoutineCommandRuntimeMatch acousticMatch) {
        return switch (acousticMatch.band()) {
            case NONE -> TaskRoutineCommandMatchDecision.noMatch();
            case AMBIGUOUS -> TaskRoutineCommandMatchDecision.conflict();
            case UNIQUE -> acousticMatch.intent() == transcriptIntent
                    ? TaskRoutineCommandMatchDecision.corroborated(transcriptIntent)
                    : TaskRoutineCommandMatchDecision.conflict();
        };
    }

    private void requireCompatibleContext(
            TaskClientContext context,
            DialectPackageManifest manifest) {
        boolean compatible = context != null
                && manifest.dialectCode().equals(context.dialectCode())
                && manifest.packageVersion().equals(context.dialectPackageVersion())
                && manifest.acousticModelVersion().equals(
                        context.templateModelVersion())
                && manifest.thresholdVersion().equals(context.thresholdVersion());
        if (!compatible) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
    }

    private boolean compatible(
            RoutineCommandTemplateRecord record,
            TaskClientContext context) {
        return record.dialectCode().equals(context.dialectCode())
                && record.dialectPackageVersion().equals(
                        context.dialectPackageVersion())
                && record.templateModelVersion().equals(
                        context.templateModelVersion())
                && record.thresholdVersion().equals(context.thresholdVersion());
    }

    private boolean isCommunicationIntent(TaskIntent intent) {
        return intent == TaskIntent.SEND_MESSAGE
                || intent == TaskIntent.VOICE_CALL
                || intent == TaskIntent.VIDEO_CALL;
    }
}
