package com.aifriend.contact.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.AcousticTemplatePort;
import com.aifriend.knowledge.application.GraphSourceException;
import com.aifriend.knowledge.domain.GraphNode;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.security.SensitiveDataProtector;
import com.aifriend.shared.security.SecurityKeyMaterial;

/** 实际适配器+模拟JDBC结果集；不证明MySQL语法、隔离或并发提交已通过。 */
class ContactGraphSourceAdapterTest {
    private final UUID owner = id(1);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private final AcousticTemplatePort compatibility = mock(AcousticTemplatePort.class);
    private final SensitiveDataProtector protector = mock(SensitiveDataProtector.class);
    private final List<Map<String, Object>> users = new ArrayList<>();
    private final List<Map<String, Object>> contacts = new ArrayList<>();
    private final List<Map<String, Object>> aliases = new ArrayList<>();
    private final List<String> sqlSeen = new ArrayList<>();
    private final List<List<Object>> arguments = new ArrayList<>();
    private final List<byte[]> ciphers = new ArrayList<>();
    private final List<byte[]> plains = new ArrayList<>();
    private boolean granted = true;
    private boolean failDatabase;
    private boolean noDisplay;
    private boolean duplicateDisplay;
    private boolean immutableResults;
    private Runnable afterDecrypt = () -> {};
    private java.util.function.Consumer<List<Map<String,Object>>> adjustDisplayRows = rows -> {};
    private byte[] nextPlain = "老二".getBytes(StandardCharsets.UTF_8);
    private byte[] actualCipher;
    private ContactGraphSourceAdapter adapter;

