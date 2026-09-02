package com.aifriend.contact.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.aifriend.contact.domain.ContactAlias;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aifriend.contact.domain.ContactStatus;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

/**
 * 当前 owner 联系人最小展示查询服务。
 *
 * <p>只解密允许展示的备注，不解密或返回微信主体、稳定定位和其他微信数据。
 *
 * @author Codex
 * @since 1.0.0
 */
@Service
public class ContactQueryService {

    private static final int MAX_PAGE_SIZE = 20;

    private final ContactBindingRepositoryPort repositoryPort;
    private final ContactAliasRepositoryPort aliasRepositoryPort;
    private final ContactSummaryMapper summaryMapper;

    /**
     * 创建联系人查询服务。
     *
     * @param repositoryPort owner 范围绑定持久化端口
     * @param aliasRepositoryPort owner 范围称呼持久化端口
     * @param summaryMapper 最小展示映射器
     */
    public ContactQueryService(
            ContactBindingRepositoryPort repositoryPort,
            ContactAliasRepositoryPort aliasRepositoryPort,
            ContactSummaryMapper summaryMapper) {
        this.repositoryPort = repositoryPort;
        this.aliasRepositoryPort = aliasRepositoryPort;
        this.summaryMapper = summaryMapper;
    }

    /**
     * 分页查询当前 owner 的联系人最小展示信息。
     *
     * @param ownerUserId 当前账号 UUID
     * @param status 可选状态过滤
     * @param page 页码，从 0 开始
     * @param size 每页数量，1—20
     * @return 最小展示分页结果
     * @throws BusinessException 当分页参数越界时抛出
     */
    @Transactional(readOnly = true)
    public ContactSummaryPage list(
            UUID ownerUserId,
            ContactStatus status,
            int page,
            int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        ContactBindingPage bindings = repositoryPort.findByOwner(
                ownerUserId, status, page, size);
        Map<UUID, List<ContactAlias>> aliasesByBinding = aliasRepositoryPort
                .findActiveByOwner(ownerUserId).stream()
                .collect(Collectors.groupingBy(ContactAlias::bindingId));
        return new ContactSummaryPage(
                bindings.items().stream()
                        .map(binding -> summaryMapper.toSummary(
                                binding,
                                binding.status() == ContactStatus.REVOKED
                                        ? List.of()
                                        : aliasesByBinding.getOrDefault(binding.id(), List.of())))
                        .toList(),
                bindings.page(),
                bindings.size(),
                bindings.totalElements(),
                bindings.totalPages());
    }

}
