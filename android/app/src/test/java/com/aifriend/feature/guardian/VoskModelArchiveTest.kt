package com.aifriend.feature.guardian

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vosk 模型归档摘要、路径边界和必需文件测试。 */
class VoskModelArchiveTest {

    @Test
    fun sha256ReadsBoundedArchive() {
        val bytes = "fixed model archive".encodeToByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(
            expected,
            VoskModelArchive.sha256(ByteArrayInputStream(bytes), bytes.size.toLong()),
        )
        assertThrows(IllegalArgumentException::class.java) {
            VoskModelArchive.sha256(ByteArrayInputStream(bytes), bytes.size.toLong() - 1L)
        }
    }

    @Test
    fun extractKeepsFilesInsideTargetAndRequiresManifestFiles() {
        val target = Files.createTempDirectory("vosk-model-test").toFile()
        try {
            val archive = zip(
                "model/am/final.mdl" to byteArrayOf(1, 2, 3),
                "model/conf/model.conf" to byteArrayOf(4, 5),
                "model/ivector/online_cmvn.conf" to byteArrayOf(),
            )

            VoskModelArchive.extract(
                input = ByteArrayInputStream(archive),
                destination = target,
                archiveRoot = "model/",
                requiredFiles = setOf(
                    "am/final.mdl",
                    "conf/model.conf",
                    "ivector/online_cmvn.conf",
                ),
                maximumEntries = 8,
                maximumFileBytes = 32,
                maximumTotalBytes = 64,
            )

            assertArrayEquals(byteArrayOf(1, 2, 3), target.resolve("am/final.mdl").readBytes())
            assertArrayEquals(byteArrayOf(4, 5), target.resolve("conf/model.conf").readBytes())
            assertEquals(0L, target.resolve("ivector/online_cmvn.conf").length())
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun extractRejectsPathTraversal() {
        val target = Files.createTempDirectory("vosk-model-test").toFile()
        try {
            val archive = zip("model/../escaped" to byteArrayOf(1))

            assertThrows(IllegalArgumentException::class.java) {
                VoskModelArchive.extract(
                    input = ByteArrayInputStream(archive),
                    destination = target,
                    archiveRoot = "model/",
                    requiredFiles = emptySet(),
                    maximumEntries = 8,
                    maximumFileBytes = 32,
                    maximumTotalBytes = 64,
                )
            }
        } finally {
            target.deleteRecursively()
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
}
