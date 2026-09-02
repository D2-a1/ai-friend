package com.aifriend.invitation.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aifriend.invitation.application.WechatInvitationIdentity;
import com.aifriend.shared.error.UpstreamFailureException;
import com.aifriend.shared.security.DigestService;

class LocalWechatInvitationIdentityAdapterTest {

    @Test
    void shouldAcceptIsolatedLocalCodeWithoutCallingWechat() {
        LocalWechatInvitationIdentityAdapter adapter =
                new LocalWechatInvitationIdentityAdapter(new DigestService());

        WechatInvitationIdentity identity =
                adapter.exchangeCode("local_relative.12345678");

        assertEquals("relative", identity.subject());
    }

    @Test
    void shouldRejectMalformedAndReplayedLocalCodes() {
        LocalWechatInvitationIdentityAdapter adapter =
                new LocalWechatInvitationIdentityAdapter(new DigestService());
        String code = "local_relative.12345678";
        adapter.exchangeCode(code);

        assertThrows(UpstreamFailureException.class, () -> adapter.exchangeCode(code));
        assertThrows(UpstreamFailureException.class,
                () -> adapter.exchangeCode("production-code"));
    }
}
