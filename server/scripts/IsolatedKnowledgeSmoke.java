import java.time.Duration;
import java.util.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.aifriend.retrieval.infrastructure.*;

/** Explicit synthetic public-knowledge real-SQL smoke; no HTTP/model or application scheduler. */
class IsolatedKnowledgeSmoke {
    static void check(boolean ok, String label) {
        if (!ok) throw new IllegalStateException("FAILED_" + label);
        System.out.println("PASS=" + label);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !"SYNTHETIC_KNOWLEDGE_SMOKE".equals(args[3])
                || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+")
                || !args[2].matches("[a-zA-Z0-9_]+") || System.console() == null)
            throw new IllegalArgumentException("Explicit isolated smoke and hidden terminal required");
        char[] password = System.console().readPassword("Test database password: ");
        if (password == null || password.length == 0) throw new IllegalArgumentException("Missing secret");
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + args[0] + ":3306/" + args[1]
                + "?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]); config.setPassword(new String(password)); Arrays.fill(password, '\0');
        config.setMaximumPoolSize(2); config.setMinimumIdle(1); config.setConnectionTimeout(10000);
        config.setPoolName("isolated-knowledge-smoke");
        try (var source = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)), "DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                    .cleanDisabled(true).baselineOnMigrate(false).load().validate();
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document",Integer.class)==0,
                    "NO_EXISTING_KNOWLEDGE_GUARD");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job",Integer.class)==0,
                    "NO_EXISTING_JOBS_GUARD");
            String run="isolated-smoke-"+UUID.randomUUID();
            System.out.println("SYNTHETIC_RUN="+run);
            var tx = new DataSourceTransactionManager(source);
            var chunker = new KnowledgeChunker(400,60,600);
            var spec = new KnowledgeImportRegistrationPort.BuildSpecification(Optional.empty(),KnowledgeTokenizer.VERSION,chunker.version());
            var register = new JdbcKnowledgeImportRegistrationAdapter(source,tx,Duration.ofMinutes(10),10);
            var reader = new JdbcKnowledgeAdapter(source,tx);
            var docs = new JdbcKnowledgeDocumentManagementAdapter(source,tx);
            var worker = new KnowledgeImportWorker(new JdbcKnowledgeImportWorkAdapter(source,tx),
                    new JdbcKnowledgeImportLeaseAdapter(source,tx),new JdbcKnowledgeBuildSourceAdapter(source,tx),
                    new JdbcKnowledgeGenerationAdapter(source,tx),chunker,
                    new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(false,0,0,0,0,0,0,0)),
                    Optional.empty(),new KnowledgeImportWorker.Settings(spec,Duration.ofMinutes(5),8,64L*1024*1024,Duration.ofSeconds(2)));
            var a=new KnowledgeImportRequest(run+"-a","合成蓝灯说明","蓝灯按钮用于打开阅读模式。","zh-CN",1,10,UUID.randomUUID().toString());
            var b=new KnowledgeImportRequest(run+"-b","合成绿灯说明","绿灯按钮用于打开夜间模式。","zh-CN",1,10,UUID.randomUUID().toString());
            var receiptA=register.register(a,spec);
            check(register.register(a,spec).id().equals(receiptA.id()),"IDEMPOTENT_REGISTRATION");
            check(worker.tick()==KnowledgeImportWorker.Outcome.READY,"IMPORT_A_READY");
            var receiptB=register.register(b,spec);
            check(worker.tick()==KnowledgeImportWorker.Outcome.READY,"IMPORT_B_READY");
            var before=reader.readActive().orElseThrow();
            check(before.documents().size()==2,"PUBLISHED_TWO_DOCUMENTS");
            var index=new Bm25KeywordIndex(before,new KnowledgeTokenizer(),1.2,0.75,64L*1024*1024);
            var hits=index.search(new RetrievalQuery("蓝灯","zh-CN",1,4),4);
            check(!hits.isEmpty() && hits.get(0).chunk().documentId().equals(receiptA.documentId()),"BM25_A_EXACT_SOURCE");
            check(index.search(new RetrievalQuery("蓝灯","en-US",1,4),4).isEmpty(),"LOCALE_ISOLATION");
            check(index.search(new RetrievalQuery("蓝灯","zh-CN",11,4),4).isEmpty(),"APP_VERSION_ISOLATION");
            check(reader.isCurrent(before.version(),List.of(hits.get(0).chunk())),"CURRENT_EVIDENCE");
            docs.invalidate(receiptA.documentId(),docs.find(receiptA.documentId()).orElseThrow().revision());
            check(!reader.isCurrent(before.version(),List.of(hits.get(0).chunk())),"DELETED_EVIDENCE_REJECTED");
            new JdbcKnowledgeDeletionRebuildAdapter(source,tx).rebuild();
            var after=reader.readActive().orElseThrow();
            check(after.documents().size()==1 && after.documents().get(0).id().equals(receiptB.documentId()),"REBUILD_RETAINS_B_ONLY");
            var rebuilt=new Bm25KeywordIndex(after,new KnowledgeTokenizer(),1.2,0.75,64L*1024*1024);
            check(rebuilt.search(new RetrievalQuery("蓝灯","zh-CN",1,4),4).isEmpty(),"DELETED_A_NOT_RETRIEVABLE");
            check(!rebuilt.search(new RetrievalQuery("绿灯","zh-CN",1,4),4).isEmpty(),"B_STILL_RETRIEVABLE");
            var cleanup=new JdbcKnowledgeHistoryMaintenanceAdapter(source,tx);
            boolean drained=false;
            for(int batch=0;batch<8;batch++) {
                if(cleanup.sweep().state()==KnowledgeHistoryMaintenancePort.State.DRAINED) { drained=true; break; }
            }
            check(drained,"BOUNDED_HISTORY_DRAINED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document_version WHERE document_id=UUID_TO_BIN(?) AND original_text IS NOT NULL",
                    Integer.class,receiptA.documentId().toString())==0,"A_ORIGINAL_PURGED");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job WHERE id=UUID_TO_BIN(?)",
                    Integer.class,receiptA.id().toString())==1,"A_IDEMPOTENCY_RECORD_RETAINED");
            check(reader.isCurrent(after.version(),after.chunks()),"B_CURRENT_AFTER_CLEANUP");
            System.out.println("SCOPE=PUBLIC_LEXICAL_SQL_ONLY_GRAPH_CONCURRENCY_MODEL_DEVICE_NOT_TESTED");
            System.out.println("DATA_RETAINED=ONE_SYNTHETIC_ACTIVE_DOCUMENT_AND_TOMBSTONES");
        } finally { config.setPassword(null); }
    }
}
