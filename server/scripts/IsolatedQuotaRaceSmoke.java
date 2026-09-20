import java.util.*;
import java.util.concurrent.*;
import java.sql.Timestamp;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifriend.retrieval.application.KnowledgeQuotaPort.*;
import com.aifriend.retrieval.infrastructure.*;

/** Explicit isolated synthetic quota race and scoped aging; no external model. */
class IsolatedQuotaRaceSmoke extends IsolatedGraphSmoke {
    static List<Decision> race(JdbcKnowledgeQuotaAdapter quota,Reservation a,Reservation b) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Decision> x=()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER_TIMEOUT");return quota.reserve(a);};
            Callable<Decision> y=()->{ready.countDown();if(!start.await(5,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER_TIMEOUT");return quota.reserve(b);};
            var first=pool.submit(x);var second=pool.submit(y);
            if(!ready.await(5,TimeUnit.SECONDS))throw new IllegalStateException("BARRIER_TIMEOUT");start.countDown();
            return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
    }
    static Reservation request(JdbcTemplate jdbc,UUID owner) {
        return new Reservation(UUID.randomUUID(),Optional.of(owner),Phase.REQUEST,"LOCAL",1,0,
            jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class).toInstant().plusSeconds(8));
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_QUOTA_RACE") || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null)throw new IllegalArgumentException("Explicit isolated mode required");
        char[] secret=System.console().readPassword("Test database password: ");
        if(secret==null || secret.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig();config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]);config.setPassword(new String(secret));Arrays.fill(secret,'\0');
        config.setMaximumPoolSize(3);config.setMinimumIdle(2);config.setConnectionTimeout(10000);
        try(var source=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            // Refuse to sweep if any pre-existing record is even a possible expiry candidate.
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_reservation WHERE expires_at<=UTC_TIMESTAMP(3)",Integer.class)==0,"NO_UNRELATED_EXPIRED_RESERVATIONS");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_bucket WHERE expires_at<=UTC_TIMESTAMP(3)",Integer.class)==0,"NO_UNRELATED_EXPIRED_BUCKETS");
            long originalReservations=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_reservation",Long.class);
            long originalBuckets=jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_bucket",Long.class);
            var tx=new DataSourceTransactionManager(source);
            var quota=new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(true,1,0,0,0,0,0,0));
            UUID same=UUID.randomUUID(),different=UUID.randomUUID(),live=UUID.randomUUID();
            System.out.println("SYNTHETIC_OWNERS="+same+","+different+","+live);
            // Quota owner keys are synthetic UUIDs, not registered real accounts.
            var one=request(jdbc,same);var duplicate=race(quota,one,one);
            check(Collections.frequency(duplicate,Decision.GRANTED)==1 && Collections.frequency(duplicate,Decision.DUPLICATE)==1,"SAME_KEY_ONE_GRANT_ONE_DUPLICATE");
            check(jdbc.queryForObject("SELECT SUM(used_count) FROM knowledge_quota_bucket WHERE owner_id=UUID_TO_BIN(?)",Long.class,same.toString())==1,"SAME_KEY_CHARGED_ONCE");
            check(jdbc.queryForObject("SELECT SECOND(UTC_TIMESTAMP(3))",Integer.class)<50,"AWAY_FROM_MINUTE_BOUNDARY");
            var two=request(jdbc,different);var three=new Reservation(UUID.randomUUID(),two.ownerId(),two.phase(),two.profileId(),1,0,two.deadline());
            var distinct=race(quota,two,three);
            check(Collections.frequency(distinct,Decision.GRANTED)==1 && Collections.frequency(distinct,Decision.LIMIT_EXCEEDED)==1,"DISTINCT_KEYS_OWNER_CAP_ENFORCED");
            check(jdbc.queryForObject("SELECT SUM(used_count) FROM knowledge_quota_bucket WHERE owner_id=UUID_TO_BIN(?)",Long.class,different.toString())==1,"DENIAL_NOT_CHARGED");
            var keep=request(jdbc,live);check(quota.reserve(keep)==Decision.GRANTED,"LIVE_RESERVATION_CREATED");
            check(jdbc.update("UPDATE knowledge_quota_reservation SET deadline=deadline-INTERVAL 4 DAY,created_at=created_at-INTERVAL 4 DAY,expires_at=expires_at-INTERVAL 4 DAY WHERE owner_id IN(UUID_TO_BIN(?),UUID_TO_BIN(?))",same.toString(),different.toString())==3,"AGE_ONLY_THREE_SYNTHETIC_RESERVATIONS");
            check(jdbc.update("UPDATE knowledge_quota_bucket SET window_start=window_start-INTERVAL 4 DAY,expires_at=expires_at-INTERVAL 4 DAY WHERE owner_id IN(UUID_TO_BIN(?),UUID_TO_BIN(?))",same.toString(),different.toString())==2,"AGE_ONLY_TWO_SYNTHETIC_BUCKETS");
            var maintenance=new JdbcKnowledgeQuotaMaintenanceAdapter(source,tx);var removed=maintenance.sweep();
            check(removed.reservations()==3 && removed.buckets()==2,"EXPIRED_SYNTHETIC_ROWS_REMOVED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_reservation",Long.class)==originalReservations+1,"PREEXISTING_AND_LIVE_RESERVATIONS_PRESERVED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_quota_bucket",Long.class)==originalBuckets+1,"PREEXISTING_AND_LIVE_BUCKETS_PRESERVED");
            check(quota.reserve(keep)==Decision.DUPLICATE,"LIVE_IDEMPOTENCY_PRESERVED");
            var again=maintenance.sweep();check(again.reservations()==0 && again.buckets()==0,"REPEATED_SWEEP_EMPTY");
            System.out.println("RESULT=PASS; live synthetic reservation retained; only aged synthetic rows physically removed");
        }
    }
}
