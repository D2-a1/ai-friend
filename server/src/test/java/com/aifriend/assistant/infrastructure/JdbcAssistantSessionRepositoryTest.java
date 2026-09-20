package com.aifriend.assistant.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.assistant.domain.AssistantAnswer.Purpose;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.*;

/** 实际JDBC适配器/行映射/AES-GCM+模拟存储与回滚，不是MySQL事务验收。 */
class JdbcAssistantSessionRepositoryTest {
    private static final UUID OWNER=new UUID(0,1), OTHER=new UUID(0,2);
    private static final String KEY="creation-key-0001";
    private static final Instant START=Instant.parse("2026-09-10T01:00:00Z");
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final ConsentGrantQueryPort consents=mock(ConsentGrantQueryPort.class);
    private final List<String> statements=new ArrayList<>();
    private final Map<TransactionStatus,Map<String,Map<String,Object>>> before=new IdentityHashMap<>();
    private Map<String,Map<String,Object>> database=new HashMap<>();
    private final List<TransactionDefinition> definitions=new ArrayList<>();
    private Instant now=START;
    private boolean active=true, granted=true, failInsert, loseInsert, wrongOwner, commitLost, readFailure, corruptReadback;
    private int inserts;
    private AssistantContextCipher cipher;
    private AssistantRequestFingerprint fingerprints;
    private JdbcAssistantSessionRepository repository;
    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        byte[] key=new byte[32]; key[0]=1;
        var protector=new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(key,"HmacSHA256"),
                new SecretKeySpec(key,"AES"),new SecretKeySpec(key,"HmacSHA256")));
        cipher=new AssistantContextCipher(protector); fingerprints=new AssistantRequestFingerprint(protector);
        when(consents.isGrantedForPolicy(any(),any(),anyString())).thenAnswer(c->granted);
        when(manager.getTransaction(any())).thenAnswer(c->{
            definitions.add(c.getArgument(0)); var status=new SimpleTransactionStatus();
            before.put(status,copy(database)); return status;
        });
        doAnswer(c->{ database=before.get(c.getArgument(0)); return null; }).when(manager).rollback(any());
        doAnswer(c->{ if(commitLost) throw new TransactionSystemException("SIMULATED_COMMIT_UNKNOWN"); return null; })
                .when(manager).commit(any());
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)",Timestamp.class)).thenAnswer(c->Timestamp.from(now));
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(c->{
            String sql=c.getArgument(0); statements.add(sql);
            RowMapper<Object> mapper=c.getArgument(1); String owner=c.getArgument(2);
            if(sql.contains("FROM app_user")) {
                return List.of(mapper.mapRow(resultSet(Map.of("id",owner,"status",active?"ACTIVE":"DELETING")),0));
            }
            if(readFailure) throw new DataAccessResourceFailureException("SIMULATED_DATABASE_OFFLINE");
            Object selector=c.getArgument(3); var result=new ArrayList<Object>();
            for(var row:database.values()) {
                boolean match=sql.contains("create_key_hash=?")
                        ? Arrays.equals((byte[])row.get("create_key_hash"),(byte[])selector) : row.get("id").equals(selector);
                if(match && (owner.equals(row.get("owner_id")) || wrongOwner)) {
                    var selected=new HashMap<>(row); if(wrongOwner) selected.put("owner_id",OTHER.toString());
                    result.add(mapper.mapRow(resultSet(selected),result.size()));
                }
            }
            return result;
        });
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(c->{
            String sql=c.getArgument(0); statements.add(sql); inserts++;
            if(failInsert) throw new DataAccessResourceFailureException("SIMULATED_WRITE_FAILURE");
            Object[] args=Arrays.copyOfRange(c.getArguments(),1,c.getArguments().length);
            assertThat(args).hasSize(10); // SQL参数和入库字段一致，不含原文。
            var row=new HashMap<String,Object>();
            String[] columns={"id","owner_id","purpose","create_key_hash","create_request_digest","policy_version",
                    "encrypted_context","created_at","last_activity_at","expires_at"};
            for(int i=0;i<columns.length;i++) row.put(columns[i],args[i] instanceof byte[] b?b.clone():args[i]);
            row.put("state","OPEN"); row.put("version",0L); row.put("accepted_questions",0L);
            if(corruptReadback) row.put("version",1L);
            if(!loseInsert) database.put((String)row.get("id"),row);
            return 1;
        });
        repository=new JdbcAssistantSessionRepository(jdbc,manager,new KnowledgeAccessPolicy(consents),fingerprints,cipher);
    }
    private static Map<String,Map<String,Object>> copy(Map<String,Map<String,Object>> input) {
        var result=new HashMap<String,Map<String,Object>>(); input.forEach((k,v)->result.put(k,new HashMap<>(v))); return result;
    }
    private static ResultSet resultSet(Map<String,Object> row) throws Exception {
        var rs=mock(ResultSet.class); boolean[] missing={false};
        when(rs.getString(anyString())).thenAnswer(c->{Object v=row.get(c.getArgument(0)); return v==null?null:v.toString();});
        when(rs.getBytes(anyString())).thenAnswer(c->{byte[] v=(byte[])row.get(c.getArgument(0)); return v==null?null:v.clone();});
        when(rs.getTimestamp(anyString())).thenAnswer(c->row.get(c.getArgument(0)));
        when(rs.getLong(anyString())).thenAnswer(c->{Number v=(Number)row.get(c.getArgument(0)); missing[0]=v==null; return v==null?0L:v.longValue();});
        when(rs.wasNull()).thenAnswer(c->missing[0]); return rs;
    }
    @Test void createReadAndNewRepositoryReplaySamePersistentSessionWithoutExtendingExpiry() {
        var created=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        assertThat(repository.find(OWNER,created.id())).contains(created);
        now=START.plusSeconds(60);
        var restarted=new JdbcAssistantSessionRepository(jdbc,manager,new KnowledgeAccessPolicy(consents),fingerprints,cipher);
        assertThat(restarted.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).isEqualTo(created);
        assertThat(inserts).isEqualTo(1);
        assertThat(created.expiresAt()).isEqualTo(START.plusSeconds(300));
        assertThat(statements.get(0)).contains("app_user","FOR UPDATE");
        assertThat(definitions).allSatisfy(d->{assertThat(d.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(d.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED); assertThat(d.getTimeout()).isEqualTo(2);});
    }
    @Test void sameCreationKeyDifferentPurposeConflicts() {
        repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        assertThatThrownBy(()->repository.create(OWNER,Purpose.CONTACT_GRAPH,KEY)).hasMessage("IDEMPOTENCY_CONFLICT");
        assertThat(inserts).isEqualTo(1);
    }
    @Test void ownersMayReuseKeyButCannotReadOthersSession() {
        var first=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        assertThat(repository.find(OTHER,first.id())).isEmpty();
        var second=repository.create(OTHER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        assertThat(second.id()).isNotEqualTo(first.id()); assertThat(database).hasSize(2);
    }
    @Test void expiredCreationKeyDoesNotCreateNewSessionAndExactBoundaryFails() {
        var created=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        now=START.plusMillis(299999); assertThat(repository.find(OWNER,created.id())).contains(created);
        now=START.plusSeconds(300);
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("SESSION_EXPIRED");
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).hasMessage("SESSION_EXPIRED");
        assertThat(inserts).isEqualTo(1);
    }
    @Test void graphConsentIsRecheckedBeforeDecryptionWhileLocalPublicDoesNotRequireExternalConsent() {
        var graph=repository.create(OWNER,Purpose.CONTACT_GRAPH,KEY); granted=false;
        // 损坏密文也应先拒绝授权，而不是进入解密。
        database.get(graph.id().toString()).put("encrypted_context",new byte[29]);
        assertThatThrownBy(()->repository.find(OWNER,graph.id())).isInstanceOf(BusinessException.class);
        assertThat(repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,"public-key-00001")).isNotNull();
        assertThatThrownBy(()->repository.create(OWNER,Purpose.CONTACT_GRAPH,"graph-key-000002")).isInstanceOf(BusinessException.class);
    }
    @Test void inactiveOwnerCannotCreateOrReadAndNoSessionQueriesRun() {
        active=false;
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->repository.find(OWNER,OTHER)).isInstanceOf(BusinessException.class);
        assertThat(statements).allMatch(s->s.contains("FROM app_user")); assertThat(inserts).isZero();
    }
    @Test void insertedButUnreadableSessionRollsBackAndWriteFailureDoesNotLeakDetails() {
        loseInsert=true;
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
        assertThat(database).isEmpty(); verify(manager).rollback(any());
        loseInsert=false; failInsert=true;
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
        assertThat(database).isEmpty();
    }
    @Test void commitUnknownIsNotRetriedAndLaterOriginalKeyRecoversStoredSession() {
        commitLost=true;
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY)).hasMessage("COMMIT_UNCERTAIN");
        assertThat(database).hasSize(1); assertThat(inserts).isEqualTo(1);
        commitLost=false;
        assertThat(repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY).id().toString()).isEqualTo(database.keySet().iterator().next());
        assertThat(inserts).isEqualTo(1);
    }
    @Test void wrongOwnerCipherTamperingAndMissingVersionCannotRestoreContext() {
        var created=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        wrongOwner=true;
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("STORAGE_UNAVAILABLE"); wrongOwner=false;
        var row=database.get(created.id().toString()); byte[] cipherBytes=(byte[])row.get("encrypted_context");
        cipherBytes[cipherBytes.length-1]^=1;
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("DECRYPTION_FAILED");
        // 失败事务已用快照替换模拟数据库，后续故障必须注入当前行而不是旧Map引用。
        database.get(created.id().toString()).remove("version");
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("STORAGE_UNAVAILABLE");
    }
    @Test void databaseClockRollbackAndFailureCannotBeReportedAsEmpty() {
        var created=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY); now=START.minusMillis(1);
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("CLOCK_UNRELIABLE");
        readFailure=true;
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("STORAGE_UNAVAILABLE");
    }
    @Test void policyChangeAndTerminalStateNeverReturnOldContext() {
        var created=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY); var row=database.get(created.id().toString());
        row.put("policy_version","knowledge-model-v2");
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("AUTH_CHANGED");
        var current=database.get(created.id().toString());
        current.put("policy_version",KnowledgeAccessPolicy.MODEL_POLICY); current.put("state","CLOSED"); current.put("encrypted_context",null);
        assertThatThrownBy(()->repository.find(OWNER,created.id())).hasMessage("SESSION_CLOSED");
    }
    @Test void constructingProductionAdapterDoesNotOpenDataSource() {
        DataSource source=mock(DataSource.class);
        new JdbcAssistantSessionRepository(source,manager,new KnowledgeAccessPolicy(consents),fingerprints,cipher);
        verifyNoInteractions(source);
    }
    @Test void failedReadbackAfterActualSimulatedInsertRollsBackNewRowAndPreservesPriorSession() {
        var prior=repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,KEY);
        corruptReadback=true;
        assertThatThrownBy(()->repository.create(OWNER,Purpose.PUBLIC_KNOWLEDGE,"creation-key-0002")).hasMessage("DECRYPTION_FAILED");
        assertThat(database.keySet()).containsExactly(prior.id().toString());
        corruptReadback=false;
        assertThat(repository.find(OWNER,prior.id())).contains(prior);
    }
}
