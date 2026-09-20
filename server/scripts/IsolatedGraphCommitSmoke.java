import java.util.*;
import java.sql.*;
import java.util.concurrent.atomic.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.infrastructure.*;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;

/** Test-only loss of commit acknowledgement AFTER a real successful MySQL commit. */
class IsolatedGraphCommitSmoke extends IsolatedGraphSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_COMMIT_LOSS") || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null)throw new IllegalArgumentException("Explicit isolated mode required");
        char[] secret=System.console().readPassword("Test database password: ");if(secret==null || secret.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig();config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");config.setUsername(args[2]);config.setPassword(new String(secret));Arrays.fill(secret,'\0');config.setMaximumPoolSize(3);
        try(var pool=new HikariDataSource(config)) {
            var armed=new AtomicBoolean();var injected=new AtomicInteger();
            var data=new DelegatingDataSource(pool){
                @Override public Connection getConnection() throws SQLException {
                    Connection real=super.getConnection();
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                        try {
                            Object result=m.invoke(real,a);
                            if(m.getName().equals("commit") && armed.compareAndSet(true,false)){injected.incrementAndGet();throw new SQLException("SYNTHETIC_ACK_LOSS","08007");}
                            return result;
                        }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
            var jdbc=new JdbcTemplate(data);jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(data).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            var tx=new DataSourceTransactionManager(data);
            var access=new KnowledgeAccessPolicy(new ConsentGrantQueryPort(){
                public boolean isGranted(UUID owner,ConsentType type){return isGrantedForPolicy(owner,type,KnowledgeAccessPolicy.GRAPH_POLICY);}
                public boolean isGrantedForPolicy(UUID owner,ConsentType type,String policy){var rows=jdbc.query("SELECT decision,policy_version FROM consent_record WHERE user_id=UUID_TO_BIN(?) AND type=? ORDER BY sequence_no DESC LIMIT 1",(r,n)->r.getString(1).equals("GRANTED")&&r.getString(2).equals(policy),owner.toString(),type.name());return !rows.isEmpty()&&rows.get(0);}
            });
            var store=new JdbcKnowledgeGraphAdapter(data,tx,access);var cleanup=new JdbcGraphOwnerCleanupAdapter(data,tx);
            UUID owner=UUID.randomUUID();System.out.println("SYNTHETIC_OWNER="+owner);account(jdbc,owner);consent(jdbc,owner,"GRANTED");
            try {
                for(long expected=0;expected<2;expected++) {
                    var snapshot=graph(owner);armed.set(true);boolean unavailable=false;
                    try{store.project(snapshot,expected);}catch(GraphProjectionException e){unavailable=e.kind()==GraphProjectionException.Kind.STORAGE_UNAVAILABLE && e.getCause()==null;}
                    check(unavailable,"COMMIT_ACK_LOSS_REPORTED_WITHOUT_SENSITIVE_CAUSE");
                    check(!armed.get() && injected.get()==expected+1,"EXACTLY_ONE_COMMIT_FAULT");
                    var saved=store.findByOwner(owner).orElseThrow();
                    check(saved.version()==expected+1,"NO_AUTOMATIC_REPUBLICATION");
                    check(saved.snapshot().generation().equals(snapshot.generation()) && saved.snapshot().sourceDigest().equals(snapshot.sourceDigest()) && saved.snapshot().nodes().size()==3 && saved.snapshot().edges().size()==2,"DURABLE_COMPLETE_GENERATION_FOUND");
                    boolean conflict=false;try{store.project(snapshot,expected);}catch(GraphProjectionException e){conflict=e.kind()==GraphProjectionException.Kind.CONFLICT;}
                    check(conflict,"STALE_RETRY_CAS_REJECTED");
                    check(store.findByOwner(owner).orElseThrow().version()==expected+1,"CONFLICT_PRESERVES_VERSION");
                }
            } finally {
                armed.set(false);consent(jdbc,owner,"REVOKED");check(cleanup.purgeOwner(owner)==6,"SYNTHETIC_GRAPH_CLEARED");
            }
            System.out.println("RESULT=PASS; test-only commit acknowledgement fault, no network/firewall changes");
        }
    }
}
