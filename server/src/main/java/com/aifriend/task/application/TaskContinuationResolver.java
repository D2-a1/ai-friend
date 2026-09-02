package com.aifriend.task.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.task.domain.TaskIntent;
import com.aifriend.task.domain.TaskState;

/**
 * 解析消息继续窗口携带的上一位联系人。
 *
 * <p>客户端负责用单调时钟限制五秒点击；服务端再核对同 owner 最新任务、消息意图、
 * 可信渠道结果、联系人一致性和总处理时限。任一事实缺失时返回空，不猜测联系人。
 *
 * @author codex
 * @since 1.0.0
 */
@Service
public class TaskContinuationResolver {

    /** 五秒点击、最长六十秒录音和有界上传处理的服务端总证明时限。 */
    static final Duration MAX_TOTAL_ELAPSED = Duration.ofSeconds(75);

    private final TaskSessionRepositoryPort repositoryPort;
    private final TaskPayloadCodec payloadCodec;
    private final TaskContinuationContactProjectionPort contactProjectionPort;
    private final Clock clock;

    /**
     * 创建消息继续解析器。
     *
     * @param repositoryPort owner 范围任务持久化端口
     * @param payloadCodec 任务敏感载荷保护器
     * @param contactProjectionPort ACTIVE 联系人投影端口
     * @param clock UTC 时钟
     */
    public TaskContinuationResolver(
            TaskSessionRepositoryPort repositoryPort,
            TaskPayloadCodec payloadCodec,
            TaskContinuationContactProjectionPort contactProjectionPort,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.payloadCodec = payloadCodec;
        this.contactProjectionPort = contactProjectionPort;
        this.clock = clock;
    }

    /**
     * 解析当前 owner 请求沿用的上一位联系人。
     *
     * @param ownerUserId JWT 派生 owner UUID
     * @param publicContactId 客户端五秒窗口携带的 ct_ 联系人编号
     * @return 全部事实成立时返回唯一继续候选，否则返回空
     */
    @Transactional(readOnly = true)
    public Optional<TaskContinuationResolution> resolve(
            UUID ownerUserId,
            String publicContactId) {
        if (!StringUtils.hasText(publicContactId)) {
            return Optional.empty();
        }
        UUID contactId = PublicIdCodec.parseContactId(publicContactId);
        TaskStoredSession latest = repositoryPort.findLatestByOwner(ownerUserId).orElse(null);
        Instant now = Instant.now(clock);
        if (!isRecentSession(latest, contactId, now)
                || !contactProjectionPort.isActive(ownerUserId, contactId)) {
            return Optional.empty();
        }
        TaskPayload payload = payloadCodec.decode(latest.payloadCipher());
        TaskUnderstandingView understanding = payload.understanding();
        if (understanding == null
                || understanding.intent() != TaskIntent.SEND_MESSAGE
                || understanding.contact() == null
                || !PublicIdCodec.contactId(contactId).equals(understanding.contact().id())
                || !hasTrustedMessageEvidence(payload.channelResult(), latest.state())) {
            return Optional.empty();
        }
        TaskMatchedContactView previousContact = understanding.contact();
        TaskContactCandidate candidate = new TaskContactCandidate(
                UUID.randomUUID().toString(), contactId,
                PublicIdCodec.contactId(contactId), previousContact.displayName(),
                previousContact.alias(), "UNIQUE", 1);
        return Optional.of(new TaskContinuationResolution(latest.id(), candidate));
    }

    private boolean isRecentSession(
            TaskStoredSession latest,
            UUID contactId,
            Instant now) {
        if (latest == null || latest.selectedContactId() == null
                || !latest.selectedContactId().equals(contactId)
                || (latest.state() != TaskState.COMPLETED
                        && latest.state() != TaskState.PARTIAL)
                || latest.updatedAt().isBefore(now.minus(MAX_TOTAL_ELAPSED))
                || latest.updatedAt().isAfter(now)) {
            return false;
        }
        return true;
    }

    private boolean hasTrustedMessageEvidence(
            TaskChannelResultView channelResult,
            TaskState state) {
        if (channelResult == null) {
            return false;
        }
        boolean audioSent = hasPart(channelResult, "AUDIO", "SENT", true);
        if (state == TaskState.COMPLETED && "SENT".equals(channelResult.result())) {
            return audioSent && hasPart(channelResult, "TEXT", "SENT", true);
        }
        return state == TaskState.PARTIAL
                && "PARTIAL".equals(channelResult.result())
                && audioSent
                && hasPart(channelResult, "TEXT", "FAILED", false);
    }

    private boolean hasPart(
            TaskChannelResultView channelResult,
            String partName,
            String partResult,
            boolean requiresEvidence) {
        return channelResult.parts().stream().anyMatch(part ->
                partName.equals(part.part())
                        && partResult.equals(part.result())
                        && (!requiresEvidence || StringUtils.hasText(part.evidenceCode())));
    }
}
