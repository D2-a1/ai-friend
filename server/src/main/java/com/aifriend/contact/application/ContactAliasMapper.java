package com.aifriend.contact.application;

import org.springframework.stereotype.Component;

import com.aifriend.contact.domain.ContactAlias;
import com.aifriend.contact.domain.ContactAliasStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 联系人称呼敏感持久化快照映射器。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class ContactAliasMapper {

    private final SensitiveDataProtector sensitiveDataProtector;
    private final AcousticTemplatePort acousticTemplatePort;

    /**
     * 创建联系人称呼映射器。
     *
     * @param sensitiveDataProtector 展示文字和声学模板解密器
     * @param acousticTemplatePort 当前已验证方言包兼容性查询端口
     */
    public ContactAliasMapper(
            SensitiveDataProtector sensitiveDataProtector,
            AcousticTemplatePort acousticTemplatePort) {
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.acousticTemplatePort = acousticTemplatePort;
    }

    /**
     * 将有效称呼映射为最小展示结果。
     *
     * @param alias 有效称呼
     * @return 不含声学模板的展示结果
     * @throws BusinessException 称呼已删除或缺少展示密文时抛出
     */
    public ContactAliasSummary toSummary(ContactAlias alias) {
        if (alias.status() != ContactAliasStatus.ACTIVE
                || alias.displayTextCipher() == null) {
            throw new BusinessException(ErrorCode.SESSION_CONFLICT);
        }
        return new ContactAliasSummary(
                PublicIdCodec.aliasId(alias.id()),
                sensitiveDataProtector.decrypt(alias.displayTextCipher()),
                alias.dialectCode(),
                alias.dialectPackageVersion(),
                alias.modelVersion(),
                alias.thresholdVersion(),
                acousticTemplatePort.isCompatible(
                        alias.dialectCode(), alias.dialectPackageVersion(),
                        alias.modelVersion(), alias.thresholdVersion())
                        ? "COMPATIBLE" : "INCOMPATIBLE",
                alias.createdAt());
    }

    /**
     * 解密有效称呼的短期模板供 owner 锁内唯一性比较。
     *
     * @param alias 有效称呼
     * @return 不含展示文字的声学比较输入
     * @throws BusinessException 模板状态或密文无效时抛出
     */
    public ExistingAcousticTemplate toAcousticTemplate(ContactAlias alias) {
        if (alias.status() != ContactAliasStatus.ACTIVE || alias.templateCipher() == null) {
            throw new BusinessException(ErrorCode.TEMPLATE_INCOMPATIBLE);
        }
        return new ExistingAcousticTemplate(
                alias.id(),
                sensitiveDataProtector.decryptBytes(alias.templateCipher()),
                alias.dialectCode(),
                alias.dialectPackageVersion(),
                alias.modelVersion(),
                alias.thresholdVersion());
    }
}
