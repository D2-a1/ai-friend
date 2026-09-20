import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeCleanupStatusAdapter;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeHistoryMaintenanceAdapter;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeDeletionRebuildAdapter;
import com.aifriend.retrieval.infrastructure.JdbcKnowledgeQuotaMaintenanceAdapter;
import com.aifriend.assistant.infrastructure.JdbcAssistantSessionLifecycleAdapter;

/**
 * Explicit empty isolated database initialization and empty-state SQL smoke.
 * Never cleans, repairs, baselines, or starts application/background workers.
 * Run from server root with its resolved test classpath and compiled classes.
 */
class IsolatedMysqlMigration {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"INIT_EMPTY_DATABASE".equals(args[3])
                || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+")
                || !args[2].matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("Explicit empty database mode required");
        if (System.console() == null) throw new IllegalStateException("Hidden interactive input required");
        char[] password = System.console().readPassword("Test database password: ");
        if (password == null || password.length == 0) throw new IllegalArgumentException("Missing secret");
        var source = new DriverManagerDataSource();
        source.setUrl("jdbc:mysql://" + args[0] + ":3306/" + args[1]
                + "?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=30000&connectionTimeZone=UTC");
        source.setUsername(args[2]);
        source.setPassword(new String(password));
        Arrays.fill(password, '\0');
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.setQueryTimeout(10);
            if (!args[1].equals(jdbc.queryForObject("SELECT DATABASE()", String.class)))
                throw new IllegalStateException("Database mismatch");
            var tables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class);
            if (tables == null || tables != 0) throw new IllegalStateException("Refusing nonempty database");
            System.out.println("EMPTY_DATABASE_GUARD=PASS");
            var flyway = Flyway.configure().dataSource(source)
                    .locations("filesystem:src/main/resources/db/migration")
                    .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true).load();
            var migration = flyway.migrate();
            System.out.println("MIGRATIONS_EXECUTED=" + migration.migrationsExecuted);
            flyway.validate();
            System.out.println("MIGRATION_VALIDATION=PASS");
            if (flyway.migrate().migrationsExecuted != 0) throw new IllegalStateException("Repeat migration changed schema");
            System.out.println("REPEAT_MIGRATION_NOOP=PASS");
            var transactions = new DataSourceTransactionManager(source);
            var status = new JdbcKnowledgeCleanupStatusAdapter(source, transactions).read();
            if (status.inactiveVersions() != 0 || status.inactiveGenerations() != 0 || status.unreferencedChunks() != 0)
                throw new IllegalStateException("Unexpected initial backlog");
            System.out.println("EMPTY_CLEANUP_STATUS=PASS");
            new JdbcKnowledgeHistoryMaintenanceAdapter(source, transactions).sweep();
            System.out.println("EMPTY_HISTORY_SQL=PASS");
            new JdbcKnowledgeDeletionRebuildAdapter(source, transactions).rebuild();
            System.out.println("EMPTY_REBUILD_SQL=PASS");
            new JdbcKnowledgeQuotaMaintenanceAdapter(source, transactions).sweep();
            System.out.println("EMPTY_QUOTA_SQL=PASS");
            if (new JdbcAssistantSessionLifecycleAdapter(source, transactions).expireBatch(16) != 0)
                throw new IllegalStateException("Unexpected expired sessions");
            System.out.println("EMPTY_SESSION_EXPIRY_SQL=PASS");
            System.out.println("SCOPE=EMPTY_STATE_ONLY_NOT_FULL_ACCEPTANCE");
        } finally { source.setPassword(null); }
    }
}
