package com.aifriend.invitation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;

/**
 * 邀请微信 OAuth 回调的事务外身份兑换服务。
 *
 * <p>先以 Cookie 和一次性 state 拒绝无效请求，再在数据库事务外调用微信身份端口；
 * 最终写入由独立事务服务重新加锁复验，避免把外部网络调用包在数据库事务中。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationOAuthService {

    private static final byte[] DUMMY_DIGEST = new byte[32];

    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final InvitationRepositoryPort invitationRepositoryPort;
    private final InvitationOAuthAttemptLimiterPort attemptLimiterPort;
    private final WechatInvitationIdentityPort identityPort;
    private final InvitationOAuthCompletionService completionService;
    private final DigestService digestService;
    private final Clock clock;

    /**
     * 创建邀请 OAuth 回调服务。
     *
     * @param sessionRepositoryPort 邀请会话持久化端口
     * @param invitationRepositoryPort 邀请持久化端口
     * @param attemptLimiterPort 邀请 OAuth 回调限流端口
     * @param identityPort 微信 code 兑换端口
     * @param completionService OAuth 结果事务提交服务
     * @param digestService 摘要与常量时间比较服务
     * @param clock UTC 时钟
     */
    public InvitationOAuthService(
            InvitationSessionRepositoryPort sessionRepositoryPort,
            InvitationRepositoryPort invitationRepositoryPort,
            InvitationOAuthAttemptLimiterPort attemptLimiterPort,
            WechatInvitationIdentityPort identityPort,
            InvitationOAuthCompletionService completionService,
            DigestService digestService,
            Clock clock) {
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.invitationRepositoryPort = invitationRepositoryPort;
        this.attemptLimiterPort = attemptLimiterPort;
        this.identityPort = identityPort;
        this.completionService = completionService;
        this.digestService = digestService;
        this.clock = clock;
    }

    /**
     * 校验回调上下文、事务外兑换 code，再原子提交最小微信身份。
     *
     * @param sessionToken 受限邀请 Cookie 明文，不得记录
     * @param oauthState 一次性 state 明文，不得记录
     * @param code 一次性微信 OAuth code，不得记录
     * @throws BusinessException 当 Cookie、state、状态或期限不可用时抛出统一错误
     */
    public void complete(String sessionToken, String oauthState, String code) {
        byte[] sessionDigest = digestService.sha256(valueOrEmpty(sessionToken));
        attemptLimiterPort.acquire(sessionDigest);
        byte[] stateDigest = digestService.sha256(valueOrEmpty(oauthState));
        Optional<InvitationSession> candidateOptional =
                sessionRepositoryPort.findBySessionTokenDigest(sessionDigest);
        InvitationSession candidate = candidateOptional.orElse(null);
        byte[] expectedSession = candidate == null ? DUMMY_DIGEST : candidate.sessionTokenDigest();
        byte[] expectedState = candidate == null ? DUMMY_DIGEST : candidate.oauthStateDigest();
        boolean credentialsMatch = digestService.constantTimeEquals(expectedSession, sessionDigest)
                && digestService.constantTimeEquals(expectedState, stateDigest);
        ContactInvitation invitation = candidate == null ? null
                : invitationRepositoryPort.findById(candidate.invitationId()).orElse(null);
        Instant now = Instant.now(clock);
        if (candidate == null || invitation == null || !credentialsMatch
                || candidate.status() != InvitationSessionStatus.AWAITING_WECHAT_OAUTH
                || invitation.status() != InvitationStatus.PROOF_REDEEMED
                || !candidate.expiresAt().isAfter(now)
                || !invitation.expiresAt().isAfter(now)) {
            throw unavailable();
        }
        WechatInvitationIdentity identity = identityPort.exchangeCode(code);
        completionService.commit(
                candidate.invitationId(), candidate.id(), sessionDigest, stateDigest, identity);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INVITATION_UNAVAILABLE);
    }
}
