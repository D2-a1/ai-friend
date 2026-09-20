package com.aifriend.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class KnowledgeAccessPolicyTest {
    private final UUID owner = UUID.randomUUID();
    private final ConsentGrantQueryPort consents = mock(ConsentGrantQueryPort.class);
    private final KnowledgeAccessPolicy policy = new KnowledgeAccessPolicy(consents);

    @Test
    void noDecisionDeniesBothPurposes() {
        assertDenied(() -> policy.requireExternalModelConsent(owner), ErrorCode.CONSENT_REQUIRED);
        assertDenied(() -> policy.requireGraphConsent(owner), ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    void graphConsentDoesNotGrantExternalModelProcessing() {
        when(consents.isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH,
                KnowledgeAccessPolicy.GRAPH_POLICY)).thenReturn(true);
        policy.requireGraphConsent(owner);
        assertDenied(() -> policy.requireExternalModelConsent(owner), ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    void modelConsentDoesNotGrantGraphAccess() {
        when(consents.isGrantedForPolicy(owner, ConsentType.KNOWLEDGE_MODEL,
                KnowledgeAccessPolicy.MODEL_POLICY)).thenReturn(true);
        policy.requireExternalModelConsent(owner);
        assertDenied(() -> policy.requireGraphConsent(owner), ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    void rechecksCurrentOwnerAndPolicyOnEveryRead() {
        when(consents.isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH,
                KnowledgeAccessPolicy.GRAPH_POLICY)).thenReturn(true, false);
        policy.requireGraphConsent(owner);
        assertDenied(() -> policy.requireGraphConsent(owner), ErrorCode.CONSENT_REQUIRED);
        assertDenied(() -> policy.requireGraphConsent(UUID.randomUUID()), ErrorCode.CONSENT_REQUIRED);
    }

    @Test
    void missingIdentityDoesNotReadStorage() {
        assertDenied(() -> policy.requireExternalModelConsent(null), ErrorCode.AUTH_REQUIRED);
        assertDenied(() -> policy.requireGraphConsent(null), ErrorCode.AUTH_REQUIRED);
        verifyNoInteractions(consents);
    }

    @Test
    void storageFailureDoesNotFallBackToOtherPurposes() {
        when(consents.isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH,
                KnowledgeAccessPolicy.GRAPH_POLICY)).thenThrow(new IllegalStateException("storage"));
        assertThatThrownBy(() -> policy.requireGraphConsent(owner)).isInstanceOf(IllegalStateException.class);
        verify(consents).isGrantedForPolicy(owner, ConsentType.CONTACT_GRAPH, KnowledgeAccessPolicy.GRAPH_POLICY);
        verifyNoMoreInteractions(consents);
    }

    private static void assertDenied(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.errorCode()).isEqualTo(code));
    }
}
