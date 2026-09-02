package com.aifriend.invitation.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.application.ContactBindingRepositoryPort;
import com.aifriend.identity.application.AuditEventPort;
import com.aifriend.invitation.domain.ContactInvitation;
import com.aifriend.invitation.domain.InvitationStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.DigestService;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 邀请创建与邀请人撤销用例服务。
 *
 * <p>同一邀请人的写操作先锁定账号行，再复验数量限制，防止并发越过上限。
 * proof 由服务端密钥按邀请 UUID 派生且只落库摘要，完整分享地址仅返回当前调用方。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class InvitationService {

    private static final int MAX_PENDING_INVITATIONS = 5;
    private static final int MAX_DAILY_INVITATIONS = 10;
    private static final int MAX_TOTAL_CONTACT_OBJECTS = 20;

    private final InvitationRepositoryPort repositoryPort;
    private final InvitationSessionRepositoryPort sessionRepositoryPort;
    private final ContactBindingRepositoryPort contactBindingRepositoryPort;
    private final SensitiveDataProtector sensitiveDataProtector;
    private final DigestService digestService;
    private final AuditEventPort auditEventPort;
    private final InvitationProperties properties;
    private final Clock clock;

    /**
     * 创建邀请用例服务。
     *
     * @param repositoryPort 邀请持久化端口
     * @param sessionRepositoryPort 邀请会话持久化端口
     * @param contactBindingRepositoryPort 联系人绑定持久化端口
     * @param sensitiveDataProtector proof 派生器
     * @param digestService 摘要服务
     * @param auditEventPort 安全审计端口
     * @param properties 邀请配置
     * @param clock UTC 时钟
     */
    public InvitationService(
            InvitationRepositoryPort repositoryPort,
            InvitationSessionRepositoryPort sessionRepositoryPort,
            ContactBindingRepositoryPort contactBindingRepositoryPort,
            SensitiveDataProtector sensitiveDataProtector,
            DigestService digestService,
            AuditEventPort auditEventPort,
            InvitationProperties properties,
            Clock clock) {
        this.repositoryPort = repositoryPort;
        this.sessionRepositoryPort = sessionRepositoryPort;
        this.contactBindingRepositoryPort = contactBindingRepositoryPort;
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.digestService = digestService;
        this.auditEventPort = auditEventPort;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建固定有效期、单次且可撤销的亲友邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param idempotencyKey 创建幂等键原文，不得记录日志
     * @return 含一次性敏感分享地址的邀请结果
     * @throws BusinessException 当未完成邀请或每日创建数量达到限制时抛出
     * @throws IllegalStateException 当邀请基础地址不是安全 HTTPS 地址时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public CreatedInvitation create(UUID ownerUserId, String idempotencyKey) {
        Instant now = Instant.now(clock);
        byte[] idempotencyHash = digestService.sha256(idempotencyKey);
        repositoryPort.lockOwner(ownerUserId);
        repositoryPort.expirePending(ownerUserId, now);
        ContactInvitation invitation = repositoryPort
                .findByCreateIdempotencyKey(ownerUserId, idempotencyHash)
                .orElseGet(() -> createNew(ownerUserId, idempotencyHash, now));
        return toCreatedInvitation(invitation);
    }

    /**
     * 由邀请人撤销仍处于等待状态的邀请。
     *
     * @param ownerUserId 邀请人 UUID
     * @param publicInvitationId 邀请公开编号
     * @param idempotencyKey 撤销幂等键原文，不得记录日志
     * @throws BusinessException 当邀请不存在、不属于当前用户或已进入其他终态时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(UUID ownerUserId, String publicInvitationId, String idempotencyKey) {
        Instant now = Instant.now(clock);
        repositoryPort.lockOwner(ownerUserId);
        UUID invitationId = PublicIdCodec.parseInvitationId(publicInvitationId);
        ContactInvitation invitation = repositoryPort.findByIdForUpdate(invitationId, ownerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (invitation.status() == InvitationStatus.REVOKED) {
            return;
        }
        if (!invitation.status().isUnfinished() || !invitation.expiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT, "邀请已经结束，不能撤销");
        }
        ContactInvitation revoked = new ContactInvitation(
                invitation.id(),
                invitation.ownerUserId(),
                invitation.proofDigest(),
                InvitationStatus.REVOKED,
                invitation.expiresAt(),
                invitation.createdAt(),
                invitation.createIdempotencyKeyHash(),
                digestService.sha256(idempotencyKey),
                invitation.version());
        repositoryPort.save(revoked);
        sessionRepositoryPort.terminateByInvitationId(invitation.id(), now);
        auditEventPort.append(ownerUserId, "INVITATION_REVOKE", "SUCCESS", null, now);
    }

    /**
     * 查询当前用户仍可撤销的未完成邀请。
     *
     * <p>所有内部处理中状态统一折叠为等待亲友确认，且不重建只在创建响应中出现的分享地址。
     *
     * @param ownerUserId 邀请人 UUID
     * @return 最多五条按创建时间倒序排列的邀请摘要
     */
    @Transactional(rollbackFor = Exception.class)
    public List<PendingInvitationSummary> listPending(UUID ownerUserId) {
        Instant now = Instant.now(clock);
        repositoryPort.lockOwner(ownerUserId);
        repositoryPort.expirePending(ownerUserId, now);
        return repositoryPort.findPending(ownerUserId, now, MAX_PENDING_INVITATIONS).stream()
                .map(invitation -> new PendingInvitationSummary(
                        PublicIdCodec.invitationId(invitation.id()), invitation.expiresAt()))
                .toList();
    }

    private ContactInvitation createNew(UUID ownerUserId, byte[] idempotencyHash, Instant now) {
        if (repositoryPort.countPending(ownerUserId) >= MAX_PENDING_INVITATIONS) {
            throw new BusinessException(ErrorCode.CONTACT_LIMIT_REACHED, "未完成邀请已达上限");
        }
        long totalContactObjects = repositoryPort.countPending(ownerUserId)
                + contactBindingRepositoryPort.countOccupying(ownerUserId);
        if (totalContactObjects >= MAX_TOTAL_CONTACT_OBJECTS) {
            throw new BusinessException(ErrorCode.CONTACT_LIMIT_REACHED);
        }
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        if (repositoryPort.countCreatedSince(ownerUserId, dayStart) >= MAX_DAILY_INVITATIONS) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "今天创建邀请的次数已达上限");
        }
        UUID invitationId = UUID.randomUUID();
        String proof = sensitiveDataProtector.invitationProof(invitationId);
        ContactInvitation invitation = new ContactInvitation(
                invitationId,
                ownerUserId,
                digestService.sha256(proof),
                InvitationStatus.PENDING,
                now.plus(properties.ttl()),
                now,
                idempotencyHash,
                null,
                0L);
        ContactInvitation saved = repositoryPort.save(invitation);
        auditEventPort.append(ownerUserId, "INVITATION_CREATE", "SUCCESS", null, now);
        return saved;
    }

    private CreatedInvitation toCreatedInvitation(ContactInvitation invitation) {
        URI baseUrl = properties.baseUrl();
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new IllegalStateException("邀请基础地址必须是无查询和 fragment 的 HTTPS 地址");
        }
        String base = baseUrl.toString().endsWith("/") ? baseUrl.toString() : baseUrl + "/";
        String publicId = PublicIdCodec.invitationId(invitation.id());
        String proof = sensitiveDataProtector.invitationProof(invitation.id());
        String shareUrl = base + publicId + "#proof=" + proof;
        return new CreatedInvitation(publicId, shareUrl, invitation.expiresAt());
    }
}
