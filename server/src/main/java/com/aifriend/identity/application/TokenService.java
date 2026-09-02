package com.aifriend.identity.application;

import org.springframework.stereotype.Service;

import com.aifriend.identity.domain.UserAccount;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 令牌签发与轮换用例入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class TokenService {

    private final TokenTransactionService tokenTransactionService;
    private final DeviceTrustService deviceTrustService;

    /**
     * 创建令牌用例服务。
     *
     * @param tokenTransactionService 原子令牌事务服务
     * @param deviceTrustService 设备证明门禁
     */
    public TokenService(
            TokenTransactionService tokenTransactionService,
            DeviceTrustService deviceTrustService) {
        this.tokenTransactionService = tokenTransactionService;
        this.deviceTrustService = deviceTrustService;
    }

    /**
     * 为登录用户创建新 token family。
     *
     * @param user ACTIVE 用户
     * @param devicePublicKeySha256 已验证设备公钥摘要；门禁关闭时为空
     * @return 令牌对
     */
    public TokenPairResult issue(UserAccount user, byte[] devicePublicKeySha256) {
        return tokenTransactionService.issue(user, devicePublicKeySha256);
    }

    /**
     * 轮换刷新令牌；重用检测完成并提交撤销后统一返回未认证。
     *
     * @param refreshToken 刷新令牌明文，不得记录日志
     * @param publicKeySpkiBase64 Android Keystore 公钥
     * @param proofBase64 绑定刷新令牌的设备签名
     * @return 新令牌对
     * @throws BusinessException 令牌无效、过期或重用时抛出
     */
    public TokenPairResult rotate(
            String refreshToken,
            String publicKeySpkiBase64,
            String proofBase64) {
        DeviceAuthentication device = deviceTrustService.verifyRefresh(
                refreshToken, publicKeySpkiBase64, proofBase64);
        TokenRotationResult result = tokenTransactionService.rotate(
                refreshToken, device.publicKeySha256());
        if (result.status() != TokenRotationResult.Status.SUCCESS) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        return result.tokenPair();
    }
}
