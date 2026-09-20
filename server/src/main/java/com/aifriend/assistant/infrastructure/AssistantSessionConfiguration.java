package com.aifriend.assistant.infrastructure;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.domain.*;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;
import com.aifriend.retrieval.application.*;
import com.aifriend.shared.security.SensitiveDataProtector;

/**
 * 独立问答装配。清理不受总开关影响，在线组件默认关闭；构造零数据库/模型调用。
 * 不创建常驻维护调度器，迁移和云端验收仍需独立执行。
 * @author Codex
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods=false)
@Import(AssistantSessionConfiguration.Online.class)
public class AssistantSessionConfiguration {
    /** 创建配置。 */
    public AssistantSessionConfiguration() { }
    /**
         * 装配不依赖功能开启的生命周期清理适配器。
         * @param source 同源数据源
         * @param transactions 事务管理器
         * @return 开关关闭仍可清理的生命周期端口
         */
    @Bean @ConditionalOnMissingBean(AssistantSessionLifecyclePort.class)
    public AssistantSessionLifecyclePort assistantSessionLifecycle(DataSource source,PlatformTransactionManager transactions) {
        return new JdbcAssistantSessionLifecycleAdapter(source,transactions);
    }
    /**
         * 将问答清理接入既有同意撤回链。
         * @param lifecycle 同事务清理端口
         * @return 加入既有撤权组合器的处理器
         */
    @Bean public AssistantConsentRevocationHandler assistantConsentRevocationHandler(AssistantSessionLifecyclePort lifecycle) {
        return new AssistantConsentRevocationHandler(lifecycle);
    }

    /** 在线组件总开关关闭时不实例化、不启动线程。 */
    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="ai-friend.knowledge",name="enabled",havingValue="true")
    @EnableConfigurationProperties(AssistantExecutionProperties.class)
    public static class Online {
        /** 创建配置。 */
        public Online() { }
        /**
         * 创建与其他用途分域的请求指纹器。
         * @param protector 既有加密器
         * @return 分域幂等指纹
         */
        @Bean public AssistantRequestFingerprint assistantRequestFingerprint(SensitiveDataProtector protector) { return new AssistantRequestFingerprint(protector); }
        /**
         * 创建会话上下文保护器。
         * @param protector 既有加密器
         * @return 上下文保护器
         */
        @Bean public AssistantContextCipher assistantContextCipher(SensitiveDataProtector protector) { return new AssistantContextCipher(protector); }
        /**
         * 创建结果密文及证明的保护端口。
         * @param protector 既有加密器
         * @return 结果保护端口
         */
        @Bean public AssistantResultCipher assistantResultCipher(SensitiveDataProtector protector) { return new AssistantResultCipher(protector); }
        /**
         * 装配所有者隔离的JDBC会话仓储。
         * @param source 数据源
         * @param transactions 事务管理器
         * @param access 独立授权
         * @param fingerprint 请求指纹
         * @param cipher 上下文加密
         * @return 会话仓储
         */
        @Bean @ConditionalOnMissingBean(AssistantSessionRepository.class)
        public AssistantSessionRepository assistantSessionRepository(DataSource source,PlatformTransactionManager transactions,
                KnowledgeAccessPolicy access,AssistantRequestFingerprint fingerprint,AssistantContextCipher cipher) {
            return new JdbcAssistantSessionRepository(source,transactions,access,fingerprint,cipher);
        }
        /**
         * 装配原子受理与提交的JDBC请求仓储。
         * @param source 数据源
         * @param transactions 事务管理器
         * @param access 独立授权
         * @param fingerprint 请求指纹
         * @param cipher 上下文加密
         * @return 原子请求仓储
         */
        @Bean @ConditionalOnMissingBean(AssistantTurnRepository.class)
        public AssistantTurnRepository assistantTurnRepository(DataSource source,PlatformTransactionManager transactions,
                KnowledgeAccessPolicy access,AssistantRequestFingerprint fingerprint,AssistantContextCipher cipher) {
            return new JdbcAssistantTurnRepository(source,transactions,access,fingerprint,cipher);
        }
        /**
         * 创建具有并发和期限上限的专用执行器。
         * @param properties 外置有界参数
         * @param clock UTC时钟
         * @return 容器关闭时释放的专用执行器
         */
        @Bean(destroyMethod="close") public BoundedAssistantExecutor assistantExecutionPort(AssistantExecutionProperties properties,Clock clock) {
            return new BoundedAssistantExecutor(properties,clock);
        }
        /**
         * 提供当前生成配置指纹；非生成模式不使用外部生成端口。
         * @param properties 回答模式
         * @param generation 专用生成端口
         * @return 只读本地生成profile，不联网
         */
        @Bean("assistantGenerationProfile") public Supplier<Optional<String>> assistantGenerationProfile(KnowledgeAnswerProperties properties,
                ObjectProvider<KnowledgeAnswerGenerationPort> generation) {
            if(properties.mode()!=AssistantAnswer.Mode.GENERATED) return Optional::empty;
            var port=generation.getIfAvailable();
            if(port==null) throw new IllegalStateException("KNOWLEDGE_GENERATION_NOT_CONFIGURED");
            return ()->Optional.ofNullable(port.profileId()).filter(value->!value.isBlank());
        }
        /**
         * 装配公开来源、图谱来源及独立同意的返回前复验器。
         * @param repository 公开来源
         * @param graph 可选图谱来源
         * @param enabled 图谱开关
         * @param access 同意
         * @param profile 本地profile
         * @return 返回前复验
         */
        @Bean public AssistantResultRevalidator assistantResultRevalidator(KnowledgeRepositoryPort repository,
                ObjectProvider<ContactGraphSourcePort> graph,@Value("${ai-friend.knowledge.graph-enabled:false}") boolean enabled,
                KnowledgeAccessPolicy access,@Qualifier("assistantGenerationProfile") Supplier<Optional<String>> profile) {
            ContactGraphSourcePort source=enabled?graph.getIfAvailable():disabledGraph();
            if(source==null) throw new IllegalStateException("KNOWLEDGE_GRAPH_NOT_CONFIGURED");
            return new AssistantResultRevalidator(repository,source,access,profile);
        }
        /**
         * 装配解密前后执行来源和同意复验的结果读取器。
         * @param sessions 会话
         * @param requests 请求
         * @param cipher 保护器
         * @param validator 复验
         * @param access 同意
         * @return 唯一安全读取
         */
        @Bean public AssistantResultReader assistantResultReader(AssistantSessionRepository sessions,AssistantTurnRepository requests,
                AssistantResultCipher cipher,AssistantResultRevalidator validator,KnowledgeAccessPolicy access) {
            return new AssistantResultReader(sessions,requests,cipher,validator,access);
        }
        /**
         * 装配有界问答服务，并根据实际生成和向量端口确定外部处理需求。
         * @param requests 请求
         * @param reader 读取
         * @param cipher 加密
         * @param validator 复验
         * @param knowledge 公开知识
         * @param graph 私人查询
         *
         * @param enabled 图谱开关
         * @param profile 本地生成profile
         * @param properties 回答模式
         * @param embedding 仅在线Embedding
         *
         * @param clock UTC时钟
         * @param executor 专用有界执行器
         * @return 已接队列但尚需HTTP入口的服务 */
        @Bean public AssistantSessionService assistantSessionService(AssistantTurnRepository requests,AssistantResultReader reader,
                AssistantResultCipher cipher,AssistantResultRevalidator validator,KnowledgeAnswerService knowledge,
                ObjectProvider<GraphQueryPort> graph,@Value("${ai-friend.knowledge.graph-enabled:false}") boolean enabled,
                @Qualifier("assistantGenerationProfile") Supplier<Optional<String>> profile,KnowledgeAnswerProperties properties,
                @Qualifier("knowledgeEmbeddingPort") ObjectProvider<EmbeddingPort> embedding,Clock clock,BoundedAssistantExecutor executor) {
            GraphQueryPort query=enabled?graph.getIfAvailable():input->{throw disabled();};
            if(query==null) throw new IllegalStateException("KNOWLEDGE_GRAPH_NOT_CONFIGURED");
            boolean external=properties.mode()==AssistantAnswer.Mode.GENERATED || embedding.getIfAvailable()!=null;
            return new AssistantSessionService(requests,reader,cipher,validator,knowledge,query,profile,clock,external,executor);
        }
        private static AssistantSessionException disabled() { return new AssistantSessionException(AssistantReason.CONFIG_INVALID); }
        private static ContactGraphSourcePort disabledGraph() {
            return new ContactGraphSourcePort() {
                @Override public GraphSnapshot snapshot(UUID owner) { throw disabled(); }
                @Override public List<ContactDisplay> displayCurrent(UUID owner,String digest,List<UUID> contacts) { throw disabled(); }
            };
        }
    }
}
