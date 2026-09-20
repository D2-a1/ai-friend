import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.ConcurrencyFailureException;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.aifriend.retrieval.infrastructure.*;

/** Explicit isolated real-SQL concurrency smoke. No models, publication or deployment. */
class IsolatedKnowledgeLeaseSmoke {
    static void check(boolean value, String label) {
        if (!value) throw new IllegalStateException("FAILED_"+label);
        System.out.println("PASS="+label);
    }
    public static void main(String[] args) throws Exception {
        if (args.length!=4 || !args[3].equals("SYNTHETIC_LEASE_SMOKE")
                || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+")
                || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null)
            throw new IllegalArgumentException("Explicit isolated test and hidden console required");
        char[] password=System.console().readPassword("Test database password: ");
        if (password==null || password.length==0) throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]
                +"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]); config.setPassword(new String(password)); Arrays.fill(password,'\0');
        config.setMaximumPoolSize(3); config.setMinimumIdle(2); config.setConnectionTimeout(10000);
        config.setPoolName("isolated-lease-smoke");
        try (var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                    .cleanDisabled(true).load().validate();
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job WHERE status IN ('PENDING','PROCESSING')",Integer.class)==0,
                    "NO_OTHER_PENDING_WORK");
            var activeBefore=jdbc.queryForObject("SELECT BIN_TO_UUID(active_generation_id) FROM knowledge_index_control WHERE singleton_id=1",String.class);
            var tx=new DataSourceTransactionManager(source);
            var chunker=new KnowledgeChunker(400,60,600);
            var spec=new KnowledgeImportRegistrationPort.BuildSpecification(Optional.empty(),KnowledgeTokenizer.VERSION,chunker.version());
            var register=new JdbcKnowledgeImportRegistrationAdapter(source,tx,Duration.ofMinutes(10),10);
            String run="isolated-lease-"+UUID.randomUUID(); System.out.println("SYNTHETIC_RUN="+run);
            var request=new KnowledgeImportRequest(run,"合成并发测试","此文档只验证导入租约，不发布索引。","zh-CN",1,10,UUID.randomUUID().toString());
            var receipt=register.register(request,spec);
            check(register.register(request,spec).id().equals(receipt.id()),"REGISTRATION_IDEMPOTENT");
            var lease=new JdbcKnowledgeImportLeaseAdapter(source,tx);
            var start=new CountDownLatch(1);
            var ready=new CountDownLatch(2);
            Optional<KnowledgeImportLeasePort.Claim> one,two;
            try (var executor=Executors.newFixedThreadPool(2)) {
                Callable<Optional<KnowledgeImportLeasePort.Claim>> contender=()-> {
                    ready.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("BARRIER_TIMEOUT");
                    return new JdbcKnowledgeImportLeaseAdapter(source,tx).claim(receipt.id(),UUID.randomUUID(),Duration.ofMinutes(2));
                };
                var first=executor.submit(contender); var second=executor.submit(contender);
                check(ready.await(5,TimeUnit.SECONDS),"TWO_CONTENDERS_READY"); start.countDown();
                one=first.get(15,TimeUnit.SECONDS); two=second.get(15,TimeUnit.SECONDS);
            }
            check(one.isPresent()!=two.isPresent(),"EXACTLY_ONE_LEASE_WINNER");
            var winner=one.or(()->two).orElseThrow();
            check(winner.job().attempts()==1,"SINGLE_PERSISTED_ATTEMPT");
            check(lease.claim(receipt.id(),UUID.randomUUID(),Duration.ofMinutes(2)).isEmpty(),"LIVE_LEASE_BLOCKS_ANOTHER_CLAIM");
            var failed=lease.fail(winner,KnowledgeImportJob.Failure.CONFIGURATION,false);
            check(failed.state()==KnowledgeImportJob.State.FAILED,"FAILURE_TERMINAL");
            boolean rejected=false;
            try { lease.fail(winner,KnowledgeImportJob.Failure.CONFIGURATION,false); }
            catch(ConcurrencyFailureException expected) { rejected=true; }
            check(rejected,"STALE_LEASE_REJECTED");
            check(lease.claim(receipt.id(),UUID.randomUUID(),Duration.ofMinutes(2)).isEmpty(),"TERMINAL_JOB_NOT_RECLAIMED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_control WHERE singleton_id=1 AND lease_token IS NULL AND lease_job_id IS NULL AND lease_until IS NULL",Integer.class)==1,"GLOBAL_LEASE_RELEASED");
            var docs=new JdbcKnowledgeDocumentManagementAdapter(source,tx);
            docs.invalidate(receipt.documentId(),docs.find(receipt.documentId()).orElseThrow().revision());
            check(Objects.equals(activeBefore,jdbc.queryForObject("SELECT BIN_TO_UUID(active_generation_id) FROM knowledge_index_control WHERE singleton_id=1",String.class)),"ACTIVE_INDEX_UNCHANGED");
            System.out.println("RESULT=PASS; synthetic failed job and deleted document metadata retained; no index published");
        }
    }
}
