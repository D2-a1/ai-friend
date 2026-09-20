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
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;

/** In-process HTTP dispatch with production JWT filters and real SQL; no socket listener/model/device. */
class IsolatedGraphHttpSmoke extends IsolatedGraphSmoke {
    static final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.atomic.LongAdder> stageCalls=new java.util.concurrent.ConcurrentHashMap<>(),stageNanos=new java.util.concurrent.ConcurrentHashMap<>();
    // Interface timings are nested, not additive; never print arguments, results or exception messages.
    static <T> T stage(Class<T> contract,T target) {
        return contract.cast(java.lang.reflect.Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,arguments)->{
            long start=System.nanoTime();
            try { return method.invoke(target,arguments); }
            catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            finally {
                if(method.getDeclaringClass()!=Object.class) {
                    String key=Thread.currentThread().getName()+":"+contract.getSimpleName()+"."+method.getName();
                    stageCalls.computeIfAbsent(key,k->new java.util.concurrent.atomic.LongAdder()).increment();
                    stageNanos.computeIfAbsent(key,k->new java.util.concurrent.atomic.LongAdder()).add(System.nanoTime()-start);
                }
            }
        }));
    }
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
    @Import({SecurityConfiguration.class,AssistantApiExceptionHandler.class,GlobalExceptionHandler.class})
    static class WebConfig { }
    static String token(JwtEncoder encoder,UUID owner,String device) {
        var now=Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer("isolated-http").audience(List.of("isolated-http"))
            .subject(PublicIdCodec.userId(owner)).issuedAt(now).expiresAt(now.plusSeconds(300))
            .claim("device_public_key_sha256",device).build())).getTokenValue();
    }
    static JsonNode body(MvcResult result,ObjectMapper mapper,int status) throws Exception {
        if(result.getResponse().getStatus()!=status) {
            var error=mapper.readTree(result.getResponse().getContentAsByteArray());
            System.out.println("HTTP_FAILURE_STATUS="+result.getResponse().getStatus()+"; CODE="+error.path("code").asText()+"; REASON="+error.path("data").path("reasonCode").asText());
        }
        check(result.getResponse().getStatus()==status,"HTTP_STATUS_"+status);
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
    static boolean containsPrivateResult(JsonNode result,Map<String,Set<String>> expected) {
        String content=result.toString();
        return expected.keySet().stream().anyMatch(content::contains)
                || expected.values().stream().flatMap(Set::stream).anyMatch(content::contains);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("SYNTHETIC_GRAPH_HTTP_DB_CLOCK_SMOKE","SYNTHETIC_GRAPH_HTTP_APP_CLOCK_SMOKE","SYNTHETIC_GRAPH_HTTP_APP_CLOCK_COLD_SMOKE").contains(args[3]) || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null) throw new IllegalArgumentException("Explicit isolated mode required");
        int contactCount=Integer.getInteger("isolated.graph.contacts",1),aliasCount=Integer.getInteger("isolated.graph.aliases",1);
        if(contactCount<1 || contactCount>20 || aliasCount<1 || aliasCount>5)throw new IllegalArgumentException("Invalid synthetic fixture size");
        System.out.println("SYNTHETIC_CONTACT_COUNT="+contactCount+"; ALIASES_PER_CONTACT="+aliasCount);
        char[] password=System.console().readPassword("Test database password: ");
        if(password==null || password.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig(); config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        // Test-only single-variable experiment; do not propagate to production config.
        boolean localState=Boolean.getBoolean("isolated.graph.localSessionState");
        config.addDataSourceProperty("useLocalSessionState",Boolean.toString(localState));
        System.out.println("TEST_ONLY_LOCAL_SESSION_STATE="+localState);
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
            boolean aligned=args[3].equals("SYNTHETIC_GRAPH_HTTP_DB_CLOCK_SMOKE");
            System.out.println("CLOCK_MODE="+(aligned?"TEST_DB_OFFSET":"UNMODIFIED_APP_CLOCK")+" clocksSynchronized="+synchronizedClocks);
            Clock applicationClock=aligned?Clock.offset(Clock.systemUTC(),Duration.ofMillis(offsets.get(1))):Clock.systemUTC();
            System.out.println("TEST_ONLY_DB_ALIGNED_CLOCK="+aligned+"; ORIGINAL_DEADLINE_SECONDS=8");
            UUID owner=UUID.randomUUID(),other=UUID.randomUUID(); account(jdbc,owner);account(jdbc,other);
            System.out.println("SYNTHETIC_HTTP_OWNER="+owner);System.out.println("SYNTHETIC_HTTP_OTHER="+other);
            try(var jpa=new IsolatedJpaConsentContext(source)) {
            var tx=jpa.transactions;
            var keys=new SecurityKeyMaterial(new SecretKeySpec(random(),"HmacSHA256"),new SecretKeySpec(random(),"AES"),new SecretKeySpec(random(),"HmacSHA256"));
            var protector=new SensitiveDataProtector(keys);
            // Match the production knowledge-only read adapter; writes still use real JPA consent service.
            var access=new KnowledgeAccessPolicy(new com.aifriend.consent.infrastructure.JdbcKnowledgeConsentQueryAdapter(source));
            var fingerprints=new AssistantRequestFingerprint(protector);var cipher=new AssistantContextCipher(protector);
            var sessions=new JdbcAssistantSessionRepository(source,tx,access,fingerprints,cipher);
            var requests=stage(AssistantTurnRepository.class,new JdbcAssistantTurnRepository(source,tx,access,fingerprints,cipher));
            var lifecycle=new JdbcAssistantSessionLifecycleAdapter(source,tx);
            var resultCipher=new AssistantResultCipher(protector);var repository=new JdbcKnowledgeAdapter(source,tx);
            var quota=new JdbcKnowledgeQuotaAdapter(source,tx,new KnowledgeQuotaProperties(true,60,0,0,0,0,0,0));
            var knowledge=new KnowledgeAnswerService(repository,new LocalKnowledgeSearchAdapter(repository,1.2,.75,64L*1024*1024),new RrfFusion(60),Optional.empty(),Optional.empty(),quota,access,new KnowledgeAnswerService.Settings(Mode.EXTRACTIVE,Duration.ofSeconds(4)),applicationClock);
            jpa.append(owner,com.aifriend.consent.domain.ConsentDecision.GRANTED);
            check(jpa.grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"REAL_JPA_GRANT_VISIBLE");
            var acoustic=(com.aifriend.contact.application.AcousticTemplatePort)java.lang.reflect.Proxy.newProxyInstance(
                com.aifriend.contact.application.AcousticTemplatePort.class.getClassLoader(),
                new Class[]{com.aifriend.contact.application.AcousticTemplatePort.class},(p,m,a)->{
                    if(m.getName().equals("isCompatible"))return Arrays.equals(a,new Object[]{"synthetic","v1","v1","v1"});
                    throw new AssertionError("NO_AUDIO_TEST");
                });
            var forbiddenGraph=stage(ContactGraphEvidencePort.class,new com.aifriend.contact.infrastructure.ContactGraphSourceAdapter(source,tx,access,acoustic,protector));
            var graphStore=new com.aifriend.knowledge.infrastructure.JdbcKnowledgeGraphAdapter(source,tx,access);
            var graphCleanup=new com.aifriend.knowledge.infrastructure.JdbcGraphOwnerCleanupAdapter(source,tx);
            var graphQuery=stage(GraphQueryPort.class,new ContactGraphQueryService(forbiddenGraph,graphStore,access));
            UUID contact=IsolatedContactSourceSmoke.binding(jdbc,owner);
            IsolatedContactSourceSmoke.alias(jdbc,protector,owner,contact,"合成图谱联系人");
            var expectedContacts=new HashMap<String,Set<String>>();
            for(int person=0;person<contactCount;person++) {
                UUID selected=person==0?contact:IsolatedContactSourceSmoke.binding(jdbc,owner);
                var aliases=new HashSet<String>();
                for(int alias=0;alias<aliasCount;alias++) {
                    String name=person==0&&alias==0?"合成图谱联系人":"合成亲友"+person+"称呼"+alias;
                    if(person!=0 || alias!=0) IsolatedContactSourceSmoke.alias(jdbc,protector,owner,selected,name);
                    aliases.add(name);
                }
                expectedContacts.put(selected.toString(),Set.copyOf(aliases));
            }
            boolean cold=args[3].equals("SYNTHETIC_GRAPH_HTTP_APP_CLOCK_COLD_SMOKE");
            System.out.println("PROJECTION_MODE="+(cold?"COLD_FIRST_QUERY":"PREWARMED"));
            if(!cold) graphQuery.query(IsolatedContactSourceSmoke.list(owner));
            var validator=new AssistantResultRevalidator(repository,forbiddenGraph,access,Optional::empty);
            var reader=new AssistantResultReader(sessions,requests,resultCipher,validator,access);
            try(var executor=new BoundedAssistantExecutor(new AssistantExecutionProperties(1,2),applicationClock);var web=new GenericWebApplicationContext()) {
                var service=new AssistantSessionService(requests,reader,resultCipher,validator,knowledge,graphQuery,Optional::empty,applicationClock,false,executor);
                var mapper=new ObjectMapper().findAndRegisterModules();String device=HexFormat.of().formatHex(random());
                var jwt=new JwtConfiguration();var encoder=jwt.jwtEncoder(keys);
                var properties=new IdentitySecurityProperties("isolated-http","isolated-http",Duration.ofMinutes(5),Duration.ofDays(1),"","","");
                web.setServletContext(new org.springframework.mock.web.MockServletContext());
                web.registerBean(ObjectMapper.class,()->mapper);
                web.registerBean(ActiveAccountStatusPort.class,()->new JdbcActiveAccountStatusAdapter(jdbc));
                web.registerBean(DeviceTrustService.class,()->new DeviceTrustService(new DeviceTrustProperties(true,List.of(device)),new DigestService()));
                web.registerBean(JwtDecoder.class,()->jwt.jwtDecoder(keys,properties));
                web.registerBean(AssistantSessionRepository.class,()->sessions);web.registerBean(AssistantSessionService.class,()->service);web.registerBean(AssistantResultReader.class,()->reader);
                web.registerBean(AssistantSessionController.class,()->new AssistantSessionController(web.getBeanProvider(AssistantSessionRepository.class),web.getBeanProvider(AssistantSessionService.class),web.getBeanProvider(AssistantResultReader.class),lifecycle,true,true));
                new AnnotatedBeanDefinitionReader(web).register(WebConfig.class);web.refresh();
                var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
                String auth="Bearer "+token(encoder,owner,device);
                String create=mapper.writeValueAsString(Map.of("purpose","CONTACT_GRAPH","clientRequestId",UUID.randomUUID().toString()));
                try {
                    body(mvc.perform(post("/assistant/sessions").header("Authorization","Bearer "+token(encoder,other,device)).contentType("application/json").content(create)).andReturn(),mapper,403);
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_session WHERE owner_user_id=UUID_TO_BIN(?)",Integer.class,other.toString())==0,"NO_CONSENT_NO_SESSION");
                    body(mvc.perform(post("/assistant/sessions").contentType("application/json").content(create)).andReturn(),mapper,401);
                    body(mvc.perform(post("/assistant/sessions").header("Authorization","Bearer invalid").contentType("application/json").content(create)).andReturn(),mapper,401);
                    body(mvc.perform(post("/assistant/sessions").header("Authorization","Bearer "+token(encoder,owner,"00".repeat(32))).contentType("application/json").content(create)).andReturn(),mapper,403);
                    var created=body(mvc.perform(post("/assistant/sessions").header("Authorization",auth).contentType("application/json").content(create)).andReturn(),mapper,200).path("data");
                    String id=created.path("id").asText();check(!id.isBlank(),"AUTHENTICATED_SESSION_CREATED");
                    check(!created.has("owner")&&!created.has("conversation"),"HTTP_METADATA_MINIMIZED");
                    String key=UUID.randomUUID().toString();String question=mapper.writeValueAsString(Map.of("expectedVersion",0,"requestKey",key,"locale","zh-CN","appVersionCode",1,"graphQuery",Map.of("queryType","LIST_CONTACTS")));
                    calls.clear();nanos.clear();stageCalls.clear();stageNanos.clear();long requestStart=System.nanoTime();
                    var answer=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,200).path("data");
                    System.out.println("ASK_ELAPSED_MS="+(System.nanoTime()-requestStart)/1_000_000);
                    stageCalls.keySet().stream().sorted().forEach(k->System.out.println("STAGE_TIMING_NESTED="+k+"; CALLS="+stageCalls.get(k).sum()+"; MS="+stageNanos.get(k).sum()/1_000_000));
                    calls.keySet().stream().sorted().forEach(k->System.out.println("SQL_TIMING="+k+"; CALLS="+calls.get(k).sum()+"; MS="+nanos.get(k).sum()/1_000_000));
                    for(int i=0;i<8 && answer.path("status").asText().equals("PROCESSING");i++) {
                        Thread.sleep(250);
                        answer=body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization",auth)).andReturn(),mapper,200).path("data");
                    }
                    System.out.println("ANSWER_STATUS="+answer.path("status").asText()+"; REASON="+answer.path("reasonCode").asText());
                    check(answer.path("status").asText().equals("ANSWERED"),"HTTP_REAL_GRAPH_ANSWER");
                    var actualContacts=new HashMap<String,Set<String>>();
                    for(var candidate:answer.path("candidates")) {
                        var aliases=new HashSet<String>();
                        for(var alias:candidate.path("aliases"))check(aliases.add(alias.asText()),"HTTP_NO_DUPLICATE_ALIAS");
                        check(actualContacts.put(candidate.path("contactId").asText(),Set.copyOf(aliases))==null,"HTTP_NO_DUPLICATE_CONTACT");
                    }
                    check(actualContacts.keySet().equals(expectedContacts.keySet()),"HTTP_EXACT_SYNTHETIC_CONTACT");
                    check(actualContacts.equals(expectedContacts),"HTTP_EXACT_ALIAS");
                    var replay=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,200).path("data");
                    check(replay.path("status").asText().equals("ANSWERED"),"HTTP_REPLAY_REVALIDATED");
                    check(replay.path("candidates").equals(answer.path("candidates")),"HTTP_REPLAY_EXACT_CANDIDATES");
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE session_id=UUID_TO_BIN(?)",Integer.class,id)==1,"HTTP_REPLAY_ONE_LEDGER");
                    body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization","Bearer "+token(encoder,other,device))).andReturn(),mapper,404);
                    // After the cold answer, prove source snapshots suspend an existing stale RR view.
                    var outerView=new org.springframework.transaction.support.TransactionTemplate(tx);
                    outerView.setReadOnly(true);
                    outerView.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    outerView.execute(status->{
                        String versionSql="SELECT version FROM contact_binding WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)";
                        long before=jdbc.queryForObject(versionSql,Long.class,owner.toString(),contact.toString());
                        try(var connection=pool.getConnection();var update=connection.prepareStatement(
                                "UPDATE contact_binding SET version=version+1 WHERE owner_user_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?) AND version=?")) {
                            check(connection.getAutoCommit(),"EXTERNAL_FIXTURE_AUTOCOMMIT");
                            update.setString(1,owner.toString());update.setString(2,contact.toString());update.setLong(3,before);
                            check(update.executeUpdate()==1,"SYNTHETIC_SOURCE_VERSION_ADVANCED");
                        } catch(java.sql.SQLException failure){throw new IllegalStateException("FIXTURE_VERSION_UPDATE_FAILED");}
                        var fresh=forbiddenGraph.snapshot(owner);
                        check(fresh.nodes().stream().anyMatch(node->node.sourceId().equals(contact)&&node.sourceVersion()==before+1),"FRESH_SOURCE_SUSPENDS_OUTER_RR");
                        check(jdbc.queryForObject(versionSql,Long.class,owner.toString(),contact.toString())==before,"OUTER_RR_VIEW_RESTORED");
                        return null;
                    });
                    jpa.append(owner,com.aifriend.consent.domain.ConsentDecision.REVOKED);
                    check(!jpa.grants.isGranted(owner,ConsentType.CONTACT_GRAPH),"REAL_JPA_REVOKE_VISIBLE");
                    var revoked=body(mvc.perform(get("/assistant/sessions/"+id+"/requests/"+key).header("Authorization",auth)).andReturn(),mapper,403);
                    check(!containsPrivateResult(revoked,expectedContacts),"HTTP_REVOKED_REPLAY_NO_PRIVATE_RESULT");
                    var revokedPost=body(mvc.perform(post("/assistant/sessions/"+id+"/questions").header("Authorization",auth).contentType("application/json").content(question)).andReturn(),mapper,403);
                    check(!containsPrivateResult(revokedPost,expectedContacts),"HTTP_REVOKED_POST_NO_PRIVATE_RESULT");
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE session_id=UUID_TO_BIN(?)",Integer.class,id)==1,"REVOKED_REPLAY_NO_NEW_REQUEST");
                } finally {
                    jdbc.query("SELECT r.state,r.admitted_version,r.result_version,TIMESTAMPDIFF(MICROSECOND,r.created_at,r.deadline) budget_us,s.version,s.accepted_questions FROM assistant_turn_request r JOIN assistant_session s ON s.id=r.session_id WHERE r.owner_user_id=UUID_TO_BIN(?)",
                        (org.springframework.jdbc.core.RowCallbackHandler)rs->System.out.println("HTTP_LEDGER_STATE="+rs.getString(1)+"; ADMITTED="+rs.getLong(2)+"; RESULT_VERSION="+rs.getString(3)+"; BUDGET_US="+rs.getLong(4)+"; SESSION_VERSION="+rs.getLong(5)+"; QUESTIONS="+rs.getLong(6)),owner.toString());
                    executor.close();lifecycle.revoke(owner,Purpose.CONTACT_GRAPH);
                    jpa.append(owner,com.aifriend.consent.domain.ConsentDecision.REVOKED);graphCleanup.purgeOwner(owner);
                    jdbc.update("UPDATE contact_alias SET status='DELETED',display_text_cipher=NULL,template_cipher=NULL,template_digest=NULL,deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE owner_user_id=UUID_TO_BIN(?)",owner.toString());
                    jdbc.update("UPDATE contact_binding SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE owner_user_id=UUID_TO_BIN(?)",owner.toString());
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM assistant_turn_request WHERE owner_user_id=UUID_TO_BIN(?) AND encrypted_result IS NOT NULL",Integer.class,owner.toString())==0,"HTTP_TEST_RESULTS_CLEARED");
                }
            }
            System.out.println("RESULT=PASS; JWT/filters/controller/service/SQL graph flow; actual JPA consent and synthetic compatibility; no remote HTTP deployment");
            }
        }
    }
}
