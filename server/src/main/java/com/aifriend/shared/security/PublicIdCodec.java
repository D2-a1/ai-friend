package com.aifriend.shared.security;

import java.util.UUID;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 内部 UUID 与不暴露数据库格式的用户公开编号转换器。
 *
 * @author Codex
 * @since 1.0.0
 */
public final class PublicIdCodec {

    private static final String USER_PREFIX = "us_";
    private static final String INVITATION_PREFIX = "iv_";
    private static final String CONTACT_PREFIX = "ct_";
    private static final String ALIAS_PREFIX = "al_";
    private static final String AUDIO_OBJECT_PREFIX = "au_";
    private static final String VOICE_TEMPLATE_PREFIX = "vt_";
    private static final String TASK_SESSION_PREFIX = "ts_";
    private static final String VOICE_COLLECTION_SAMPLE_PREFIX = "vs_";

    private PublicIdCodec() {
    }

    /**
     * 将内部用户 UUID 转为 OpenAPI 用户编号。
     *
     * @param userId 内部用户 UUID
     * @return us_ 前缀用户编号
     */
    public static String userId(UUID userId) {
        return USER_PREFIX + userId.toString().replace("-", "");
    }

    /**
     * 将公开用户编号还原为内部 UUID。
     *
     * @param publicUserId JWT subject 中的用户编号
     * @return 内部用户 UUID
     * @throws BusinessException 编号非法时抛出
     */
    public static UUID parseUserId(String publicUserId) {
        if (publicUserId == null || !publicUserId.startsWith(USER_PREFIX)) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        String compactUuid = publicUserId.substring(USER_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将邀请 UUID 转为无权限公开编号。
     *
     * @param invitationId 邀请 UUID
     * @return iv_ 前缀公开编号
     */
    public static String invitationId(UUID invitationId) {
        return INVITATION_PREFIX + invitationId.toString().replace("-", "");
    }

    /**
     * 将公开邀请编号还原为内部 UUID。
     *
     * @param publicInvitationId 公开邀请编号
     * @return 内部邀请 UUID
     * @throws BusinessException 编号格式非法时抛出
     */
    public static UUID parseInvitationId(String publicInvitationId) {
        if (publicInvitationId == null || !publicInvitationId.startsWith(INVITATION_PREFIX)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String compactUuid = publicInvitationId.substring(INVITATION_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将联系人绑定 UUID 转为无权限公开编号。
     *
     * @param contactId 联系人绑定 UUID
     * @return ct_ 前缀公开编号
     */
    public static String contactId(UUID contactId) {
        return CONTACT_PREFIX + contactId.toString().replace("-", "");
    }

    /**
     * 将公开联系人编号还原为内部 UUID。
     *
     * @param publicContactId ct_ 前缀公开联系人编号
     * @return 内部联系人绑定 UUID
     * @throws BusinessException 编号格式非法时抛出统一不存在错误
     */
    public static UUID parseContactId(String publicContactId) {
        if (publicContactId == null || !publicContactId.startsWith(CONTACT_PREFIX)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String compactUuid = publicContactId.substring(CONTACT_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将称呼 UUID 转为无权限公开编号。
     *
     * @param aliasId 称呼 UUID
     * @return al_ 前缀公开编号
     */
    public static String aliasId(UUID aliasId) {
        return ALIAS_PREFIX + aliasId.toString().replace("-", "");
    }

    /**
     * 将公开称呼编号还原为内部 UUID。
     *
     * @param publicAliasId al_ 前缀公开编号
     * @return 内部称呼 UUID
     * @throws BusinessException 编号格式非法时抛出统一不存在错误
     */
    public static UUID parseAliasId(String publicAliasId) {
        if (publicAliasId == null || !publicAliasId.startsWith(ALIAS_PREFIX)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String compactUuid = publicAliasId.substring(ALIAS_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将音频对象 UUID 转为无权限公开编号。
     *
     * @param audioObjectId 音频对象 UUID
     * @return au_ 前缀公开编号
     */
    public static String audioObjectId(UUID audioObjectId) {
        return AUDIO_OBJECT_PREFIX + audioObjectId.toString().replace("-", "");
    }

    /**
     * 将公开音频对象编号还原为内部 UUID。
     *
     * @param publicAudioObjectId au_ 前缀公开音频对象编号
     * @return 内部音频对象 UUID
     * @throws BusinessException 编号格式非法时抛出统一音频无效错误
     */
    public static UUID parseAudioObjectId(String publicAudioObjectId) {
        if (publicAudioObjectId == null
                || !publicAudioObjectId.startsWith(AUDIO_OBJECT_PREFIX)) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        String compactUuid = publicAudioObjectId.substring(AUDIO_OBJECT_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.AUDIO_INVALID);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将语音模板 UUID 转为无权限公开编号。
     *
     * @param voiceTemplateId 语音模板 UUID
     * @return vt_ 前缀公开编号
     */
    public static String voiceTemplateId(UUID voiceTemplateId) {
        return VOICE_TEMPLATE_PREFIX + voiceTemplateId.toString().replace("-", "");
    }

    /**
     * 将任务会话 UUID 转为公开编号。
     *
     * @param taskSessionId 任务会话 UUID
     * @return ts_ 前缀公开编号
     */
    public static String taskSessionId(UUID taskSessionId) {
        return TASK_SESSION_PREFIX + taskSessionId.toString().replace("-", "");
    }

    /**
     * 将公开任务会话编号还原为内部 UUID。
     *
     * @param publicTaskSessionId ts_ 前缀公开编号
     * @return 内部任务会话 UUID
     * @throws BusinessException 编号非法时抛出统一不存在错误
     */
    public static UUID parseTaskSessionId(String publicTaskSessionId) {
        if (publicTaskSessionId == null
                || !publicTaskSessionId.startsWith(TASK_SESSION_PREFIX)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String compactUuid = publicTaskSessionId.substring(TASK_SESSION_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将公开语音模板编号还原为内部 UUID。
     *
     * @param publicVoiceTemplateId vt_ 前缀公开编号
     * @return 内部模板 UUID
     * @throws BusinessException 编号非法时抛出模板不兼容错误
     */
    public static UUID parseVoiceTemplateId(String publicVoiceTemplateId) {
        if (publicVoiceTemplateId == null
                || !publicVoiceTemplateId.startsWith(VOICE_TEMPLATE_PREFIX)) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        String compactUuid = publicVoiceTemplateId.substring(VOICE_TEMPLATE_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }

    /**
     * 将语音采集样本 UUID 转为无权限公开编号。
     *
     * @param sampleId 样本 UUID
     * @return vs_ 前缀公开编号
     */
    public static String voiceCollectionSampleId(UUID sampleId) {
        return VOICE_COLLECTION_SAMPLE_PREFIX + sampleId.toString().replace("-", "");
    }

    /**
     * 将公开语音采集样本编号还原为内部 UUID。
     *
     * @param publicSampleId vs_ 前缀公开编号
     * @return 内部样本 UUID
     * @throws BusinessException 编号格式非法时抛出统一不存在错误
     */
    public static UUID parseVoiceCollectionSampleId(String publicSampleId) {
        if (publicSampleId == null
                || !publicSampleId.startsWith(VOICE_COLLECTION_SAMPLE_PREFIX)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String compactUuid = publicSampleId.substring(VOICE_COLLECTION_SAMPLE_PREFIX.length());
        if (!compactUuid.matches("[0-9a-fA-F]{32}")) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String uuid = compactUuid.substring(0, 8) + "-"
                + compactUuid.substring(8, 12) + "-"
                + compactUuid.substring(12, 16) + "-"
                + compactUuid.substring(16, 20) + "-"
                + compactUuid.substring(20);
        return UUID.fromString(uuid);
    }
}
