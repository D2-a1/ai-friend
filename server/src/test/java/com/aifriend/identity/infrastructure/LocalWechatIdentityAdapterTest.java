package com.aifriend.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aifriend.identity.domain.WechatIdentity;
import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class LocalWechatIdentityAdapterTest {

    @Test
    void shouldMapDifferentOneTimeCodesToSameLocalSubject() {
        LocalWechatIdentityAdapter adapter = new LocalWechatIdentityAdapter();

        WechatIdentity first = adapter.exchange("local_owner.12345678");
        WechatIdentity second = adapter.exchange("local_owner.abcdefgh");

        assertEquals(first.subject(), second.subject());
    }

    @Test
    void shouldRejectReusedCode() {
        LocalWechatIdentityAdapter adapter = new LocalWechatIdentityAdapter();
        String code = "local_owner.12345678";
        adapter.exchange(code);

        BusinessException exception = assertThrows(BusinessException.class, () -> adapter.exchange(code));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.errorCode());
    }
}
