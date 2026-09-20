import java.util.*;
import java.security.SecureRandom;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.knowledge.domain.*;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.infrastructure.*;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.*;
import com.aifriend.assistant.infrastructure.*;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.assistant.domain.AssistantSession.State;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

/** Projection persistence/rollback smoke. Account and consent SQL are synthetic fixtures,
 * not OAuth or production JPA consent integration; no real contact or external model. */
class IsolatedGraphSmoke {
    static byte[] random() { byte[] b=new byte[32]; new SecureRandom().nextBytes(b); return b; }
    static void check(boolean ok,String name) {
        if(!ok) throw new IllegalStateException("FAILED_"+name); System.out.println("PASS="+name);
    }
    static void account(JdbcTemplate jdbc, UUID owner) {
        jdbc.update("INSERT INTO app_user(id,wechat_open_id_cipher,wechat_open_id_hash,status,account_generation,accessibility_settings,created_at,updated_at) VALUES(UUID_TO_BIN(?),?,?,'ACTIVE',0,'{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",owner.toString(),random(),random());
    }
    static void consent(JdbcTemplate jdbc,UUID owner,String decision) {
        jdbc.update("INSERT INTO consent_record(id,user_id,type,decision,policy_version,confirmed_at,decided_at,idempotency_key_hash,request_hash) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'CONTACT_GRAPH',?,'contact-graph-v1',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,?)",UUID.randomUUID().toString(),owner.toString(),decision,random(),random());
    }
    static GraphSnapshot graph(UUID owner) {
        UUID generation=UUID.randomUUID(),root=UUID.randomUUID(),contact=UUID.randomUUID(),alias=UUID.randomUUID();
        return new GraphSnapshot(owner,generation,HexFormat.of().formatHex(random()),List.of(
            new GraphNode(owner,generation,root,GraphNode.Type.USER,owner,0),
            new GraphNode(owner,generation,contact,GraphNode.Type.CONTACT,UUID.randomUUID(),0),
            new GraphNode(owner,generation,alias,GraphNode.Type.ALIAS,UUID.randomUUID(),0)),List.of(
            new GraphEdge(owner,generation,UUID.randomUUID(),root,contact,GraphEdge.Type.HAS_CONTACT),
            new GraphEdge(owner,generation,UUID.randomUUID(),contact,alias,GraphEdge.Type.HAS_ALIAS)));
    }
    static void sessions(DataSource source, JdbcTemplate jdbc, DataSourceTransactionManager tx,
                         KnowledgeAccessPolicy access, UUID a, UUID b) {
        // Fresh random test-only keys; all encrypted contexts are cleared before this method exits.
        var protector=new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(random(),"HmacSHA256"),
            new SecretKeySpec(random(),"AES"),new SecretKeySpec(random(),"HmacSHA256")));
        var repository=new JdbcAssistantSessionRepository(source,tx,access,new AssistantRequestFingerprint(protector),new AssistantContextCipher(protector));
        var lifecycle=new JdbcAssistantSessionLifecycleAdapter(source,tx);
        String key=UUID.randomUUID().toString();
        var publicA=repository.create(a,Purpose.PUBLIC_KNOWLEDGE,key);
        check(repository.create(a,Purpose.PUBLIC_KNOWLEDGE,key).id().equals(publicA.id()),"SESSION_CREATE_IDEMPOTENT");
        var graphA=repository.create(a,Purpose.CONTACT_GRAPH,UUID.randomUUID().toString());
        var graphB=repository.create(b,Purpose.CONTACT_GRAPH,UUID.randomUUID().toString());
        check(repository.find(b,publicA.id()).isEmpty(),"SESSION_OWNER_ISOLATION");
        new TransactionTemplate(tx).execute(status->{lifecycle.revoke(a,Purpose.CONTACT_GRAPH); status.setRollbackOnly(); return null;});
        check(repository.find(a,graphA.id()).orElseThrow().state()==State.OPEN,"SESSION_REVOKE_ROLLBACK");
        lifecycle.revoke(a,Purpose.CONTACT_GRAPH);
        check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE id=UUID_TO_BIN(?) AND state='CLOSED' AND encrypted_context IS NULL",Integer.class,graphA.id().toString())==1,"SESSION_REVOKE_CLEARS_CONTEXT");
        check(repository.find(a,publicA.id()).orElseThrow().state()==State.OPEN,"OTHER_PURPOSE_PRESERVED");
        check(repository.find(b,graphB.id()).orElseThrow().state()==State.OPEN,"OTHER_OWNER_SESSION_PRESERVED");
        // Fixture aging only this newly created session, preserving its schema time invariants.
        check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE state='OPEN' AND expires_at<=UTC_TIMESTAMP(3)",Integer.class)==0,"NO_UNRELATED_EXPIRED_SESSIONS");
        check(jdbc.update("UPDATE assistant_session SET created_at=created_at-INTERVAL 10 MINUTE,last_activity_at=last_activity_at-INTERVAL 10 MINUTE,expires_at=expires_at-INTERVAL 10 MINUTE WHERE id=UUID_TO_BIN(?) AND owner_user_id=UUID_TO_BIN(?)",publicA.id().toString(),a.toString())==1,"AGE_ONLY_SYNTHETIC_SESSION");
        check(lifecycle.expireBatch(1)==1,"BOUNDED_EXPIRY_ONE");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE id=UUID_TO_BIN(?) AND state='EXPIRED' AND encrypted_context IS NULL",Integer.class,publicA.id().toString())==1,"EXPIRY_CLEARS_CONTEXT");
        check(repository.find(b,graphB.id()).orElseThrow().state()==State.OPEN,"EXPIRY_PRESERVES_LIVE_SESSION");
        check(lifecycle.close(b,graphB.id(),graphB.version()).state()==State.CLOSED,"EXPLICIT_CLOSE");
        check(lifecycle.expireBatch(1)==0,"REPEATED_EXPIRY_EMPTY");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE owner_user_id IN (UUID_TO_BIN(?),UUID_TO_BIN(?)) AND encrypted_context IS NOT NULL",Integer.class,a.toString(),b.toString())==0,"ALL_TEST_CONTEXTS_CLEARED");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_GRAPH_SMOKE")
                || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+")
                || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null)
            throw new IllegalArgumentException("Explicit isolated test and hidden console required");
        char[] password=System.console().readPassword("Test database password: ");
        if(password==null || password.length==0) throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig(); config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]); config.setPassword(new String(password)); Arrays.fill(password,'\0');
        config.setMaximumPoolSize(3); config.setMinimumIdle(1); config.setConnectionTimeout(10000);
        config.setPoolName("isolated-graph-smoke");
        try(var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            UUID a=UUID.randomUUID(),b=UUID.randomUUID();
            System.out.println("SYNTHETIC_OWNER_A="+a); System.out.println("SYNTHETIC_OWNER_B="+b);
            account(jdbc,a); account(jdbc,b);
            // Test-only JDBC consent bridge reads the same DB transaction; no unconditional grant mock.
            var access=new KnowledgeAccessPolicy(new ConsentGrantQueryPort() {
                public boolean isGranted(UUID owner,ConsentType type) { return isGrantedForPolicy(owner,type,KnowledgeAccessPolicy.GRAPH_POLICY); }
                public boolean isGrantedForPolicy(UUID owner,ConsentType type,String policy) {
                    var rows=jdbc.query("SELECT decision,policy_version FROM consent_record WHERE user_id=UUID_TO_BIN(?) AND type=? ORDER BY sequence_no DESC LIMIT 1",
                        (rs,n)->rs.getString(1).equals("GRANTED") && rs.getString(2).equals(policy),owner.toString(),type.name());
                    return !rows.isEmpty() && rows.get(0);
                }
            });
            var tx=new DataSourceTransactionManager(source);
            var store=new JdbcKnowledgeGraphAdapter(source,tx,access);
            var cleanup=new JdbcGraphOwnerCleanupAdapter(source,tx);
            var ga=graph(a); var gb=graph(b);
            boolean denied=false; try{store.project(ga,0);}catch(BusinessException expected){denied=true;}
            check(denied,"DEFAULT_NO_CONSENT_DENIED");
            check(store.findByOwner(a).isEmpty(),"DENIAL_WRITES_NOTHING");
            consent(jdbc,a,"GRANTED"); consent(jdbc,b,"GRANTED");
            check(store.project(ga,0).version()==1,"OWNER_A_PROJECTED");
            check(store.project(gb,0).version()==1,"OWNER_B_PROJECTED");
            sessions(source,jdbc,tx,access,a,b);
            check(store.findByOwner(a).orElseThrow().snapshot().nodes().stream().allMatch(n->n.ownerUserId().equals(a)),"OWNER_A_SCOPE");
            check(store.findByOwner(UUID.randomUUID()).isEmpty(),"UNRELATED_OWNER_EMPTY");
            boolean conflict=false; try{store.project(graph(a),0);}catch(GraphProjectionException expected){conflict=expected.kind()==GraphProjectionException.Kind.CONFLICT;}
            check(conflict,"STALE_VERSION_REJECTED");
            check(store.findByOwner(a).orElseThrow().snapshot().generation().equals(ga.generation()),"CONFLICT_PRESERVES_GENERATION");
            var outer=new TransactionTemplate(tx);
            outer.execute(status->{consent(jdbc,a,"REVOKED"); check(cleanup.purgeOwner(a)==6,"ROLLBACK_CLEANUP_APPLIED_IN_TRANSACTION"); status.setRollbackOnly(); return null;});
            check(store.findByOwner(a).isPresent(),"ROLLBACK_RESTORES_GRAPH");
            access.requireGraphConsent(a); check(true,"ROLLBACK_RESTORES_CONSENT");
            outer.execute(status->{consent(jdbc,a,"REVOKED"); cleanup.purgeOwner(a); return null;});
            check(store.findByOwner(a).isEmpty(),"REVOKE_PURGES_OWNER_A");
            check(store.findByOwner(b).orElseThrow().snapshot().generation().equals(gb.generation()),"REVOKE_PRESERVES_OWNER_B");
            denied=false; try{store.project(graph(a),0);}catch(BusinessException expected){denied=true;}
            check(denied,"REVOKED_OWNER_CANNOT_REPUBLISH");
            check(!store.purge(b,0),"PURGE_WRONG_VERSION_REJECTED");
            check(store.findByOwner(b).isPresent(),"WRONG_PURGE_PRESERVES_GRAPH");
            check(store.purge(b,1),"VERSIONED_PURGE_SUCCESS");
            check(cleanup.purgeOwner(a)==0 && cleanup.purgeOwner(b)==0,"REPEATED_CLEANUP_IDEMPOTENT");
            consent(jdbc,b,"REVOKED");
            System.out.println("RESULT=PASS; two synthetic accounts/consent histories retained; graph fixtures removed");
        }
    }
}