    @BeforeEach void setup() throws Exception {
        users.add(new HashMap<>(Map.of("id", owner.toString(), "version", 1L, "status", "ACTIVE")));
        contacts.add(contact(2));
        aliases.add(alias(3, 2));
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(consents.isGrantedForPolicy(any(), eq(ConsentType.CONTACT_GRAPH), eq("contact-graph-v1")))
                .thenAnswer(call -> granted);
        when(compatibility.isCompatible(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(protector.decryptBytes(any())).thenAnswer(call -> {
            byte[] plain = nextPlain.clone();
            plains.add(plain);
            afterDecrypt.run();
            return plain;
        });
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            sqlSeen.add(sql);
            var parameters = new ArrayList<Object>();
            for (int i = 2; i < call.getArguments().length; i++) { parameters.add(call.getArgument(i)); }
            arguments.add(parameters);
            if (failDatabase) { throw new DataAccessResourceFailureException("DO_NOT_LEAK"); }
            List<Map<String, Object>> rows;
            if (sql.contains("source_kind")) {
                rows = new ArrayList<>();
                for(var kind:List.of("USER","CONTACT","ALIAS")) {
                    for(var original:switch(kind){case "USER"->users;case "CONTACT"->contacts;default->aliases;}) {
                        var combined=new HashMap<>(original); combined.put("source_kind",kind);
                        if(kind.equals("USER")){combined.put("owner_id",original.get("id"));combined.put("valid",true);}
                        rows.add(combined);
                    }
                }
            }
            else if (sql.contains("FROM app_user")) { rows = users; }
            else if (sql.contains("FROM contact_binding")) { rows = contacts; }
            else if (sql.contains("FROM contact_alias a")) { rows = aliases; }
            else if (sql.contains("SELECT display_text_cipher")) {
                if (noDisplay) { rows = List.of(); }
                else {
                    rows = new ArrayList<>();
                    for(int p=1;p<parameters.size();p+=3) {
                        byte[] cipher = actualCipher == null ? new byte[48] : actualCipher.clone();
                        if (actualCipher == null) { java.util.Arrays.fill(cipher, (byte) 7); }
                        ciphers.add(cipher);
                        rows.add(Map.of("display_text_cipher",cipher,"binding_id",parameters.get(p),"id",parameters.get(p+1),"version",parameters.get(p+2)));
                    }
                    if (duplicateDisplay) { rows.add(rows.get(0)); }
                    adjustDisplayRows.accept(rows);
                }
            } else { throw new AssertionError("Unexpected SQL"); }
            RowMapper<?> mapper = call.getArgument(1);
            var mapped = new ArrayList<>();
            for (var row : rows) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(anyString())).thenAnswer(get -> (String) row.get(get.getArgument(0)));
                when(rs.getLong(anyString())).thenAnswer(get -> row.getOrDefault(get.getArgument(0), 0L));
                when(rs.wasNull()).thenReturn(!row.containsKey("version"));
                when(rs.getBoolean(anyString())).thenAnswer(get -> row.getOrDefault(get.getArgument(0), false));
                when(rs.getBytes(anyString())).thenAnswer(get -> row.get(get.getArgument(0)));
                mapped.add(mapper.mapRow(rs, mapped.size()));
            }
            return immutableResults ? List.copyOf(mapped) : mapped;
        });
        adapter = new ContactGraphSourceAdapter(jdbc, transactions, new KnowledgeAccessPolicy(consents),
                compatibility, protector);
    }

    @Test void snapshotContainsOnlyOwnerScopedReferencesAndNeverDecrypts() {
        var graph = adapter.snapshot(owner);
        assertThat(graph.nodes()).extracting(GraphNode::type)
                .containsExactly(GraphNode.Type.USER, GraphNode.Type.CONTACT, GraphNode.Type.ALIAS);
        assertThat(graph.nodes()).allSatisfy(node -> assertThat(node.ownerUserId()).isEqualTo(owner));
        assertThat(graph.edges()).hasSize(2);
        verifyNoInteractions(protector);
        assertThat(sqlSeen).hasSize(1).noneMatch(sql -> sql.contains("wechat_locator")
                || sql.contains("phonetic_hint") || sql.contains("SELECT *")
                || sql.contains("SELECT display_text_cipher"));
        assertThat(sqlSeen.get(0)).contains("UNION ALL", "LIMIT 2)", "LIMIT 200)", "OCTET_LENGTH(a.template_cipher)", "a.owner_user_id = UUID_TO_BIN(?)",
                "b.owner_user_id = a.owner_user_id", "a.status = 'ACTIVE'", "LIMIT 201");
        assertThat(arguments).allSatisfy(params -> assertThat(params).containsExactly(owner.toString(),owner.toString(),owner.toString()));
    }

    @Test void snapshotRevokedDuringMetadataValidationIsNotReturned() {
        when(compatibility.isCompatible(anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(call -> { granted=false; return true; });
        assertThatThrownBy(() -> adapter.snapshot(owner)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(protector);
        assertThat(sqlSeen).hasSize(1);
    }

    @Test void snapshotWithoutConsentNeverReadsMetadata() {
        granted=false;
        assertThatThrownBy(() -> adapter.snapshot(owner)).isInstanceOf(BusinessException.class);
        assertThat(sqlSeen).isEmpty();
        verifyNoInteractions(protector);
    }

    @Test void displayUsesFreshTransactionsAndClearsBuffers() {
        var graph = adapter.snapshot(owner);
        var result = adapter.displayCurrent(owner, graph.sourceDigest(), List.of(id(2)));
        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.aliases()).containsExactly("老二");
            assertThat(value.contactId()).isEqualTo(id(2));
            assertThat(value.toString()).doesNotContain("老二", id(2).toString());
        });
        assertThat(plains).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        assertThat(ciphers).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        assertThat(arguments.get(2)).containsExactly(owner.toString(), id(2).toString(), id(3).toString(), 1L);
        var definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions, times(3)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues().get(1)).satisfies(def -> {
            assertThat(def.isReadOnly()).isTrue();
            assertThat(def.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(def.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(def.getTimeout()).isEqualTo(2);
        });
        assertThat(List.of(definitions.getAllValues().get(0),definitions.getAllValues().get(2)))
                .allSatisfy(def -> assertThat(def.getPropagationBehavior())
                        .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED));
        verify(consents, times(5)).isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH, "contact-graph-v1");
    }

    @Test void displayEvidenceExposesBothFreshFactsWithoutTwoRedundantSnapshots() {
        var digest=adapter.snapshot(owner).sourceDigest();
        sqlSeen.clear();
        clearInvocations(transactions,consents);
        var evidence=adapter.displayEvidence(owner,digest,List.of(id(2)));
        assertThat(evidence.before().sourceDigest()).isEqualTo(digest);
        assertThat(evidence.after().sourceDigest()).isEqualTo(digest);
        assertThat(evidence.before().generation()).isNotEqualTo(evidence.after().generation());
        assertThat(evidence.before().nodes()).extracting(GraphNode::sourceId).containsExactly(owner,id(2),id(3));
        assertThat(evidence.after().nodes()).extracting(GraphNode::sourceId).containsExactly(owner,id(2),id(3));
        assertThat(evidence.displays()).singleElement().satisfies(value->assertThat(value.aliases()).containsExactly("老二"));
        assertThat(evidence.toString()).isEqualTo("GraphDisplayEvidence[redacted]");
        assertThat(sqlSeen).hasSize(3);
        verify(transactions,times(2)).getTransaction(any());
        verify(consents,times(3)).isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1");
        assertThat(plains).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
    }

    @Test void evidenceDoesNotEscapeWhenConsentChangesDuringDecryption() {
        var digest=adapter.snapshot(owner).sourceDigest();
        afterDecrypt=()->granted=false;
        assertThatThrownBy(()->adapter.displayEvidence(owner,digest,List.of(id(2)))).isInstanceOf(BusinessException.class);
        assertThat(plains).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
        assertThat(ciphers).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
    }

    @Test void evidenceDoesNotEscapeWhenSourceChangesDuringDecryption() {
        var digest=adapter.snapshot(owner).sourceDigest();
        afterDecrypt=()->contacts.get(0).put("version",2L);
        failure("SOURCE_CHANGED",()->adapter.displayEvidence(owner,digest,List.of(id(2))));
    }

    @Test void maximumBatchReadsHundredAliasesWithOneBoundedParameterizedQuery() {
        contacts.clear();aliases.clear();
        var ids=new ArrayList<UUID>();
        for(int p=0;p<20;p++) {
            contacts.add(contact(100+p));ids.add(id(100+p));
            for(int a=0;a<5;a++)aliases.add(alias(1000+p*5+a,100+p));
        }
        var digest=adapter.snapshot(owner).sourceDigest();
        sqlSeen.clear();arguments.clear();
        adjustDisplayRows=Collections::reverse;
        var result=adapter.displayEvidence(owner,digest,ids);
        assertThat(result.displays()).hasSize(20).allSatisfy(display->assertThat(display.aliases()).hasSize(5));
        assertThat(sqlSeen).hasSize(3).filteredOn(sql->sql.contains("SELECT display_text_cipher"))
                .singleElement().satisfies(sql->assertThat(sql).contains("LIMIT 101","owner_user_id = UUID_TO_BIN(?)").doesNotContain(owner.toString()));
        assertThat(arguments.get(1)).hasSize(301);
        assertThat(ciphers).hasSize(100).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
        assertThat(plains).hasSize(100).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
    }

    @Test void batchRejectsWrongBindingVersionAndUnexpectedAliasBeforeDecrypting() {
        var digest=adapter.snapshot(owner).sourceDigest();
        for(var change:List.of(Map.<String,Object>of("binding_id",id(99).toString()),
                Map.<String,Object>of("id",id(99).toString()),Map.<String,Object>of("version",2L))) {
            adjustDisplayRows=rows->{var altered=new HashMap<>(rows.get(0));altered.putAll(change);rows.set(0,altered);};
            failure("SOURCE_CHANGED",()->adapter.displayEvidence(owner,digest,List.of(id(2))));
        }
        verifyNoInteractions(protector);
    }

    @Test void batchMissingOneAliasRejectsWholeResultAndClearsAlreadyDecodedBuffers() {
        aliases.add(alias(4,2));
        var digest=adapter.snapshot(owner).sourceDigest();
        adjustDisplayRows=rows->rows.remove(rows.size()-1);
        failure("SOURCE_CHANGED",()->adapter.displayEvidence(owner,digest,List.of(id(2))));
        assertThat(plains).hasSize(1).allSatisfy(bytes->assertThat(bytes).containsOnly((byte)0));
    }

    @Test void digestIsStableAcrossRowOrderButBindsAliasParentAndVersions() {
        contacts.add(contact(4)); aliases.add(alias(5, 4));
        var before = adapter.snapshot(owner);
        Collections.reverse(contacts); Collections.reverse(aliases);
        var reordered = adapter.snapshot(owner);
        assertThat(reordered.sourceDigest()).isEqualTo(before.sourceDigest());
        assertThat(reordered.generation()).isNotEqualTo(before.generation());
        aliases.get(0).put("binding_id", id(2).toString());
        assertThat(adapter.snapshot(owner).sourceDigest()).isNotEqualTo(before.sourceDigest());
        aliases.get(0).put("binding_id", id(4).toString());
        aliases.get(0).put("version", 2L);
        assertThat(adapter.snapshot(owner).sourceDigest()).isNotEqualTo(before.sourceDigest());
    }

    @Test void deletedAndIncompatibleAliasesAreExcludedIncludingRuntimeIncompatibility() {
        var deleted = alias(4, 2); deleted.put("status", "DELETED"); aliases.add(deleted);
        var incompatible = alias(5, 2); incompatible.put("status", "INCOMPATIBLE"); aliases.add(incompatible);
        var tombstone = alias(6, 2); tombstone.put("valid", false); aliases.add(tombstone);
        var runtime = alias(7, 2); runtime.put("template_model_version", "retired"); aliases.add(runtime);
        when(compatibility.isCompatible("cn", "p1", "retired", "t1")).thenReturn(false);
        assertThat(adapter.snapshot(owner).nodes()).filteredOn(node -> node.type() == GraphNode.Type.ALIAS)
                .extracting(GraphNode::sourceId).containsExactly(id(3));
        verifyNoInteractions(protector);
    }

    @Test void emptyOwnerGraphIsAValidRootNotStorageFailure() {
        contacts.clear(); aliases.clear();
        var graph = adapter.snapshot(owner);
        assertThat(graph.nodes()).hasSize(1);
        assertThat(adapter.displayCurrent(owner, graph.sourceDigest(), List.of())).isEmpty();
        verifyNoInteractions(protector);
    }

    @Test void differentOwnerCannotReuseSameSourceIdentifiers() {
        var first = adapter.snapshot(owner);
        users.get(0).put("id", id(9).toString());
        contacts.get(0).put("owner_id", id(9).toString());
        aliases.get(0).put("owner_id", id(9).toString());
        var second = adapter.snapshot(id(9));
        assertThat(second.sourceDigest()).isNotEqualTo(first.sourceDigest());
        assertThat(second.nodes()).extracting(GraphNode::id)
                .doesNotContainAnyElementsOf(first.nodes().stream().map(GraphNode::id).toList());
    }

    @Test void nullOrInactiveOwnerCannotReadContacts() {
        assertThatThrownBy(() -> adapter.snapshot(null)).isInstanceOf(BusinessException.class);
        assertThat(sqlSeen).isEmpty();
        users.get(0).put("status", "DELETING");
        assertThatThrownBy(() -> adapter.snapshot(owner)).isInstanceOf(BusinessException.class);
        assertThat(sqlSeen).hasSize(1);
    }

    @Test void independentConsentIsRequiredBeforeAnySourceRead() {
        granted = false;
        assertThatThrownBy(() -> adapter.snapshot(owner)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc, protector, compatibility);
    }

    @Test void databaseFailureIsNotEmptyGraphAndDoesNotExposeDetails() {
        failDatabase = true;
        assertThatThrownBy(() -> adapter.snapshot(owner)).isInstanceOf(GraphSourceException.class)
                .hasMessage("STORAGE_UNAVAILABLE").hasNoCause();
    }

    @Test void mixedOwnerBindingIsRejectedBeforeAliasesOrDecryption() {
        contacts.get(0).put("owner_id", id(9).toString());
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        assertThat(sqlSeen).hasSize(1); verifyNoInteractions(protector);
    }

    @Test void mixedOwnerAliasAndOrphanAliasAreRejected() {
        aliases.get(0).put("owner_id", id(9).toString());
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        aliases.get(0).put("owner_id", owner.toString());
        aliases.get(0).put("binding_id", id(9).toString());
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
    }

    @Test void invalidActiveBindingAndMissingAliasMaterialFailClosed() {
        contacts.get(0).put("valid", false);
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        contacts.get(0).put("valid", true);
        aliases.get(0).put("material_present", false);
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
    }

    @Test void duplicateSourceAndNegativeOrNullVersionAreRejected() {
        aliases.add(alias(3, 2));
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        aliases.remove(1); aliases.get(0).put("version", -1L);
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        aliases.get(0).remove("version");
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
    }

    @Test void graphLimitRejectsRatherThanTruncating() {
        aliases.clear();
        for (int i = 0; i < 199; i++) { aliases.add(alias(100 + i, 2)); }
        failure("GRAPH_LIMIT", () -> adapter.snapshot(owner));
        verifyNoInteractions(protector);
    }

    @Test void aliasRowLimitAppliesBeforeCompatibilityFiltering() {
        aliases.clear();
        for (int i = 0; i < 201; i++) { aliases.add(alias(100 + i, 2)); }
        failure("GRAPH_LIMIT", () -> adapter.snapshot(owner));
        verifyNoInteractions(compatibility, protector);
    }

    @Test void changedSourceBeforeDisplayDoesNotDecrypt() {
        var digest = adapter.snapshot(owner).sourceDigest();
        contacts.get(0).put("version", 2L);
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        verifyNoInteractions(protector);
    }

    @Test void unbindBetweenSnapshotAndDisplayCannotReturnStaleContact() {
        var digest = adapter.snapshot(owner).sourceDigest(); contacts.clear(); aliases.clear();
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        verifyNoInteractions(protector);
    }

    @Test void allCandidateIdsAreCheckedBeforeDecryptingAny() {
        var digest = adapter.snapshot(owner).sourceDigest();
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2), id(9))));
        verifyNoInteractions(protector);
    }

    @Test void invalidDisplayRequestAndAliasOutputLimitDoNotReturnPartialResults() {
        for (int i = 0; i < 5; i++) { aliases.add(alias(10 + i, 2)); }
        var digest = adapter.snapshot(owner).sourceDigest();
        failure("GRAPH_LIMIT", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        assertThatThrownBy(() -> adapter.displayCurrent(owner, digest, List.of(id(2), id(2))))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(protector);
    }

    @Test void revokedDuringDecryptionDropsResultOnFreshFinalCheck() {
        var digest = adapter.snapshot(owner).sourceDigest(); afterDecrypt = () -> granted = false;
        assertThatThrownBy(() -> adapter.displayCurrent(owner, digest, List.of(id(2))))
                .isInstanceOf(BusinessException.class);
        assertThat(plains).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        verify(transactions, times(3)).getTransaction(any());
    }

    @Test void sourceChangedDuringDecryptionDropsResultOnFreshFinalCheck() {
        var digest = adapter.snapshot(owner).sourceDigest();
        afterDecrypt = () -> aliases.get(0).put("version", 2L);
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
    }

    @Test void decryptionFailureAndMalformedUtf8AreUnavailableAndBuffersCleared() {
        var digest = adapter.snapshot(owner).sourceDigest();
        doThrow(new IllegalStateException("DO_NOT_LEAK")).when(protector).decryptBytes(any());
        failure("DECRYPTION_FAILED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        assertThat(ciphers).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        byte[] malformed = {(byte) 0xc3, 0x28};
        doReturn(malformed).when(protector).decryptBytes(any());
        failure("DECRYPTION_FAILED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        assertThat(malformed).containsOnly((byte) 0);
    }

    @Test void missingOrDuplicateDisplayRowsCannotBecomeSuccess() {
        var digest = adapter.snapshot(owner).sourceDigest(); noDisplay = true;
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        noDisplay = false; duplicateDisplay = true;
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
    }

    @Test void actualAesGcmDisplayRoundTripsWithoutFetchingOtherSensitiveFields() {
        var keys = mock(SecurityKeyMaterial.class);
        when(keys.dataEncryptionKey()).thenReturn(new SecretKeySpec(new byte[32], "AES"));
        var actual = new SensitiveDataProtector(keys);
        actualCipher = actual.encrypt("老三");
        adapter = new ContactGraphSourceAdapter(jdbc, transactions, new KnowledgeAccessPolicy(consents),
                compatibility, actual);
        var digest = adapter.snapshot(owner).sourceDigest();
        assertThat(adapter.displayCurrent(owner, digest, List.of(id(2))).get(0).aliases()).containsExactly("老三");
        assertThat(ciphers).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
        verifyNoInteractions(protector);
    }

    @Test void actualAesGcmTamperIsFixedFailureNotAnEmptyAlias() {
        var keys = mock(SecurityKeyMaterial.class);
        when(keys.dataEncryptionKey()).thenReturn(new SecretKeySpec(new byte[32], "AES"));
        var actual = new SensitiveDataProtector(keys);
        actualCipher = actual.encrypt("老三"); actualCipher[actualCipher.length - 1] ^= 1;
        adapter = new ContactGraphSourceAdapter(jdbc, transactions, new KnowledgeAccessPolicy(consents),
                compatibility, actual);
        var digest = adapter.snapshot(owner).sourceDigest();
        failure("DECRYPTION_FAILED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        assertThat(ciphers).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @Test void contactReadLimitAndUnknownAliasStatusFailClosed() {
        aliases.get(0).put("status", "FUTURE_UNKNOWN");
        failure("SOURCE_INVALID", () -> adapter.snapshot(owner));
        for (int i = 0; i < 199; i++) { contacts.add(contact(100 + i)); }
        failure("GRAPH_LIMIT", () -> adapter.snapshot(owner));
        verifyNoInteractions(protector);
    }

    @Test void runtimeCompatibilityChangeAfterDecryptionInvalidatesResult() {
        var digest = adapter.snapshot(owner).sourceDigest();
        afterDecrypt = () -> when(compatibility.isCompatible(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);
        failure("SOURCE_CHANGED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
    }

    @Test void plaintextControlOrSizeViolationIsUnavailableAndCleared() {
        var digest = adapter.snapshot(owner).sourceDigest();
        nextPlain = new byte[] {0};
        failure("DECRYPTION_FAILED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        nextPlain = "a".repeat(401).getBytes(StandardCharsets.UTF_8);
        failure("DECRYPTION_FAILED", () -> adapter.displayCurrent(owner, digest, List.of(id(2))));
        assertThat(plains).allSatisfy(bytes -> assertThat(bytes).containsOnly((byte) 0));
    }

    @Test void immutableJdbcResultsIncludingEmptyUnboundOwnerAreSupported() {
        immutableResults = true;
        assertThat(adapter.snapshot(owner).nodes()).hasSize(3);
        contacts.clear(); aliases.clear();
        assertThat(adapter.snapshot(owner).nodes()).hasSize(1);
    }

    private Map<String, Object> contact(long value) {
        return new HashMap<>(Map.of("id", id(value).toString(), "owner_id", owner.toString(),
                "version", 1L, "status", "ACTIVE", "valid", true));
    }

    private Map<String, Object> alias(long value, long binding) {
        var row = new HashMap<String, Object>();
        row.putAll(Map.of("id", id(value).toString(), "owner_id", owner.toString(),
                "binding_id", id(binding).toString(), "version", 1L, "status", "ACTIVE", "valid", true,
                "material_present", true, "dialect_code", "cn", "dialect_package_version", "p1"));
        row.put("template_model_version", "m1"); row.put("threshold_version", "t1");
        return row;
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private static void failure(String reason, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(GraphSourceException.class).hasMessage(reason).hasNoCause();
    }
}
