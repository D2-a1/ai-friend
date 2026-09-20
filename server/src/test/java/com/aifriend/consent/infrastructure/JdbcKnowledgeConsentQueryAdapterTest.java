package com.aifriend.consent.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.sql.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.aifriend.consent.domain.ConsentType;

/** 模拟 JDBC 核对精确用途、最新状态、参数绑定和原事务参与，不连接外部系统。 */
class JdbcKnowledgeConsentQueryAdapterTest {
    final DataSource source=mock(DataSource.class);
    final Connection connection=mock(Connection.class);
    final PreparedStatement statement=mock(PreparedStatement.class);
    final ResultSet rows=mock(ResultSet.class);
    final UUID owner=new UUID(0,51);
    final JdbcKnowledgeConsentQueryAdapter adapter=new JdbcKnowledgeConsentQueryAdapter(source);
    @BeforeEach void setup() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);
        when(rows.getString("decision")).thenReturn("GRANTED");
        when(rows.getString("policy_version")).thenReturn("contact-graph-v1");
    }
    @Test void singleParameterizedReadDoesNotStartOrCommitItsOwnTransaction() throws Exception {
        assertThat(adapter.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).isTrue();
        verify(connection).prepareStatement(contains("ORDER BY sequence_no DESC LIMIT 1"));
        verify(statement).setString(1,owner.toString());
        verify(statement).setString(2,"CONTACT_GRAPH");
        verify(statement).setQueryTimeout(2);
        verify(connection,never()).setAutoCommit(anyBoolean());
        verify(connection,never()).commit();
        verify(connection).close();
    }
    @Test void eachCallReadsAgainAndLaterRevocationWins() throws Exception {
        when(rows.next()).thenReturn(true,false,true,false);
        when(rows.getString("decision")).thenReturn("GRANTED","REVOKED");
        assertThat(adapter.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).isTrue();
        assertThat(adapter.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).isFalse();
        verify(statement,times(2)).executeQuery();
    }
    @Test void absentOrWrongPolicyOrCorruptDecisionNeverGrants() throws Exception {
        assertThat(adapter.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"CONTACT-GRAPH-V1")).isFalse();
        when(rows.next()).thenReturn(false);
        assertThat(adapter.isGranted(owner,ConsentType.CONTACT_GRAPH)).isFalse();
        when(rows.next()).thenReturn(true,false);
        when(rows.getString("decision")).thenReturn(null);
        assertThat(adapter.isGranted(owner,ConsentType.CONTACT_GRAPH)).isFalse();
    }
    @Test void externalModelUsesItsOwnTypeAndPolicy() throws Exception {
        when(rows.getString("policy_version")).thenReturn("knowledge-model-v1");
        assertThat(adapter.isGrantedForPolicy(owner,ConsentType.KNOWLEDGE_MODEL,"knowledge-model-v1")).isTrue();
        verify(statement).setString(2,"KNOWLEDGE_MODEL");
    }
    @Test void otherPurposesCannotUseThisAdapter() {
        assertThatThrownBy(()->adapter.isGranted(owner,ConsentType.BASIC_IDENTITY)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source);
    }
    @Test void databaseFailurePropagatesInsteadOfGranting() throws Exception {
        when(statement.executeQuery()).thenThrow(new SQLTransientConnectionException("synthetic"));
        assertThatThrownBy(()->adapter.isGranted(owner,ConsentType.CONTACT_GRAPH)).isInstanceOf(DataAccessException.class);
    }
    @Test void ambientTransactionKeepsSameConnectionAndOwnsCommit() throws Exception {
        when(connection.getAutoCommit()).thenReturn(true);
        new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            assertThat(adapter.isGrantedForPolicy(owner,ConsentType.CONTACT_GRAPH,"contact-graph-v1")).isTrue();
            try {verify(connection,never()).commit();verify(connection,never()).close();}
            catch(SQLException failure){throw new AssertionError(failure);}
            return null;
        });
        verify(source).getConnection();
        verify(connection).commit();
    }
}
