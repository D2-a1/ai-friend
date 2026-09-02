package com.aifriend.contact.application;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aifriend.contact.domain.ContactBinding;
import com.aifriend.shared.security.PublicIdCodec;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 将联系人绑定转换为不含微信主体和稳定定位的最小展示结果。
 *
 * @author Codex
 * @since 1.0.0
 */
@Component
public class ContactSummaryMapper {

    private final SensitiveDataProtector sensitiveDataProtector;
    private final ContactAliasMapper aliasMapper;

    /**
     * 创建联系人最小展示映射器。
     *
     * @param sensitiveDataProtector 允许展示的备注解密器
     * @param aliasMapper 称呼展示映射器
     */
    public ContactSummaryMapper(
            SensitiveDataProtector sensitiveDataProtector,
            ContactAliasMapper aliasMapper) {
        this.sensitiveDataProtector = sensitiveDataProtector;
        this.aliasMapper = aliasMapper;
    }

    /**
     * 转换联系人绑定，只解密允许展示的当前备注。
     *
     * @param binding 联系人绑定快照
     * @return 最小展示结果
     */
    public ContactSummary toSummary(ContactBinding binding) {
        return toSummary(binding, List.of());
    }

    /**
     * 转换联系人绑定及其 owner 范围有效称呼。
     *
     * @param binding 联系人绑定快照
     * @param aliases 当前绑定的有效称呼；REVOKED 时调用方必须传空列表
     * @return 不含声学模板和微信稳定主体的最小展示结果
     */
    public ContactSummary toSummary(
            ContactBinding binding,
            List<com.aifriend.contact.domain.ContactAlias> aliases) {
        String remark = binding.remarkCipher() == null
                ? null : sensitiveDataProtector.decrypt(binding.remarkCipher());
        List<ContactAliasSummary> aliasSummaries = aliases.stream()
                .map(aliasMapper::toSummary)
                .toList();
        return new ContactSummary(
                PublicIdCodec.contactId(binding.id()),
                null,
                remark,
                null,
                binding.relationship(),
                binding.status(),
                aliasSummaries.size(),
                aliasSummaries,
                binding.localVerificationVersion(),
                binding.verifiedAt(),
                binding.version() + 1,
                binding.createdAt(),
                binding.updatedAt());
    }
}
