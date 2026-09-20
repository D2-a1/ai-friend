import java.util.*;
import java.time.Instant;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import com.aifriend.consent.infrastructure.*;
import com.aifriend.consent.application.*;
import com.aifriend.consent.domain.*;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.knowledge.infrastructure.*;
import com.aifriend.shared.error.BusinessException;

/** Actual JPA repository + annotation transaction + JDBC graph integration, isolated synthetic owner. */
class IsolatedJpaConsentSmoke extends IsolatedGraphSmoke {
    static ConsentRecord record(UUID owner,ConsentDecision decision,Instant now) {
        return new ConsentRecord(UUID.randomUUID(),owner,ConsentType.CONTACT_GRAPH,decision,
            KnowledgeAccessPolicy.GRAPH_POLICY,now,now,random(),random());
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_JPA_SMOKE") || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null) throw new IllegalArgumentException("Explicit isolated mode required");
        char[] password=System.console().readPassword("Test database password: ");
        if(password==null || password.length==0) throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig(); config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]); config.setPassword(new String(password)); Arrays.fill(password,'\0');
        config.setMaximumPoolSize(3); config.setMinimumIdle(1); config.setConnectionTimeout(10000); config.setPoolName("isolated-jpa-smoke");
        try(var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("com.aifriend.consent.infrastructure");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.jdbc.time_zone","UTC",
                "hibernate.show_sql","false","hibernate.cache.use_second_level_cache","false"));
            factory.afterPropertiesSet();
            try {
                var emf=Objects.requireNonNull(factory.getObject());
                var manager=new JpaTransactionManager(emf); manager.setDataSource(source); manager.afterPropertiesSet();
                var em=SharedEntityManagerCreator.createSharedEntityManager(emf);
                var repo=new JpaRepositoryFactory(em).getRepository(ConsentRecordJpaRepository.class);
                var records=new JpaConsentRecordAdapter(repo,jdbc);
                var advice=new TransactionInterceptor(); advice.setTransactionManager(manager);
                advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); advice.afterPropertiesSet();
                var proxy=new ProxyFactory(new ConsentGrantQueryService(records)); proxy.addAdvice(advice);
                var grants=(ConsentGrantQueryPort)proxy.getProxy();
                var access=new KnowledgeAccessPolicy(grants);
                var graph=new JdbcKnowledgeGraphAdapter(source,manager,access);
                var cleanup=new JdbcGraphOwnerCleanupAdapter(source,manager);
                UUID owner=UUID.randomUUID(); System.out.println("SYNTHETIC_JPA_OWNER="+owner); account(jdbc,owner);
                var transaction=new TransactionTemplate(manager);
                transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                transaction.setTimeout(5);
                Instant time=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
                var granted=record(owner,ConsentDecision.GRANTED,time);
                try {
                    check(!grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"JPA_DEFAULT_DENIED");
                    check(Boolean.TRUE.equals(transaction.execute(status->records.append(granted))),"PRODUCTION_APPEND_GRANTED");
                    check(Boolean.FALSE.equals(transaction.execute(status->records.append(granted))),"PRODUCTION_APPEND_IDEMPOTENT");
                    check(grants.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,KnowledgeAccessPolicy.GRAPH_POLICY),"JPA_LATEST_GRANT_VISIBLE");
                    check(!grants.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"old-policy"),"JPA_WRONG_POLICY_DENIED");
                    check(!grants.isGranted(owner,ConsentType.KNOWLEDGE_MODEL),"JPA_PURPOSES_INDEPENDENT");
                    var snapshot=graph(owner);
                    check(graph.project(snapshot,0).version()==1,"JPA_CONSENT_JDBC_GRAPH_PUBLISHED");
                    transaction.execute(status->{
                        records.append(record(owner,ConsentDecision.REVOKED,time));
                        check(!grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"JPA_SEES_UNCOMMITTED_REVOKE_IN_SHARED_TRANSACTION");
                        check(cleanup.purgeOwner(owner)==6,"JPA_TRANSACTION_JDBC_PURGE");
                        status.setRollbackOnly(); return null;
                    });
                    check(grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"JPA_ROLLBACK_RESTORES_GRANT");
                    check(graph.findByOwner(owner).orElseThrow().snapshot().generation().equals(snapshot.generation()),"JPA_ROLLBACK_RESTORES_GRAPH");
                    transaction.execute(status->{records.append(record(owner,ConsentDecision.REVOKED,time));cleanup.purgeOwner(owner);return null;});
                    check(!grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"SAME_MILLISECOND_LATEST_REVOKE_WINS");
                    check(graph.findByOwner(owner).isEmpty(),"JPA_REVOKE_COMMIT_PURGES_GRAPH");
                    boolean denied=false;try{graph.project(graph(owner),0);}catch(BusinessException expected){denied=true;}
                    check(denied,"JPA_REVOKED_GRAPH_CANNOT_REPUBLISH");
                    check(records.listByUser(owner).size()==2,"JPA_HISTORY_KEEPS_ONLY_COMMITTED_RECORDS");
                } finally {
                    transaction.execute(status->{records.append(record(owner,ConsentDecision.REVOKED,Instant.now()));cleanup.purgeOwner(owner);return null;});
                }
                System.out.println("RESULT=PASS; actual JPA/annotation/JDBC transactions; synthetic owner and revoked history retained");
            } finally {factory.destroy();}
        }
    }
}
