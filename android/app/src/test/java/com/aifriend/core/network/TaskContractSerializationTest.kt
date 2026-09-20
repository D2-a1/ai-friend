package com.aifriend.core.network

import com.aifriend.contract.model.AllowedAction
import com.aifriend.contract.model.ChannelResult
import com.aifriend.contract.model.ChannelResultRequest
import com.aifriend.contract.model.ConfirmationAction
import com.aifriend.contract.model.Intent
import com.aifriend.contract.model.TaskConfirmationRequest
import com.aifriend.contract.model.TaskConfirmationResponse
import com.aifriend.contract.model.TaskSessionResponse
import com.aifriend.contract.model.TaskState
import com.aifriend.contract.model.WechatActionType
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContractSerializationTest {

    @Test
    fun taskCreationResponseDecodesContextualTaskEnumsAndNumericConfidence() {
        val response = NetworkModule.provideJson().decodeFromString<TaskSessionResponse>(
            """
            {
              "code":"OK",
              "message":"success",
              "data":{
                "sessionId":"ts_test",
                "sessionVersion":1,
                "state":"AWAITING_CONFIRMATION",
                "understanding":{
                  "intent":"VOICE_CALL",
                  "transcript":"test transcript",
                  "effectiveAudioRanges":[],
                  "corrections":[],
                  "confidence":0.91,
                  "processingVersions":{
                    "dialectCode":"zh-Hans-CN-x-wugang",
                    "dialectPackageVersion":"basic-experience-v1",
                    "primaryAsrModelVersion":"vosk-model-small-cn-0.22",
                    "mandarinAssistVersion":"vosk-model-small-cn-0.22",
                    "fusionRuleVersion":"basic-local-direct-v1",
                    "alignmentVersion":"basic-local-direct-v1",
                    "templateModelVersion":"mfcc-dtw-basic-v1",
                    "thresholdVersion":"basic-personal-v2"
                  }
                },
                "candidates":[],
                "spokenSummary":"test summary",
                "summaryHash":"test_hash",
                "allowedActions":["CONFIRM_CALL","CANCEL"],
                "channelResult":null,
                "expiresAt":"2026-09-03T07:00:00Z"
              },
              "traceId":"test_trace"
            }
            """.trimIndent(),
        )

        assertEquals(TaskState.AWAITING_CONFIRMATION, response.data.state)
        assertEquals(Intent.VOICE_CALL, response.data.understanding?.intent)
        assertEquals(BigDecimal("0.91"), response.data.understanding?.confidence)
        assertEquals(
            setOf(AllowedAction.CONFIRM_CALL, AllowedAction.CANCEL),
            response.data.allowedActions,
        )
    }
    @Test
    fun confirmationRequestEncodesContextualActionAndTimestamp() {
        val encoded = NetworkModule.provideJson().encodeToString(
            TaskConfirmationRequest(
                action = ConfirmationAction.CONFIRM_CALL,
                expectedVersion = 1,
                summaryHash = "test_hash",
                confirmedAt = OffsetDateTime.parse("2026-09-03T07:00:00Z"),
            ),
        )

        assertTrue(encoded.contains("\"action\":\"CONFIRM_CALL\""))
        assertTrue(encoded.contains("\"confirmedAt\":\"2026-09-03T07:00Z\""))
    }

    @Test
    fun confirmationResponseDecodesActionPlanType() {
        val response = NetworkModule.provideJson().decodeFromString<TaskConfirmationResponse>(
            """
            {
              "code":"OK",
              "message":"success",
              "data":{
                "session":{
                  "sessionId":"ts_test",
                  "sessionVersion":2,
                  "state":"EXECUTING",
                  "candidates":[],
                  "allowedActions":[],
                  "expiresAt":"2026-09-03T07:01:00Z"
                },
                "actionPlan":{
                  "planId":"plan_test",
                  "action":"START_VOICE_CALL",
                  "contactId":"contact_test",
                  "summaryHash":"test_hash",
                  "minimumRuleVersion":"wechat-semantic-call-v1",
                  "expiresAt":"2026-09-03T07:01:00Z",
                  "targetSearchLocator":"locator_test",
                  "targetLocatorProof":{
                    "proofVersion":"WECHAT_LOCATOR_PROOF_V1",
                    "keyId":"key_test",
                    "contactVersion":1,
                    "wechatVersion":"8.0.76",
                    "locatorVersion":"wechat-id-v1",
                    "salt":"salt_test",
                    "targetLocatorSha256":"digest_test",
                    "issuedAt":"2026-09-03T07:00:00Z",
                    "expiresAt":"2026-09-03T07:01:00Z",
                    "signature":"signature_test"
                  }
                }
              },
              "traceId":"test_trace"
            }
            """.trimIndent(),
        )

        assertEquals(TaskState.EXECUTING, response.data.session.state)
        assertEquals(WechatActionType.START_VOICE_CALL, response.data.actionPlan?.action)
    }

    @Test
    fun channelResultRequestEncodesContextualResult() {
        val encoded = NetworkModule.provideJson().encodeToString(
            ChannelResultRequest(
                planId = "plan_test",
                summaryHash = "test_hash",
                result = ChannelResult.CALL_STARTED,
                ruleVersion = "wechat-semantic-call-v1",
                occurredAt = OffsetDateTime.parse("2026-09-03T07:00:00Z"),
            ),
        )

        assertTrue(encoded.contains("\"result\":\"CALL_STARTED\""))
    }
}