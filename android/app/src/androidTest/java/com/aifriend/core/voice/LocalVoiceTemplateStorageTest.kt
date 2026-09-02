package com.aifriend.core.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room owner 隔离与专用 Keystore AES-GCM 的设备测试。 */
@RunWith(AndroidJUnit4::class)
class LocalVoiceTemplateStorageTest {
    private lateinit var database: LocalVoiceTemplateDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LocalVoiceTemplateDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun daoNeverReturnsAnotherOwnerTemplate() = runBlocking {
        val dao = database.localVoiceTemplateDao()
        dao.upsertAll(
            listOf(
                entity("owner-a", "vt_a", "CONFIRM_SEND"),
                entity("owner-b", "vt_b", "CONFIRM_SEND"),
            ),
        )

        assertEquals(listOf("vt_a"), dao.list("owner-a").map { it.templateId })
        assertEquals("vt_b", dao.findSafetyCommand("owner-b", "CONFIRM_SEND")?.templateId)
        assertNull(dao.findSafetyCommand("owner-a", "CONFIRM_CALL"))
    }

    @Test
    fun deletingRoutineCategoryKeepsSafetyTemplatesAndOtherOwners() = runBlocking {
        val dao = database.localVoiceTemplateDao()
        dao.upsertAll(
            listOf(
                entity("owner-a", "vt_routine_a", null, "ROUTINE_COMMAND"),
                entity("owner-a", "vt_safety_a", "CONFIRM_SEND"),
                entity("owner-b", "vt_routine_b", null, "ROUTINE_COMMAND"),
            ),
        )

        dao.deleteCategory("owner-a", "ROUTINE_COMMAND")

        assertEquals(listOf("vt_safety_a"), dao.list("owner-a").map { it.templateId })
        assertEquals(listOf("vt_routine_b"), dao.list("owner-b").map { it.templateId })
    }

    @Test
    fun deletingAliasMaterialIsScopedToCurrentOwnerAndAlias() = runBlocking {
        val dao = database.localVoiceTemplateDao()
        dao.upsertAll(
            listOf(
                entity("owner-a", "vt_alias_a", null, "CONTACT_ALIAS", "al_same"),
                entity("owner-a", "vt_alias_keep", null, "CONTACT_ALIAS", "al_keep"),
                entity("owner-b", "vt_alias_b", null, "CONTACT_ALIAS", "al_same"),
            ),
        )

        dao.deleteAlias("owner-a", "al_same")

        assertEquals(listOf("vt_alias_keep"), dao.list("owner-a").map { it.templateId })
        assertEquals(listOf("vt_alias_b"), dao.list("owner-b").map { it.templateId })
    }

    @Test
    fun dedicatedCipherBindsCiphertextToAssociatedMetadata() {
        val cipher = AndroidKeystoreVoiceTemplateCipher()
        val material = "content-template".encodeToByteArray()
        val associatedData = "owner-a\u001fvt_a\u001fversion-1".encodeToByteArray()
        val encrypted = cipher.encrypt(material, associatedData)

        assertArrayEquals(material, cipher.decrypt(encrypted, associatedData))
        val failure = runCatching {
            cipher.decrypt(encrypted, "owner-b\u001fvt_a\u001fversion-1".encodeToByteArray())
        }.exceptionOrNull()
        requireNotNull(failure)
    }

    private fun entity(
        owner: String,
        templateId: String,
        type: String?,
        category: String = "SAFETY_COMMAND",
        aliasId: String? = null,
    ): LocalVoiceTemplateEntity = LocalVoiceTemplateEntity(
        ownerScope = owner,
        templateId = templateId,
        category = category,
        contactId = null,
        aliasId = aliasId,
        safetyCommandType = type,
        dialectCode = "zh-Hans-CN-x-wugang",
        dialectPackageVersion = "test-package-1",
        modelVersion = "test-model-1",
        thresholdVersion = "test-threshold-1",
        compatibility = "COMPATIBLE",
        serverUpdatedAt = "2026-08-13T00:00:00Z",
        materialSha256 = MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf(1))
            .joinToString("") { "%02x".format(it) },
        encryptedMaterial = byteArrayOf(1),
    )
}
