package com.aifriend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.aifriend.contact.application.ContactVerificationProperties;
import com.aifriend.dialect.application.BasicExperienceProperties;
import com.aifriend.dialect.application.DialectPackageProperties;
import com.aifriend.identity.application.DeviceTrustProperties;
import com.aifriend.identity.application.WechatIdentityProperties;
import com.aifriend.invitation.application.InvitationProperties;
import com.aifriend.invitation.application.InvitationOAuthRateLimitProperties;
import com.aifriend.invitation.application.WechatInvitationOAuthProperties;
import com.aifriend.invitation.application.InvitationSessionProperties;
import com.aifriend.retention.application.DisasterRecoveryProperties;
import com.aifriend.retention.application.OperationsTotpProperties;
import com.aifriend.retention.application.PushPlusAlertProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryProperties;
import com.aifriend.retention.application.TencentCosDisasterRecoveryRestoreProperties;
import com.aifriend.shared.config.AiFriendProperties;
import com.aifriend.shared.security.IdentitySecurityProperties;
import com.aifriend.task.application.TaskAsrProperties;
import com.aifriend.task.application.DebugMvpDemoProperties;
import com.aifriend.task.application.WechatActionPlanSigningProperties;
import com.aifriend.task.application.WechatExecutionProperties;
import com.aifriend.voice.application.AliyunOssStorageProperties;
import com.aifriend.voice.application.AudioStorageProperties;
import com.aifriend.voicecollection.application.VoiceCollectionProperties;
import com.aifriend.voicecollection.application.VoiceTrainingInputExportProperties;

/**
 * AI好友后端应用入口。
 *
 * @author Codex
 * @since 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AiFriendProperties.class,
        IdentitySecurityProperties.class,
        DeviceTrustProperties.class,
        WechatIdentityProperties.class,
        InvitationProperties.class,
        InvitationOAuthRateLimitProperties.class,
        WechatInvitationOAuthProperties.class,
        InvitationSessionProperties.class,
        ContactVerificationProperties.class,
        BasicExperienceProperties.class,
        DialectPackageProperties.class,
        AudioStorageProperties.class,
        AliyunOssStorageProperties.class,
        DisasterRecoveryProperties.class,
        TencentCosDisasterRecoveryProperties.class,
        TencentCosDisasterRecoveryRestoreProperties.class,
        PushPlusAlertProperties.class,
        OperationsTotpProperties.class,
        TaskAsrProperties.class,
        DebugMvpDemoProperties.class,
        WechatActionPlanSigningProperties.class,
        WechatExecutionProperties.class,
        VoiceCollectionProperties.class,
        VoiceTrainingInputExportProperties.class
})
public class AiFriendServerApplication {

    private AiFriendServerApplication() {
    }

    /**
     * 启动应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiFriendServerApplication.class, args);
    }
}
