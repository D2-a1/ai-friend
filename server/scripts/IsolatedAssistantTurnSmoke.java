import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.infrastructure.*;
import com.aifriend.assistant.domain.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.security.*;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.*;
import com.aifriend.retrieval.infrastructure.*;

/** Real SQL turns/quotas, synthetic no-evidence results. Does not claim HTTP/model integration. */
class IsolatedAssistantTurnSmoke extends IsolatedGraphSmoke {
    static AssistantStoredResult result(String text) {
        return new AssistantStoredResult(new AssistantAnswer(Purpose.PUBLIC_KNOWLEDGE,Status.NO_EVIDENCE,
            Mode.NONE,AssistantReason.NO_SUPPORT,text,List.of(),List.of()),null,null,null,false,null,"zh-CN",1);
    }
    static AssistantQuestion question(long version,String key,String text) {
        return new AssistantQuestion(version,key,"zh-CN",1,new AssistantQuestion.PublicText(text));
    }
    static void turns(DataSource source,JdbcTemplate jdbc,DataSourceTransactionManager tx,UUID owner) throws Exception {
        var access=new KnowledgeAccessPolicy(new ConsentGrantQueryPort(){
            public boolean isGranted(UUID user,ConsentType type){return false;}
            public boolean isGrantedForPolicy(UUID user,ConsentType type,String policy){return false;}
        }); // Public local results do not authorize or invoke any external processing.
        var protector=new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(random(),"HmacSHA256"),new SecretKeySpec(random(),"AES"),new SecretKeySpec(random(),"HmacSHA256")));
        var fingerprints=new AssistantRequestFingerprint(protector); var context=new AssistantContextCipher(protector);
        var sessions=new JdbcAssistantSessionRepository(source,tx,access,fingerprints,context);
        var turns=new JdbcAssistantTurnRepository(source,tx,access,fingerprints,context);
        var cipher=new AssistantResultCipher(protector);
        var cleanup=new JdbcAssistantSessionLifecycleAdapter(source,tx);
        try {
            var session=sessions.create(owner,Purpose.PUBLIC_KNOWLEDGE,UUID.randomUUID().toString());
            var q=question(0,UUID.randomUUID().toString(),"合成问答持久化测试");
            var admitted=turns.admit(owner,session.id(),q);
            var expected=result("没有可用的合成证据");
            byte[] encrypted=cipher.encrypt(admitted.session(),admitted.request(),expected);
            var completed=turns.complete(owner,session.id(),admitted.request().id(),admitted.request().admittedVersion(),admitted.request().leaseToken(),encrypted,Optional.empty());
            Arrays.fill(encrypted,(byte)0);
            check(admitted.execute(),"FIRST_ADMISSION_EXECUTES");
            check(completed.request().state()==AssistantTurnRequest.State.COMPLETED,"RESULT_COMPLETED");
            check(cipher.decrypt(completed.session(),completed.request(),completed.request().encryptedResult()).equals(expected),"RESULT_REAL_ENCRYPTION_ROUNDTRIP");
            var duplicate=turns.admit(owner,session.id(),q);
            check(!duplicate.execute() && duplicate.request().id().equals(completed.request().id()),"DUPLICATE_ADMISSION_NO_EXECUTION");
            check(duplicate.session().acceptedQuestions()==1,"DUPLICATE_NO_QUESTION_INCREMENT");
            byte[] replacement=cipher.encrypt(admitted.session(),admitted.request(),result("此替换内容不能覆盖原结果"));
            var repeated=turns.complete(owner,session.id(),admitted.request().id(),admitted.request().admittedVersion(),admitted.request().leaseToken(),replacement,Optional.empty());
            Arrays.fill(replacement,(byte)0);
            check(cipher.decrypt(repeated.session(),repeated.request(),repeated.request().encryptedResult()).equals(expected),"REPEATED_COMPLETE_PRESERVES_ORIGINAL");
            boolean conflict=false;
            try { turns.admit(owner,session.id(),question(0,q.requestKey(),"不同正文")); }
            catch(AssistantSessionException failure){conflict=failure.reason()==AssistantReason.IDEMPOTENCY_CONFLICT;}
            check(conflict,"SAME_KEY_DIFFERENT_BODY_REJECTED");
            boolean stale=false;
            try {turns.complete(owner,session.id(),admitted.request().id(),admitted.request().admittedVersion(),UUID.randomUUID(),new byte[32],Optional.empty());}
            catch(AssistantSessionException failure){stale=failure.reason()==AssistantReason.RESULT_STALE;}
            check(stale,"WRONG_TOKEN_REJECTED");
            check(!turns.findRequest(owner,session.id(),q.requestKey()).orElseThrow().execute(),"STATUS_QUERY_NO_EXECUTION");
            check(turns.findRequestById(owner,session.id(),admitted.request().id()).orElseThrow().request().state()==AssistantTurnRequest.State.COMPLETED,"INTERNAL_REPLAY_COMPLETED");
            var race=sessions.create(owner,Purpose.PUBLIC_KNOWLEDGE,UUID.randomUUID().toString());
            var rq=question(0,UUID.randomUUID().toString(),"合成同键并发请求");
            var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<AssistantTurnRepository.Receipt> work=()->{ready.countDown(); if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("BARRIER_TIMEOUT"); return turns.admit(owner,race.id(),rq);};
                var a=executor.submit(work); var b=executor.submit(work);
                check(ready.await(5,TimeUnit.SECONDS),"BOTH_REQUEST_THREADS_READY"); start.countDown();
                var ra=a.get(15,TimeUnit.SECONDS); var rb=b.get(15,TimeUnit.SECONDS);
                check(ra.execute()!=rb.execute(),"CONCURRENT_REQUEST_SINGLE_EXECUTOR");
                check(ra.request().id().equals(rb.request().id()),"CONCURRENT_REQUEST_SAME_LEDGER");
            }
            check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE session_id=UUID_TO_BIN(?)",Integer.class,race.id().toString())==1,"ONE_CONCURRENT_LEDGER_ROW");
        } finally {
            cleanup.revoke(owner,Purpose.PUBLIC_KNOWLEDGE);
            check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE owner_user_id=UUID_TO_BIN(?) AND encrypted_result IS NOT NULL",Integer.class,owner.toString())==0,"ALL_TEST_RESULTS_CLEARED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE owner_user_id=UUID_TO_BIN(?) AND encrypted_context IS NOT NULL",Integer.class,owner.toString())==0,"ALL_TEST_CONTEXTS_CLEARED");
        }
    }
    static void quotas(DataSource source,JdbcTemplate jdbc,DataSourceTransactionManager tx,UUID owner) {
        var quota=new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(true,1,0,0,0,0,0,0));
        var deadline=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant().plusSeconds(8);
        var request=new Reservation(UUID.randomUUID(),Optional.of(owner),Phase.REQUEST,"LOCAL",1,0,deadline);
        check(quota.reserve(request)==Decision.GRANTED,"REQUEST_QUOTA_GRANTED");
        check(quota.reserve(request)==Decision.DUPLICATE,"QUOTA_DUPLICATE_NO_NEW_GRANT");
        // Freeze deadline and do not silently extend it on retries.
        boolean conflict=false;
        try{quota.reserve(new Reservation(request.operationId(),request.ownerId(),request.phase(),"LOCAL",1,0,deadline.minusMillis(1)));}
        catch(IllegalArgumentException expected){conflict=true;}
        check(conflict,"QUOTA_CHANGED_DEADLINE_REJECTED");
        var now=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
        check(quota.reserve(new Reservation(UUID.randomUUID(),Optional.of(owner),Phase.REQUEST,"LOCAL",1,0,now.minusSeconds(1)))==Decision.EXPIRED,"EXPIRED_QUOTA_DENIED");
        check(quota.reserve(new Reservation(UUID.randomUUID(),Optional.empty(),Phase.IMPORT_EMBEDDING,"synthetic-zero-budget",1,0,now.plusSeconds(60)))==Decision.LIMIT_EXCEEDED,"ZERO_MODEL_BUDGET_DENIED");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_reservation WHERE operation_id=UUID_TO_BIN(?)",Integer.class,request.operationId().toString())==1,"ONE_QUOTA_RESERVATION");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_TURN_SMOKE") || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null) throw new IllegalArgumentException("Explicit isolated mode required");
        char[] password=System.console().readPassword("Test database password: ");
        if(password==null || password.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig(); config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]);config.setPassword(new String(password));Arrays.fill(password,'\0');
        config.setMaximumPoolSize(3);config.setMinimumIdle(2);config.setConnectionTimeout(10000);config.setPoolName("isolated-turn-smoke");
        try(var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            UUID owner=UUID.randomUUID(); System.out.println("SYNTHETIC_OWNER="+owner); account(jdbc,owner);
            var tx=new DataSourceTransactionManager(source);
            turns(source,jdbc,tx,owner); quotas(source,jdbc,tx,owner);
            System.out.println("RESULT=PASS; synthetic owner, terminal ledgers and quota metadata retained; no model calls");
        }
    }
}
