package com.aifriend.feature.contact

import com.aifriend.contract.api.ContactsApi
import com.aifriend.contract.model.AliasResponse
import com.aifriend.contract.model.AliasCompatibility
import com.aifriend.contract.model.Contact
import com.aifriend.contract.model.ContactAlias
import com.aifriend.contract.model.ContactPage
import com.aifriend.contract.model.ContactPageResponse
import com.aifriend.contract.model.ContactResponse
import com.aifriend.contract.model.ContactStatus
import com.aifriend.contract.model.ContactUnbindRequest
import com.aifriend.contract.model.CreateAliasRequest
import com.aifriend.contract.model.DeleteAliasRequest
import com.aifriend.contract.model.LocalVerificationRequest
import com.aifriend.contract.model.PageMetadata
import com.aifriend.feature.auth.AuthSession
import com.aifriend.feature.auth.AuthSessionRepository
import com.aifriend.feature.auth.WechatLoginDevice
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class DefaultContactRepositoryTest {

    @Test
    fun ensureDebugDemoContactRetriesOnceAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)

        val result = repository.ensureDebugDemoContact()

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.demoContactRequests)
        assertEquals("体验联系人", result.remark)
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status)
    }

    @Test
    fun listRetriesOnceWithSamePagingAndStatusAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)

        val result = repository.list(1, 10, ContactStatus.PENDING_LOCAL_VERIFY)

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0], api.requests[1])
        assertEquals(ListRequest(1, 10, ContactStatus.PENDING_LOCAL_VERIFY), api.requests[0])
        assertEquals("ct_0123456789abcdef0123456789abcdef", result.items.single().id)
        assertEquals("二女儿", result.items.single().remark)
    }

    @Test
    fun verifyRetriesOnceWithSameIdempotencyKeyAndMinimumEvidenceAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)
        val verifiedAt = OffsetDateTime.parse("2026-08-09T03:00:00Z")
        val evidence = LocalWechatVerificationEvidence(
            stableLocator = "wxid_stable_target",
            currentRemark = "二女儿",
            pageType = LocalWechatPageType.CONTACT_PROFILE,
            friendConfirmed = true,
            locatorObservationCount = 1,
            locatorUnique = true,
            wechatVersion = "8.0.56",
            ruleVersion = "wechat-contact-v1",
            verifiedAt = verifiedAt,
            expectedContactVersion = 1,
        )

        val result = repository.verifyLocalWechatContact(
            "ct_0123456789abcdef0123456789abcdef",
            evidence,
        )

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.verificationRequests.size)
        assertEquals(api.verificationRequests[0], api.verificationRequests[1])
        val request = api.verificationRequests.first().request
        assertEquals("wxid_stable_target", request.stableLocator)
        assertEquals("二女儿", request.currentRemark)
        assertEquals(1, request.locatorObservationCount)
        assertEquals(1, request.expectedContactVersion)
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status)
    }

    @Test
    fun unbindRetriesOnceWithSameIdempotencyKeyAndExpectedVersionAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)

        val result = repository.unbindContact(
            "ct_0123456789abcdef0123456789abcdef",
            2,
        )

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.unbindRequests.size)
        assertEquals(api.unbindRequests[0], api.unbindRequests[1])
        assertEquals(true, api.unbindRequests.first().request.confirmed)
        assertEquals(2, api.unbindRequests.first().request.expectedContactVersion)
        assertEquals(ContactStatus.REVOKED, result.status)
    }

    @Test
    fun createAliasRetriesOnceWithSameIdempotencyKeyAndAudioObjectsAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)

        val result = repository.createAlias(
            contactId = "ct_0123456789abcdef0123456789abcdef",
            displayText = "大女儿",
            phoneticHint = "da nv er",
            firstAudioObjectId = "au_11111111111111111111111111111111",
            secondAudioObjectId = "au_22222222222222222222222222222222",
            expectedContactVersion = 2,
        )

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.aliasCreationRequests.size)
        assertEquals(api.aliasCreationRequests[0], api.aliasCreationRequests[1])
        assertEquals("大女儿", api.aliasCreationRequests.first().request.displayText)
        assertEquals(2, api.aliasCreationRequests.first().request.expectedContactVersion)
        assertEquals("大女儿", result.displayText)
    }

    @Test
    fun deleteAliasRetriesOnceWithSameIdempotencyKeyAndContactVersionAfter401() = runTest {
        val api = FakeContactsApi()
        val auth = FakeAuthSessionRepository()
        val repository = DefaultContactRepository(api, auth)

        val result = repository.deleteAlias(
            contactId = "ct_0123456789abcdef0123456789abcdef",
            aliasId = "al_33333333333333333333333333333333",
            expectedContactVersion = 3,
        )

        assertEquals(1, auth.refreshCount)
        assertEquals(2, api.aliasDeletionRequests.size)
        assertEquals(api.aliasDeletionRequests[0], api.aliasDeletionRequests[1])
        assertEquals(true, api.aliasDeletionRequests.first().request.confirmed)
        assertEquals(3, api.aliasDeletionRequests.first().request.expectedContactVersion)
        assertEquals(ContactStatus.ACTIVE_NO_ALIAS, result.status)
    }

    private data class ListRequest(
        val page: Int?,
        val size: Int?,
        val status: ContactStatus?,
    )

    private data class VerificationRequest(
        val contactId: String,
        val idempotencyKey: String,
        val request: LocalVerificationRequest,
    )

    private data class UnbindRequest(
        val contactId: String,
        val idempotencyKey: String,
        val request: ContactUnbindRequest,
    )

    private data class AliasCreationRequest(
        val contactId: String,
        val idempotencyKey: String,
        val request: CreateAliasRequest,
    )

    private data class AliasDeletionRequest(
        val contactId: String,
        val aliasId: String,
        val idempotencyKey: String,
        val request: DeleteAliasRequest,
    )

    private class FakeContactsApi : ContactsApi {
        var demoContactRequests = 0
        val requests = mutableListOf<ListRequest>()
        val verificationRequests = mutableListOf<VerificationRequest>()
        val unbindRequests = mutableListOf<UnbindRequest>()
        val aliasCreationRequests = mutableListOf<AliasCreationRequest>()
        val aliasDeletionRequests = mutableListOf<AliasDeletionRequest>()

        override suspend fun ensureDebugDemoContact(): Response<ContactResponse> {
            demoContactRequests++
            if (demoContactRequests == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-29T04:00:00Z")
            return Response.success(
                ContactResponse(
                    code = ContactResponse.Code.OK,
                    message = "success",
                    data = Contact(
                        id = "ct_99999999999999999999999999999999",
                        status = ContactStatus.ACTIVE_NO_ALIAS,
                        aliasCount = 0,
                        aliases = emptyList(),
                        version = 1,
                        createdAt = now,
                        updatedAt = now,
                        remark = "体验联系人",
                        relationship = "仅用于本机体验",
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun listContacts(
            page: Int?,
            size: Int?,
            status: ContactStatus?,
        ): Response<ContactPageResponse> {
            requests += ListRequest(page, size, status)
            if (requests.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-07T01:00:00Z")
            return Response.success(
                ContactPageResponse(
                    code = ContactPageResponse.Code.OK,
                    message = "success",
                    data = ContactPage(
                        items = listOf(
                            Contact(
                                id = "ct_0123456789abcdef0123456789abcdef",
                                status = ContactStatus.PENDING_LOCAL_VERIFY,
                                aliasCount = 0,
                                version = 1,
                                createdAt = now.minusDays(1),
                                updatedAt = now,
                                remark = "二女儿",
                                aliases = emptyList(),
                            ),
                        ),
                        page = PageMetadata(
                            page = 1,
                            propertySize = 10,
                            totalElements = 1,
                            totalPages = 1,
                        ),
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun createContactAlias(
            id: String,
            idempotencyKey: String,
            createAliasRequest: CreateAliasRequest,
        ): Response<AliasResponse> {
            aliasCreationRequests += AliasCreationRequest(id, idempotencyKey, createAliasRequest)
            if (aliasCreationRequests.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-12T05:00:00Z")
            return Response.success(
                AliasResponse(
                    code = AliasResponse.Code.OK,
                    message = "success",
                    data = ContactAlias(
                        id = "al_33333333333333333333333333333333",
                        displayText = createAliasRequest.displayText,
                        dialectCode = "zh-Hans-CN-x-wugang",
                        dialectPackageVersion = "wugang-test-v1",
                        modelVersion = "content-template-test-v1",
                        thresholdVersion = "registration-threshold-test-v1",
                        compatibility = AliasCompatibility.COMPATIBLE,
                        createdAt = now,
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun deleteContactAlias(
            id: String,
            aliasId: String,
            idempotencyKey: String,
            deleteAliasRequest: DeleteAliasRequest,
        ): Response<ContactResponse> {
            aliasDeletionRequests += AliasDeletionRequest(
                id,
                aliasId,
                idempotencyKey,
                deleteAliasRequest,
            )
            if (aliasDeletionRequests.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-12T05:10:00Z")
            return Response.success(
                ContactResponse(
                    code = ContactResponse.Code.OK,
                    message = "success",
                    data = Contact(
                        id = id,
                        status = ContactStatus.ACTIVE_NO_ALIAS,
                        aliasCount = 0,
                        aliases = emptyList(),
                        version = deleteAliasRequest.expectedContactVersion + 1,
                        createdAt = now.minusDays(1),
                        updatedAt = now,
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun unbindContact(
            id: String,
            idempotencyKey: String,
            contactUnbindRequest: ContactUnbindRequest,
        ): Response<ContactResponse> {
            unbindRequests += UnbindRequest(id, idempotencyKey, contactUnbindRequest)
            if (unbindRequests.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-09T05:00:00Z")
            return Response.success(
                ContactResponse(
                    code = ContactResponse.Code.OK,
                    message = "success",
                    data = Contact(
                        id = id,
                        status = ContactStatus.REVOKED,
                        aliasCount = 0,
                        version = 3,
                        createdAt = now.minusDays(1),
                        updatedAt = now,
                        aliases = emptyList(),
                    ),
                    traceId = "trace",
                ),
            )
        }

        override suspend fun verifyLocalWechatContact(
            id: String,
            idempotencyKey: String,
            localVerificationRequest: LocalVerificationRequest,
        ): Response<ContactResponse> {
            verificationRequests += VerificationRequest(
                id,
                idempotencyKey,
                localVerificationRequest,
            )
            if (verificationRequests.size == 1) {
                return Response.error(
                    401,
                    "{}".toResponseBody("application/json".toMediaType()),
                )
            }
            val now = OffsetDateTime.parse("2026-08-09T03:00:00Z")
            return Response.success(
                ContactResponse(
                    code = ContactResponse.Code.OK,
                    message = "success",
                    data = Contact(
                        id = id,
                        status = ContactStatus.ACTIVE_NO_ALIAS,
                        aliasCount = 0,
                        version = 2,
                        createdAt = now.minusDays(1),
                        updatedAt = now,
                        remark = "二女儿",
                        aliases = emptyList(),
                        localVerificationVersion = "wechat-contact-v1",
                        verifiedAt = now,
                    ),
                    traceId = "trace",
                ),
            )
        }
    }

    private class FakeAuthSessionRepository : AuthSessionRepository {
        override val session: StateFlow<AuthSession?> = MutableStateFlow(null)
        var refreshCount = 0

        override suspend fun restore(): AuthSession? = null

        override suspend fun loginWithWechatCode(
            code: String,
            device: WechatLoginDevice,
        ): AuthSession = error("not used")

        override suspend fun refresh(): AuthSession {
            refreshCount++
            return AuthSession(
                userId = "us_0123456789abcdef0123456789abcdef",
                userStatus = "ACTIVE",
                displayName = null,
                accessTokenExpiresAt = Instant.parse("2026-08-07T01:15:00Z"),
                refreshTokenExpiresAt = Instant.parse("2026-09-07T01:00:00Z"),
            )
        }

        override suspend fun clearLocalSession() = Unit
    }
}
