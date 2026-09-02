package com.aifriend.core.voice

import java.time.OffsetDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 服务端保存响应与 MySQL `DATETIME(3)` 回读之间的模板版本对账回归。 */
class LocalVoiceTemplateMetadataTest {

    @Test
    fun nanosecondsTruncatedByMysqlStillRepresentSameTemplateRevision() {
        val responseValue = "2026-09-01T12:34:56.123987654Z"
        val persistedValue = OffsetDateTime.parse("2026-09-01T12:34:56.123Z")

        assertTrue(sameServerUpdateInstant(responseValue, persistedValue))
    }

    @Test
    fun differentMillisecondsRemainDifferentTemplateRevisions() {
        val oldValue = "2026-09-01T12:34:56.123987654Z"
        val replacedValue = OffsetDateTime.parse("2026-09-01T12:34:56.124Z")

        assertFalse(sameServerUpdateInstant(oldValue, replacedValue))
    }
}
