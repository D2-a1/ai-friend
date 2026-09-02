package com.aifriend.contact.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aifriend.shared.error.BusinessException;
import com.aifriend.shared.error.ErrorCode;

class WechatLocatorPolicyTest {

    @Test
    void shouldNormalizeSupportedInvitationWechatId() {
        assertEquals("relative_123",
                WechatLocatorPolicy.normalizeInvitationWechatId("  relative_123  "));
    }

    @Test
    void shouldRejectNicknamePhoneAndUnsupportedCharacters() {
        assertValidationFailure("女儿");
        assertValidationFailure("13800138000");
        assertValidationFailure("relative.123");
    }

    private void assertValidationFailure(String value) {
        BusinessException failure = assertThrows(
                BusinessException.class,
                () -> WechatLocatorPolicy.normalizeInvitationWechatId(value));
        assertEquals(ErrorCode.VALIDATION_FAILED, failure.errorCode());
    }
}
