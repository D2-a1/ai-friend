package com.aifriend.consent.infrastructure;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;

/**
 * 知识用途专用的单语句授权读取，不装配为通用授权 Bean。
 * <p>仅选最新决定和政策，不加载密文或幂等材料。JdbcTemplate 参与调用方既有事务；
 * 无事务时每次独立查询，不为单条 SELECT 增加事务控制往返，不缓存或吞掉存储异常。
 * 原业务层的解密前授权、返回前新读、owner 锁和撤权清理仍必须保留。
 * @author Codex
 * @since 1.0.0
 */
public final class JdbcKnowledgeConsentQueryAdapter implements ConsentGrantQueryPort {
    private static final String LATEST = """
            SELECT decision,policy_version FROM consent_record
            WHERE user_id=UUID_TO_BIN(?) AND type=? ORDER BY sequence_no DESC LIMIT 1
            """;
    private final JdbcTemplate jdbc;

    /**
     * 创建有界只读授权适配器，构造不连接数据库。
     * @param source 与调用方事务管理器相同的数据源
     */
    public JdbcKnowledgeConsentQueryAdapter(DataSource source) {
        jdbc=new JdbcTemplate(Objects.requireNonNull(source));
        jdbc.setQueryTimeout(2);
    }

    /** {@inheritDoc} */
    @Override public boolean isGranted(UUID owner,ConsentType type) {
        return read(owner,type,null);
    }

    /** {@inheritDoc} */
    @Override public boolean isGrantedForPolicy(UUID owner,ConsentType type,String policy) {
        return read(owner,type,Objects.requireNonNull(policy));
    }

    private boolean read(UUID owner,ConsentType type,String policy) {
        Objects.requireNonNull(owner); Objects.requireNonNull(type);
        if(type!=ConsentType.CONTACT_GRAPH && type!=ConsentType.KNOWLEDGE_MODEL) {
            throw new IllegalArgumentException("NOT_A_KNOWLEDGE_CONSENT");
        }
        var decisions=jdbc.query(LATEST,(row,index)->"GRANTED".equals(row.getString("decision"))
                && (policy==null || policy.equals(row.getString("policy_version"))),owner.toString(),type.name());
        return decisions.size()==1 && decisions.get(0);
    }
}
