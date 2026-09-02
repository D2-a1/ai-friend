package com.aifriend.identity.application;

import org.springframework.stereotype.Service;

import com.aifriend.identity.domain.UserAccount;
import com.aifriend.identity.domain.WechatIdentity;

/**
 * 微信登录用例；外部 code 消费不包在数据库长事务中。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class LoginService {

    private final WechatIdentityPort wechatIdentityPort;
    private final UserProvisioningService userProvisioningService;
    private final TokenService tokenService;
    private final DeviceTrustService deviceTrustService;

    /**
     * 创建微信登录服务。
     *
     * @param wechatIdentityPort 微信身份端口
     * @param userProvisioningService 用户预配服务
     * @param tokenService 令牌服务
     * @param deviceTrustService 设备公钥白名单门禁
     */
    public LoginService(
            WechatIdentityPort wechatIdentityPort,
            UserProvisioningService userProvisioningService,
            TokenService tokenService,
            DeviceTrustService deviceTrustService) {
        this.wechatIdentityPort = wechatIdentityPort;
        this.userProvisioningService = userProvisioningService;
        this.tokenService = tokenService;
        this.deviceTrustService = deviceTrustService;
    }

    /**
     * 消费微信一次性 code 并签发令牌对。
     *
     * @param code 微信一次性 code，不得记录日志
     * @param publicKeySpkiBase64 Android Keystore 公钥
     * @param proofBase64 绑定 code 的设备签名
     * @return 登录令牌对
     */
    public TokenPairResult login(String code, String publicKeySpkiBase64, String proofBase64) {
        DeviceAuthentication device = deviceTrustService.verifyLogin(
                code, publicKeySpkiBase64, proofBase64);
        WechatIdentity identity = wechatIdentityPort.exchange(code);
        UserAccount user = userProvisioningService.findOrCreate(identity);
        return tokenService.issue(user, device.publicKeySha256());
    }
}
