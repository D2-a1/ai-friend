package com.aifriend.task.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 主方言和普通话辅助识别结果的保守融合服务。
 *
 * <p>普通话候选只用于发现明显动作冲突；最终首选文本和时间戳始终来自主方言。
 * 两路识别对动作类别有明确冲突时立即要求重说，不按较高置信度强行决定。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TaskAsrFusionService {

    /** 创建无状态融合服务。 */
    public TaskAsrFusionService() {
    }

    /**
     * 融合主方言和普通话辅助结果。
     *
     * @param primary 主方言 N-best
     * @param mandarinAssist 普通话辅助 N-best
     * @return 主方言首选和全部临时证据
     * @throws BusinessException 当两路动作结论明确冲突时抛出
     */
    public TaskAsrFusionResult fuse(
            TaskAsrEngineResult primary,
            TaskAsrEngineResult mandarinAssist) {
        TaskTranscriptCandidate primaryTop = primary.topCandidate();
        TaskTranscriptCandidate assistTop = mandarinAssist.topCandidate();
        ActionEvidence primaryAction = actionEvidence(primaryTop.transcript());
        ActionEvidence assistAction = actionEvidence(assistTop.transcript());
        if (primaryAction != ActionEvidence.UNKNOWN
                && assistAction != ActionEvidence.UNKNOWN
                && primaryAction != assistAction) {
            throw new BusinessException(ErrorCode.AUDIO_SEGMENT_UNCERTAIN);
        }
        List<TaskTranscriptCandidate> evidence = new ArrayList<>(6);
        evidence.addAll(primary.candidates());
        evidence.addAll(mandarinAssist.candidates());
        double confidence = normalized(primaryTop.transcript()).equals(
                normalized(assistTop.transcript()))
                ? Math.min(primaryTop.confidence(), assistTop.confidence())
                : primaryTop.confidence();
        return new TaskAsrFusionResult(primaryTop, evidence, confidence);
    }

    private ActionEvidence actionEvidence(String transcript) {
        String normalized = normalized(transcript);
        if (containsAny(normalized, "取消", "不要了", "算了", "莫搞")) {
            return ActionEvidence.CANCEL;
        }
        if (containsAny(normalized, "不对", "说错了", "改成", "纠正")) {
            return ActionEvidence.CORRECT;
        }
        if (containsAny(normalized, "视频", "视频电话")) {
            return ActionEvidence.VIDEO_CALL;
        }
        if (containsAny(normalized, "打电话", "语音电话", "通话")) {
            return ActionEvidence.VOICE_CALL;
        }
        if (containsAny(normalized, "发消息", "发信息", "告诉", "跟他说", "跟她说", "叫")) {
            return ActionEvidence.MESSAGE;
        }
        return ActionEvidence.UNKNOWN;
    }

    private String normalized(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private enum ActionEvidence {
        MESSAGE,
        VOICE_CALL,
        VIDEO_CALL,
        CANCEL,
        CORRECT,
        UNKNOWN
    }
}
