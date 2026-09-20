import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import com.aifriend.consent.infrastructure.*;
import com.aifriend.consent.application.*;
import com.aifriend.consent.domain.*;

/** Test composition of actual production JPA consent components, no schema generation. */
final class IsolatedJpaConsentContext implements AutoCloseable {
    final LocalContainerEntityManagerFactoryBean factory=new LocalContainerEntityManagerFactoryBean();
    final JpaTransactionManager transactions;
    final ConsentGrantQueryPort grants;
    final JpaConsentRecordAdapter records;
    final JdbcTemplate jdbc;
    IsolatedJpaConsentContext(DataSource source) {
        jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        factory.setDataSource(source);factory.setPackagesToScan("com.aifriend.consent.infrastructure");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.jdbc.time_zone","UTC","hibernate.show_sql","false","hibernate.cache.use_second_level_cache","false"));factory.afterPropertiesSet();
        transactions=new JpaTransactionManager(Objects.requireNonNull(factory.getObject()));transactions.setDataSource(source);transactions.afterPropertiesSet();
        var em=SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        records=new JpaConsentRecordAdapter(new JpaRepositoryFactory(em).getRepository(ConsentRecordJpaRepository.class),jdbc);
        var advice=new TransactionInterceptor();advice.setTransactionManager(transactions);advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());advice.afterPropertiesSet();
        var proxy=new ProxyFactory(new ConsentGrantQueryService(records));proxy.addAdvice(advice);grants=(ConsentGrantQueryPort)proxy.getProxy();
    }
    void append(UUID owner,ConsentDecision decision) {
        var time=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
        var record=new ConsentRecord(UUID.randomUUID(),owner,ConsentType.CONTACT_GRAPH,decision,"contact-graph-v1",time,time,IsolatedGraphSmoke.random(),IsolatedGraphSmoke.random());
        new TransactionTemplate(transactions).execute(s->records.append(record));
    }
    @Override public void close(){factory.destroy();}
}
