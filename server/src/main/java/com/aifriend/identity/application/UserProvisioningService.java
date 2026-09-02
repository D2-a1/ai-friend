package com.aifriend.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.identity.domain.ProtectedWechatSubject;
import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.UserStatus;
import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 按微信主体查找或创建内部账号。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class UserProvisioningService {

    private final UserAccountPort userAccountPort;
    private final SubjectProtectionPort subjectProtectionPort;
    private final AccountGenerationGatePort accountGenerationGatePort;
    private final Clock clock;

    /**
     * 创建账号预配服务。
     *
     * @param userAccountPort 用户账号持久化端口
     * @param subjectProtectionPort 微信主体保护端口
     * @param accountGenerationGatePort 删除墓碑账号代次门禁
     * @param clock UTC 时钟
     */
    public UserProvisioningService(
            UserAccountPort userAccountPort,
            SubjectProtectionPort subjectProtectionPort,
            AccountGenerationGatePort accountGenerationGatePort,
            Clock clock) {
        this.userAccountPort = userAccountPort;
        this.subjectProtectionPort = subjectProtectionPort;
        this.accountGenerationGatePort = accountGenerationGatePort;
        this.clock = clock;
    }

    /**
     * 查找或创建账号；仅在旧注销完成且 72 小时下限已到后创建全新代次账号。
     *
     * @param identity 微信最小主体
     * @return ACTIVE 用户账号
     * @throws BusinessException 账号正在注销或重新注册门禁未通过时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public UserAccount findOrCreate(WechatIdentity identity) {
        ProtectedWechatSubject protectedSubject = subjectProtectionPort.protect(identity.subject());
        UserAccount existing = userAccountPort.findByWechatSubjectHash(protectedSubject.hash())
                .orElse(null);
        if (existing != null) {
            return requireActive(existing);
        }
        Instant now = Instant.now(clock);
        long accountGeneration = accountGenerationGatePort.nextGeneration(
                protectedSubject.hash(), now);
        UserAccount user = userAccountPort.create(protectedSubject, accountGeneration, now);
        return requireActive(user);
    }

    private UserAccount requireActive(UserAccount user) {
        if (user.status() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_CLOSURE_ACCEPTED);
        }
        return user;
    }
}
