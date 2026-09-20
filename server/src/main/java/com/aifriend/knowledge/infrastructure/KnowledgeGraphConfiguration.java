package com.aifriend.knowledge.infrastructure;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.contact.infrastructure.ContactGraphSourceAdapter;
import com.aifriend.knowledge.application.ContactGraphQueryService;
import com.aifriend.knowledge.application.ContactGraphSourcePort;
import com.aifriend.knowledge.application.KnowledgeGraphPort;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 总开关与图谱开关双重默认关闭；不依赖生成、Embedding或旧语音任务开关。
 * 此处只装配来源、存储与应用服务；撤权清理组件独立于本条件，HTTP/Android另行接入。
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ai-friend.knowledge", name = {"enabled", "graph-enabled"}, havingValue = "true")
public class KnowledgeGraphConfiguration {
    /** 创建无外部调用的配置。 */
    public KnowledgeGraphConfiguration() { }

    /**
     * 创建当前owner权威来源。
     * @param source 数据源
     * @param transactions 同源事务管理器
     * @param access 知识用途专用的当前同意策略
     * @param compatibility 本地兼容版本查询
     * @param protector 既有称呼加密器
     * @return 只读来源端口
     */
    @Bean public ContactGraphSourcePort contactGraphSourcePort(DataSource source, PlatformTransactionManager transactions,
            KnowledgeAccessPolicy access, AcousticTemplatePort compatibility, SensitiveDataProtector protector) {
        return new ContactGraphSourceAdapter(source, transactions, access, compatibility, protector);
    }

    /**
     * 创建查询使用的图谱投影存储器；按账号清理由独立常驻组件装配。
     * @param source 数据源
     * @param transactions 同源事务管理器
     * @param access 用途同意
     * @return 投影存储端口
     */
    @Bean public KnowledgeGraphPort knowledgeGraphPort(DataSource source, PlatformTransactionManager transactions,
            KnowledgeAccessPolicy access) {
        return new JdbcKnowledgeGraphAdapter(source, transactions, access);
    }

    /**
     * 创建纯本地有限查询服务。
     * @param source 权威来源
     * @param projections 私人图谱存储
     * @param access 用途同意
     * @return 尚需API入口的查询服务
     */
    @Bean public ContactGraphQueryService contactGraphQueryService(ContactGraphSourcePort source,
            KnowledgeGraphPort projections, KnowledgeAccessPolicy access) {
        return new ContactGraphQueryService(source, projections, access);
    }
}
