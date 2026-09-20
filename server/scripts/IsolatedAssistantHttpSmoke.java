import java.util.*;
import java.time.*;
import javax.crypto.spec.SecretKeySpec;
import com.zaxxer.hikari.*;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.aifriend.assistant.api.*;
import com.aifriend.assistant.application.*;
import com.aifriend.assistant.infrastructure.*;
import com.aifriend.assistant.domain.AssistantAnswer.*;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.identity.application.*;
import com.aifriend.identity.infrastructure.JdbcActiveAccountStatusAdapter;
import com.aifriend.shared.security.*;
import com.aifriend.shared.api.GlobalExceptionHandler;
import com.aifriend.retrieval.infrastructure.*;
import com.aifriend.retrieval.application.*;
import com.aifriend.retrieval.domain.*;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;

/** In-process HTTP dispatch with production JWT filters and real SQL; no socket listener/model/device. */
class IsolatedAssistantHttpSmoke extends IsolatedGraphSmoke {
    static final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.atomic.LongAdder> calls=new java.util.concurrent.ConcurrentHashMap<>(),nanos=new java.util.concurrent.ConcurrentHashMap<>();
    static Object measured(Object target,Class<?> contract) {
        return java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,arguments)->{
            long start=System.nanoTime();
            try {
                Object result=method.invoke(target,arguments);
                if(result instanceof java.sql.PreparedStatement statement) return measured(statement,java.sql.PreparedStatement.class);
                return result;
            } catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            finally {
                if(Set.of("executeQuery","executeUpdate","execute","commit","rollback","setAutoCommit","setReadOnly","setTransactionIsolation","getTransactionIsolation").contains(method.getName())) {
                    String key=Thread.currentThread().getName()+":"+method.getName();
                    calls.computeIfAbsent(key,k->new java.util.concurrent.atomic.LongAdder()).increment();
                    nanos.computeIfAbsent(key,k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-start);
                }
            }
        });
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class,KnowledgeAdminSecurityConfiguration.class,AssistantApiExceptionHandler.class,GlobalExceptionHandler.class})
    static class WebConfig { }
    static String token(JwtEncoder encoder,UUID owner,String device) {
        return token(encoder,owner,device,false);
    }
    static String token(JwtEncoder encoder,UUID owner,String device,boolean manage) {
        var now=Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer("isolated-http").audience(List.of("isolated-http"))
            .subject(PublicIdCodec.userId(owner)).issuedAt(now).expiresAt(now.plusSeconds(300))
            .claim("device_public_key_sha256",device).claim("scope",manage?"knowledge:manage":"").build())).getTokenValue();
    }
    static JsonNode body(MvcResult result,ObjectMapper mapper,int status) throws Exception {
        if(result.getResponse().getStatus()!=status) {
            var error=mapper.readTree(result.getResponse().getContentAsByteArray());
            System.out.println("HTTP_FAILURE_STATUS="+result.getResponse().getStatus()+"; CODE="+error.path("code").asText()+"; REASON="+error.path("data").path("reasonCode").asText());
        }
        check(result.getResponse().getStatus()==status,"HTTP_STATUS_"+status);
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("SYNTHETIC_HTTP_SMOKE","SYNTHETIC_HTTP_DB_CLOCK_SMOKE","SYNTHETIC_IMPORT_HTTP_SMOKE").contains(args[3]) || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null) throw new IllegalArgumentException("Explicit isolated mode required");
        boolean importMode=args[3].equals("SYNTHETIC_IMPORT_HTTP_SMOKE");
        char[] password=System.console().readPassword("Test database password: ");
        if(password==null || password.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig(); config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]);config.setPassword(new String(password));Arrays.fill(password,'\0');
        config.setMaximumPoolSize(4);config.setMinimumIdle(2);config.setConnectionTimeout(10000);config.setPoolName("isolated-http-smoke");
        try(var pool=new HikariDataSource(config)) {
            var source=new org.springframework.jdbc.datasource.DelegatingDataSource(pool) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {return (java.sql.Connection)measured(super.getConnection(),java.sql.Connection.class);}
            };
            var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            var offsets=new ArrayList<Long>();boolean synchronizedClocks=true;
            for(int sample=0;sample<3;sample++) {
                Instant start=Instant.now();
                Instant database=jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",java.sql.Timestamp.class).toInstant();
                Instant end=Instant.now();
                long roundTrip=Duration.between(start,end).toMillis();
                long offset=Duration.between(start.plusMillis(roundTrip/2),database).toMillis();
                System.out.println("DB_CLOCK_OFFSET_MS="+offset+"; ROUND_TRIP_MS="+roundTrip);
                offsets.add(offset);
                if(Math.abs(offset)>1000+roundTrip/2) synchronizedClocks=false;
            }
            offsets.sort(Long::compareTo);
            boolean aligned=args[3].equals("SYNTHETIC_HTTP_DB_CLOCK_SMOKE");
            // Production uses a monotonic shared budget; clock skew is evidence, not a test bypass.
            System.out.println("DB_APP_CLOCKS_SYNCHRONIZED="+synchronizedClocks);
            Clock applicationClock=aligned?Clock.offset(Clock.systemUTC(),Duration.ofMillis(offsets.get(1))):Clock.systemUTC();
            System.out.println("TEST_ONLY_DB_ALIGNED_CLOCK="+aligned+"; ORIGINAL_DEADLINE_SECONDS=8");
            UUID owner=UUID.randomUUID(),other=UUID.randomUUID(); account(jdbc,owner);account(jdbc,other);
            System.out.println("SYNTHETIC_HTTP_OWNER="+owner);System.out.println("SYNTHETIC_HTTP_OTHER="+other);
            var tx=new DataSourceTransactionManager(source);
            var keys=new SecurityKeyMaterial(new SecretKeySpec(random(),"HmacSHA256"),new SecretKeySpec(random(),"AES"),new SecretKeySpec(random(),"HmacSHA256"));
            var protector=new SensitiveDataProtector(keys);
            var access=new KnowledgeAccessPolicy(new ConsentGrantQueryPort(){
                public boolean isGranted(UUID user,ConsentType type){return false;}
                public boolean isGrantedForPolicy(UUID user,ConsentType type,String policy){return false;}
            }); // All consents absent, public EXTRACTIVE only; graph capability is off.
            var fingerprints=new AssistantRequestFingerprint(protector);var cipher=new AssistantContextCipher(protector);
            var sessions=new JdbcAssistantSessionRepository(source,tx,access,fingerprints,cipher);
            var requests=new JdbcAssistantTurnRepository(source,tx,access,fingerprints,cipher);
            var lifecycle=new JdbcAssistantSessionLifecycleAdapter(source,tx);
            var resultCipher=new AssistantResultCipher(protector);var repository=new JdbcKnowledgeAdapter(source,tx);
            var documents=new JdbcKnowledgeDocumentManagementAdapter(source,tx);
            var chunker=new KnowledgeChunker(400,60,600);
            var specification=new KnowledgeImportRegistrationPort.BuildSpecification(Optional.empty(),KnowledgeTokenizer.VERSION,chunker.version());
            var registration=new JdbcKnowledgeImportRegistrationAdapter(source,tx,Duration.ofMinutes(10),10);
            UUID[] imported={null};
            Set<UUID> baseline=new HashSet<>();
            if(importMode) repository.readActive().ifPresent(snapshot->snapshot.documents().forEach(d->baseline.add(d.id())));
            var quota=new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(true,60,0,0,0,0,0,0));
            var knowledge=new KnowledgeAnswerService(repository,new LocalKnowledgeSearchAdapter(repository,1.2,.75,64L*1024*1024),new RrfFusion(60),Optional.empty(),Optional.empty(),quota,access,new KnowledgeAnswerService.Settings(Mode.EXTRACTIVE,Duration.ofSeconds(4)),applicationClock);
            var forbiddenGraph=new ContactGraphSourcePort(){
                public GraphSnapshot snapshot(UUID id){throw new AssertionError("PRIVATE_GRAPH_MUST_NOT_BE_READ");}
                public List<ContactDisplay> displayCurrent(UUID id,String digest,List<UUID> ids){throw new AssertionError("PRIVATE_GRAPH_MUST_NOT_BE_READ");}
            };
            var validator=new AssistantResultRevalidator(repository,forbiddenGraph,access,Optional::empty);
            var reader=new AssistantResultReader(sessions,requests,resultCipher,validator,access);
            try(var executor=new BoundedAssistantExecutor(new AssistantExecutionProperties(1,2),applicationClock);var web=new GenericWebApplicationContext()) {
                var service=new AssistantSessionService(requests,reader,resultCipher,validator,knowledge,q->{throw new AssertionError("GRAPH_DISABLED");},Optional::empty,applicationClock,false,executor);
                var mapper=new ObjectMapper().findAndRegisterModules();String device=HexFormat.of().formatHex(random());
                var jwt=new JwtConfiguration();var encoder=jwt.jwtEncoder(keys);
                var properties=new IdentitySecurityProperties("isolated-http","isolated-http",Duration.ofMinutes(5),Duration.ofDays(1),"","","");
                web.setServletContext(new org.springframework.mock.web.MockServletContext());
                web.registerBean(ObjectMapper.class,()->mapper);
                web.registerBean(ActiveAccountStatusPort.class,()->new JdbcActiveAccountStatusAdapter(jdbc));
                web.registerBean(DeviceTrustService.class,()->new DeviceTrustService(new DeviceTrustProperties(true,List.of(device)),new DigestService()));
                web.registerBean(JwtDecoder.class,()->jwt.jwtDecoder(keys,properties));
                web.registerBean(AssistantSessionRepository.class,()->sessions);web.registerBean(AssistantSessionService.class,()->service);web.registerBean(AssistantResultReader.class,()->reader);
                web.registerBean(AssistantSessionController.class,()->new AssistantSessionController(web.getBeanProvider(AssistantSessionRepository.class),web.getBeanProvider(AssistantSessionService.class),web.getBeanProvider(AssistantResultReader.class),lifecycle,true,false));
                if(importMode) {
                    web.registerBean(KnowledgeImportRegistrationPort.class,()->registration);
                    web.registerBean(KnowledgeImportRegistrationPort.BuildSpecification.class,()->specification);
                    web.registerBean(KnowledgeAdminController.class,()->new KnowledgeAdminController(web.getBeanProvider(KnowledgeImportRegistrationPort.class),web.getBeanProvider(KnowledgeImportRegistrationPort.BuildSpecification.class),documents,true,true,web.getBeanProvider(KnowledgeCleanupStatusPort.class),false));
                }
                new AnnotatedBeanDefinitionReader(web).register(WebConfig.class);web.refresh();
                var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
                String auth="Bearer "+token(encoder,owner,device);
                String create=mapper.writeValueAsString(Map.of("purpose","PUBLIC_KNOWLEDGE","clientRequestId",UUID.randomUUID().toString()));
                try {
                    if(importMode) {
                        check(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_import_job WHERE status IN ('PENDING','PROCESSING')",Integer.class)==0,"NO_EXISTING_IMPORT_WORK");
                        String admin="Bearer "+token(encoder,owner,device,true);
                        String payload=mapper.writeValueAsString(Map.of("sourceKey","http-smoke-"+UUID.randomUUID(),"title","合成紫晶灯说明","text","紫晶灯按钮用于开启合成测试模式。","locale","zh-CN","minimumAppVersionCode",1,"maximumAppVersionCode",10,"idempotencyKey",UUID.randomUUID().toString()));
                        body(mvc.perform(post("/admin/knowledge/imports").header("Authorization",auth).contentType("application/json").content(payload)).andReturn(),mapper,403);
                        var receipt=body(mvc.perform(post("/admin/knowledge/imports").header("Authorization",admin).contentType("application/json").content(payload)).andReturn(),mapper,202).path("data");
                        imported[0]=UUID.fromString(receipt.path("documentId").asText());
                        var duplicate=body(mvc.perform(post("/admin/knowledge/imports").header("Authorization",admin).contentType("application/json").content(payload)).andReturn(),mapper,202).path("data");
                        check(duplicate.path("id").equals(receipt.path("id")),"HTTP_IMPORT_IDEMPOTENT");
                        var worker=new KnowledgeImportWorker(new JdbcKnowledgeImportWorkAdapter(source,tx),new JdbcKnowledgeImportLeaseAdapter(source,tx),new JdbcKnowledgeBuildSourceAdapter(source,tx),new JdbcKnowledgeGenerationAdapter(source,tx),chunker,new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(false,0,0,0,0,0,0,0)),Optional.empty(),new KnowledgeImportWorker.Settings(specification,Duration.ofMinutes(5),8,64L*1024*1024,Duration.ofSeconds(2)));
                        check(worker.tick()==KnowledgeImportWorker.Outcome.READY,"HTTP_IMPORT_WORKER_PUBLISHED");
                        var ready=body(mvc.perform(get("/admin/knowledge/imports/"+receipt.path("id").asText()).header("Authorization",admin)).andReturn(),mapper,200).path("data");
                        check(ready.path("status").asText().equals("READY"),"HTTP_IMPORT_READY_VISIBLE");
                    }
                    body(mvc.perform(post("/assistant/sessions").contentType("application/json").content(create)).andReturn(),mapper,401);
                    body(mvc.perform(post("/assistant/sessions").header("Authorization","Bearer invalid").contentType("application/json").content(create)).andReturn(),mapper,401);
                    body(mvc.perform(post("/assistant/sessions").header("Authorization","Bearer "+token(encoder,owner,"00".repeat(32))).contentType("application/json").content(create)).andReturn(),mapper,403);
                    var created=body(mvc.perform(post("/assistant/sessions").header("Authorization",auth).contentType("application/json").content(create)).andReturn(),mapper,200).path("data");
                    String id=created.path("id").asText();check(!id.isBlank(),"AUTHENTICATED_SESSION_CREATED");
                    check(!created.has("owner")&&!created.has("conversation"),"HTTP_METADATA_MINIMIZED");
                    String key=UUID.randomUUID().toString();String question=mapper.writeValueAsString(Map.of("expectedVersion",0,"requestKey",key,"locale","zh-CN","appVersionCode",1,"text",importMode?"紫晶灯":"绿灯"));
                    var expectedChunks=repository.readActive().orElseThrow().chunks().stream()
                        .filter(c->importMode?c.documentId().equals(imported[0]):c.text().equals("绿灯按钮用于打开夜间模式。")).toList();
                    check(expectedChunks.size()==1,"UNIQUE_SYNTHETIC_GREEN_FIXTURE");
                    var expected=expectedChunks.get(0);
                    calls.clear();nanos.clear();long requestStart=System.nanoTime();
                    var answer=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,200).path("data");
                    System.out.println("ASK_ELAPSED_MS="+(System.nanoTime()-requestStart)/1_000_000);
                    calls.keySet().stream().sorted().forEach(k->System.out.println("SQL_TIMING="+k+"; CALLS="+calls.get(k).sum()+"; MS="+nanos.get(k).sum()/1_000_000));
                    for(int i=0;i<8 && answer.path("status").asText().equals("PROCESSING");i++) {
                        Thread.sleep(250);
                        answer=body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization",auth)).andReturn(),mapper,200).path("data");
                    }
                    System.out.println("ANSWER_STATUS="+answer.path("status").asText()+"; REASON="+answer.path("reasonCode").asText());
                    check(answer.path("status").asText().equals("EVIDENCE_ONLY"),"HTTP_REAL_LEXICAL_ANSWER");
                    check(answer.path("answerMode").asText().equals("EXTRACTIVE") && answer.path("candidates").isEmpty(),"PUBLIC_EXTRACTIVE_NO_PRIVATE_CANDIDATES");
                    check(answer.path("citations").size()==1,"EXACT_CITATION_COUNT");
                    var citation=answer.path("citations").get(0);
                    check(citation.path("evidenceId").asText().equals("e1")
                        && citation.path("documentId").asText().equals(expected.documentId().toString())
                        && citation.path("documentVersion").asLong()==expected.documentVersion()
                        && citation.path("chunkId").asText().equals(expected.id().toString())
                        && citation.path("text").asText().equals(expected.text())
                        && citation.path("title").asText().equals(expected.heading().isBlank()?"未命名片段":expected.heading())
                        && citation.path("sourceStart").asLong()==expected.sourceStart()
                        && citation.path("sourceEnd").asLong()==expected.sourceEnd(),"EXACT_CURRENT_SOURCE_CITATION");
                    check(answer.path("text").asText().equals("找到以下原文说明：\n[e1] "+expected.text()),"EXACT_EXTRACTIVE_TEXT");
                    var replay=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,200).path("data");
                    check(replay.path("status").asText().equals("EVIDENCE_ONLY"),"HTTP_REPLAY_REVALIDATED");
                    check(replay.equals(answer),"HTTP_REPLAY_EXACT_RESULT");
                    var fetched=body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization",auth)).andReturn(),mapper,200).path("data");
                    check(fetched.equals(answer),"HTTP_GET_EXACT_RESULT");
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE session_id=UUID_TO_BIN(?)",Integer.class,id)==1,"HTTP_REPLAY_ONE_LEDGER");
                    body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization","Bearer "+token(encoder,other,device))).andReturn(),mapper,404);
                    if(importMode) {
                        var current=documents.find(imported[0]).orElseThrow();
                        var deleted=mvc.perform(delete("/admin/knowledge/documents/"+imported[0]).param("expectedVersion",Long.toString(current.revision())).header("Authorization","Bearer "+token(encoder,owner,device,true))).andReturn();
                        check(deleted.getResponse().getStatus()==204,"HTTP_DOCUMENT_INVALIDATED");
                        var staleGet=body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization",auth)).andReturn(),mapper,409);
                        var staleReplay=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,409);
                        for(var rejected:List.of(staleGet,staleReplay)) {
                            check(rejected.path("data").path("reasonCode").asText().equals("EVIDENCE_INVALIDATED"),"DELETED_SOURCE_REJECTED_WHILE_SESSION_OPEN");
                            check(!rejected.toString().contains(expected.text()) && !rejected.toString().contains(expected.id().toString()) && !rejected.toString().contains(expected.documentId().toString()),"DELETED_SOURCE_NOT_DISCLOSED");
                        }
                        check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE session_id=UUID_TO_BIN(?)",Integer.class,id)==1,"STALE_REPLAY_NO_NEW_TURN");
                    }
                    long version=sessions.find(owner,UUID.fromString(id)).orElseThrow().version();
                    body(mvc.perform(delete("/assistant/sessions/"+id).param("expectedVersion",Long.toString(version)).header("Authorization",auth)).andReturn(),mapper,200);
                } finally {
                    jdbc.query("SELECT r.state,r.admitted_version,r.result_version,TIMESTAMPDIFF(MICROSECOND,r.created_at,r.deadline) budget_us,s.version,s.accepted_questions FROM assistant_turn_request r JOIN assistant_session s ON s.id=r.session_id WHERE r.owner_user_id=UUID_TO_BIN(?)",
                        (org.springframework.jdbc.core.RowCallbackHandler)rs->System.out.println("HTTP_LEDGER_STATE="+rs.getString(1)+"; ADMITTED="+rs.getLong(2)+"; RESULT_VERSION="+rs.getString(3)+"; BUDGET_US="+rs.getLong(4)+"; SESSION_VERSION="+rs.getLong(5)+"; QUESTIONS="+rs.getLong(6)),owner.toString());
                    executor.close();lifecycle.revoke(owner,Purpose.PUBLIC_KNOWLEDGE);
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE owner_user_id=UUID_TO_BIN(?) AND encrypted_result IS NOT NULL",Integer.class,owner.toString())==0,"HTTP_TEST_RESULTS_CLEARED");
                    if(imported[0]!=null) {
                        var current=documents.find(imported[0]).orElseThrow();
                        if(!current.deleted())documents.invalidate(current.id(),current.revision());
                        new JdbcKnowledgeDeletionRebuildAdapter(source,tx).rebuild();
                        var remaining=new HashSet<UUID>();
                        repository.readActive().ifPresent(snapshot->snapshot.documents().forEach(d->remaining.add(d.id())));
                        check(remaining.equals(baseline),"IMPORT_CLEANUP_PRESERVES_BASELINE");
                    }
                }
            }
            System.out.println("RESULT=PASS; production JWT/filters/controller/service/SQL lexical flow; no remote HTTP deployment");
        }
    }
}
