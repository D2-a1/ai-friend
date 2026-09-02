package com.aifriend.invitation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.contact.application.WechatLocatorPolicy;
import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationSession;
import com.aifriend.invitation.domain.InvitationSessionStatus;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 当前受限邀请会话查询与亲友明确接受服务。
 *
 * <p>接受时固定使用 owner→邀请→会话→同主体绑定的锁顺序，并在同一事务内把邀请、
 * 会话和绑定推进到一致结果。任何凭据、微信主体和幂等键明文均不得进入日志。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationAcceptanceService {

    private static final byte[] DUMMY_DIGEST = new byte[32];
    private static final int MAX_TOTAL_CONTACT_OBJECTS = 20;
    private static final String RELATIONSHIP_SUMMARY = "将你添加为已绑定亲友";

    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final InvitationRepositoryPort invitationRepositoryPort;
    private final ContactBindingRepositoryPort contactBindingRepositoryPort;
    private final SecretTokenPort secretTokenPort;
    private final DigestService digestService;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final AuditEventPort auditEventPort;
    private final InvitationSessionProperties properties;
    private final Clock clock;

    /**
     * 创建邀请接受服务。
     *
     * @param sessionRepositoryPort 受限会话持久化端口
     * @param invitationRepositoryPort 邀请持久化端口
     * @param contactBindingRepositoryPort 联系人绑定持久化端口
     * @param secretTokenPort 安全随机令牌端口
     * @param digestService 摘要与常量时间比较服务
     * @param sensitiveDataProtector 微信号加密与 owner 范围 HMAC 保护器
     * @param auditEventPort 去标识化审计端口
     * @param properties 邀请会话与政策配置
     * @param clock UTC 时钟
     */
    public InvitationAcceptanceService(
            InvitationSessionRepositoryPort sessionRepositoryPort,
            InvitationRepositoryPort invitationRepositoryPort,
            ContactBindingRepositoryPort contactBindingRepositoryPort,
            SecretTokenPort secretTokenPort,
            DigestService digestService,
            SensitiveDataProtector sensitiveDataProtector,
            AuditEventPort auditEventPort,
            InvitationSessionProperties properties,
            Clock clock) {
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.invitationRepositoryPort = invitationRepositoryPort;
        this.contactBindingRepositoryPort = contactBindingRepositoryPort;
        this.secretTokenPort = secretTokenPort;
        this.digestService = digestService;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.auditEventPort = auditEventPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 查询已完成微信 OAuth 的当前受限邀请会话。
     *
     * <p>按 owner→邀请→会话加锁并轮换 CSRF 摘要，确保 OAuth 跳转后页面可重新取得
     * 只存在当前内存中的写操作凭据；下一次成功查询会使旧 CSRF token 失效。
     *
     * @param sessionToken 受限邀请 Cookie 明文，不得记录
     * @return 不含微信主体与虚假邀请人名称的最小展示信息及新 CSRF token
     * @throws BusinessException 当会话、邀请状态或期限不可用时抛出统一错误
     */
    @Transactional(rollbackFor = Exception.class)
    public InvitationSessionView view(String sessionToken) {
        Instant now = Instant.now(clock);
        byte[] sessionDigest = digestService.sha256(valueOrEmpty(sessionToken));
        InvitationSession candidate = authenticateSession(sessionToken, null, false);
        ContactInvitation snapshot = invitationRepositoryPort.findById(candidate.invitationId())
                .orElseThrow(this::unavailable);
        invitationRepositoryPort.lockOwner(snapshot.ownerUserId());
        ContactInvitation invitation = invitationRepositoryPort
                .findByIdForUpdate(snapshot.id(), snapshot.ownerUserId())
                .orElseThrow(this::unavailable);
        InvitationSession session = sessionRepositoryPort.findByIdForUpdate(candidate.id())
                .orElseThrow(this::unavailable);
        if (!digestService.constantTimeEquals(session.sessionTokenDigest(), sessionDigest)) {
            throw unavailable();
        }
        if (!isReady(session, invitation, now)) {
            throw unavailable();
        }
        String csrfToken = secretTokenPort.issue();
        sessionRepositoryPort.save(new InvitationSession(
                session.id(), session.invitationId(), session.sessionTokenDigest(),
                digestService.sha256(csrfToken), session.oauthStateDigest(),
                session.declineIdempotencyKeyDigest(), session.acceptIdempotencyKeyDigest(),
                session.oauthSubjectHash(), session.oauthSubjectCipher(),
                session.oauthVerifiedAt(), session.acceptedConsentPolicyVersion(), session.status(),
                session.expiresAt(), session.createdAt(), session.terminatedAt(), session.version()));
        Instant expiresAt = session.expiresAt().isBefore(invitation.expiresAt())
                ? session.expiresAt() : invitation.expiresAt();
        return new InvitationSessionView(
                null,
                RELATIONSHIP_SUMMARY,
                properties.consentPolicyVersion(),
                expiresAt,
                true,
                csrfToken);
    }

    /**
     * 使用 Cookie、CSRF、幂等键和政策版本明确接受邀请。
     *
     * @param sessionToken 受限邀请 Cookie 明文，不得记录
     * @param csrfToken CSRF token 明文，不得记录
     * @param idempotencyKey 接受操作幂等键，不得记录
     * @param confirmed 必须为 true
     * @param consentPolicyVersion 页面展示并由亲友接受的政策版本
     * @param wechatId 亲友从本人微信资料明确提交的微信号
     * @return 固定等待老人登记称呼状态
     * @throws BusinessException 当确认、政策、凭据、状态或总上限不满足时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public AcceptedInvitation accept(
            String sessionToken,
            String csrfToken,
            String idempotencyKey,
            boolean confirmed,
            String consentPolicyVersion,
            String wechatId) {
        if (!confirmed || !properties.consentPolicyVersion().equals(consentPolicyVersion)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        byte[] sessionDigest = digestService.sha256(valueOrEmpty(sessionToken));
        byte[] csrfDigest = digestService.sha256(valueOrEmpty(csrfToken));
        byte[] idempotencyDigest = digestService.sha256(valueOrEmpty(idempotencyKey));
        InvitationSession candidate = authenticateSession(sessionToken, csrfToken, true);
        ContactInvitation snapshot = invitationRepositoryPort.findById(candidate.invitationId())
                .orElseThrow(this::unavailable);

        invitationRepositoryPort.lockOwner(snapshot.ownerUserId());
        ContactInvitation invitation = invitationRepositoryPort
                .findByIdForUpdate(snapshot.id(), snapshot.ownerUserId())
                .orElseThrow(this::unavailable);
        InvitationSession session = sessionRepositoryPort.findByIdForUpdate(candidate.id())
                .orElseThrow(this::unavailable);
        if (!credentialsMatch(session, sessionDigest, csrfDigest)) {
            throw unavailable();
        }
        String stableLocator = WechatLocatorPolicy.normalizeInvitationWechatId(wechatId);
        if (isIdempotentReplay(session, invitation, idempotencyDigest)) {
            return acceptedResult();
        }
        Instant now = Instant.now(clock);
        if (!isReady(session, invitation, now)
                || session.oauthSubjectHash() == null
                || session.oauthSubjectCipher() == null) {
            throw unavailable();
        }
        long totalObjects = contactBindingRepositoryPort.countOccupying(invitation.ownerUserId())
                + invitationRepositoryPort.countPending(invitation.ownerUserId());
        if (totalObjects > MAX_TOTAL_CONTACT_OBJECTS) {
            throw unavailable();
        }

        Optional<ContactBinding> existing = contactBindingRepositoryPort
                .findByOwnerAndSubjectForUpdate(
                        invitation.ownerUserId(), session.oauthSubjectHash());
        UUID contactId = existing.map(ContactBinding::id).orElseGet(UUID::randomUUID);
        byte[] locatorHash = sensitiveDataProtector.subjectHmac(
                WechatLocatorPolicy.HMAC_DOMAIN + stableLocator);
        if (contactBindingRepositoryPort.existsOtherByOwnerAndLocatorHash(
                invitation.ownerUserId(), contactId, locatorHash)) {
            throw unavailable();
        }
        byte[] locatorCipher = sensitiveDataProtector.encrypt(stableLocator);
        ContactBinding binding = existing
                .map(value -> activateExisting(
                        value, session, invitation, consentPolicyVersion,
                        locatorCipher, locatorHash, now))
                .orElseGet(() -> createBinding(
                        contactId, session, invitation, consentPolicyVersion,
                        locatorCipher, locatorHash, now));
        contactBindingRepositoryPort.save(binding);
        invitationRepositoryPort.save(new ContactInvitation(
                invitation.id(), invitation.ownerUserId(), invitation.proofDigest(),
                InvitationStatus.ACCEPTED, invitation.expiresAt(), invitation.createdAt(),
                invitation.createIdempotencyKeyHash(), invitation.revokeIdempotencyKeyHash(),
                invitation.version()));
        sessionRepositoryPort.save(new InvitationSession(
                session.id(), session.invitationId(), session.sessionTokenDigest(),
                session.csrfTokenDigest(), session.oauthStateDigest(),
                session.declineIdempotencyKeyDigest(), idempotencyDigest,
                null, null, session.oauthVerifiedAt(), consentPolicyVersion,
                InvitationSessionStatus.TERMINATED, session.expiresAt(),
                session.createdAt(), now, session.version()));
        auditEventPort.append(
                invitation.ownerUserId(), "INVITATION_ACCEPT", "SUCCESS", null, now);
        return acceptedResult();
    }

    private InvitationSession authenticateSession(
            String sessionToken,
            String csrfToken,
            boolean requireCsrf) {
        byte[] presentedSession = digestService.sha256(valueOrEmpty(sessionToken));
        byte[] presentedCsrf = digestService.sha256(valueOrEmpty(csrfToken));
        InvitationSession candidate = sessionRepositoryPort
                .findBySessionTokenDigest(presentedSession).orElse(null);
        byte[] expectedSession = candidate == null ? DUMMY_DIGEST : candidate.sessionTokenDigest();
        byte[] expectedCsrf = candidate == null ? DUMMY_DIGEST : candidate.csrfTokenDigest();
        boolean sessionMatches = digestService.constantTimeEquals(expectedSession, presentedSession);
        boolean csrfMatches = digestService.constantTimeEquals(expectedCsrf, presentedCsrf);
        if (candidate == null || !sessionMatches || (requireCsrf && !csrfMatches)) {
            throw unavailable();
        }
        return candidate;
    }

    private boolean credentialsMatch(
            InvitationSession session,
            byte[] sessionDigest,
            byte[] csrfDigest) {
        return digestService.constantTimeEquals(session.sessionTokenDigest(), sessionDigest)
                && digestService.constantTimeEquals(session.csrfTokenDigest(), csrfDigest);
    }

    private boolean isReady(
            InvitationSession session,
            ContactInvitation invitation,
            Instant now) {
        return session.status() == InvitationSessionStatus.WECHAT_VERIFIED
                && invitation.status() == InvitationStatus.WECHAT_VERIFIED
                && session.expiresAt().isAfter(now)
                && invitation.expiresAt().isAfter(now);
    }

    private boolean isIdempotentReplay(
            InvitationSession session,
            ContactInvitation invitation,
            byte[] idempotencyDigest) {
        return session.status() == InvitationSessionStatus.TERMINATED
                && invitation.status() == InvitationStatus.ACCEPTED
                && session.acceptIdempotencyKeyDigest() != null
                && digestService.constantTimeEquals(
                        session.acceptIdempotencyKeyDigest(), idempotencyDigest);
    }

    private ContactBinding createBinding(
            UUID contactId,
            InvitationSession session,
            ContactInvitation invitation,
            String policyVersion,
            byte[] locatorCipher,
            byte[] locatorHash,
            Instant now) {
        return new ContactBinding(
                contactId, invitation.ownerUserId(), session.oauthSubjectHash(),
                session.oauthSubjectCipher(), locatorCipher, locatorHash, null, null,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION,
                null, null, null, null, now, null,
                policyVersion, now, ContactStatus.ACTIVE_NO_ALIAS,
                invitation.ownerUserId(), 0L, now, now, null);
    }

    private ContactBinding activateExisting(
            ContactBinding existing,
            InvitationSession session,
            ContactInvitation invitation,
            String policyVersion,
            byte[] locatorCipher,
            byte[] locatorHash,
            Instant now) {
        if (existing.status() != ContactStatus.REVOKED
                && existing.status() != ContactStatus.PENDING_LOCAL_VERIFY) {
            throw unavailable();
        }
        return new ContactBinding(
                existing.id(), invitation.ownerUserId(), session.oauthSubjectHash(),
                session.oauthSubjectCipher(), locatorCipher, locatorHash, null, null,
                WechatLocatorPolicy.INVITATION_WECHAT_ID_VERSION,
                null, null, null, null, now, null,
                policyVersion, now, ContactStatus.ACTIVE_NO_ALIAS,
                invitation.ownerUserId(), existing.version(), existing.createdAt(), now, null);
    }

    private AcceptedInvitation acceptedResult() {
        return new AcceptedInvitation(AcceptedInvitation.ACCEPTED_READY_FOR_ALIAS);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.INVITATION_UNAVAILABLE);
    }
}
