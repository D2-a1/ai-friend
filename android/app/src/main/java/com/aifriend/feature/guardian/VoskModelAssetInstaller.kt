package com.aifriend.feature.guardian

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 随 APK 固定发布的 Vosk 中文模型信息。 */
object GuardianWakeModelManifest {
    const val ENGINE_VERSION = "vosk-android-0.3.75"
    const val MODEL_VERSION = "vosk-model-small-cn-0.22"
    const val ARCHIVE_ASSET = "voice/vosk/vosk-model-small-cn-0.22.zip"
    const val ARCHIVE_SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
    const val ARCHIVE_ROOT = "vosk-model-small-cn-0.22/"
    const val WAKE_PHRASE = "小友"

    val files = mapOf(
        "am/final.mdl" to ModelFile(15_901_896, "91eda2c04c4f599361cb92b0e5298ccdf6b3c7a1fa52bfcefcd2c4e07aa1c131"),
        "graph/Gr.fst" to ModelFile(26_082_259, "46d41a9b1723abccdcb354d731a5b58d87c060100d88ada1a3e31220f52b0434"),
        "graph/disambig_tid.int" to ModelFile(234, "c9200318c2a265549ebfbea7e0176fa62e131e953abd140c9b0b39fe61e24015"),
        "graph/phones/word_boundary.int" to ModelFile(7_161, "11ca784b99f03188b451fb54714c2de3dd694e662a5f88e82df435c2789b9aec"),
        "graph/HCLr.fst" to ModelFile(16_158_762, "798af0d9ba6f2d727747f58e1e7b2305f8618bc8ea7f6c51beecd8a2c965b965"),
        "ivector/online_cmvn.conf" to ModelFile(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        "ivector/final.ie" to ModelFile(9_927_287, "c39d46fbff04efb7baa9660ae7a728b0e2f35a4a1250b56ed3a599e26956df86"),
        "ivector/final.mat" to ModelFile(44_975, "42706d88818070ea0d0182b4d11367ba52a13b9a2be45630f60858a422109ada"),
        "ivector/final.dubm" to ModelFile(168_048, "e33276cbd548c0a03ae5812c98115940614997dff23a6d59968af6eaf7630ff8"),
        "ivector/splice.conf" to ModelFile(35, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756"),
        "ivector/global_cmvn.stats" to ModelFile(1_090, "b267dc89c1556fdd5bc358f0a480929da1743eeccfa06a67daf8db629ca336bb"),
        "conf/mfcc.conf" to ModelFile(153, "6c326fea5741c2d78be12f76eb1b36ea2ace040da9e355e4cfe6bb80e5f7d5ab"),
        "conf/model.conf" to ModelFile(289, "468a8125c07530f96adcc219a0a5f993e8e0721ccf18acbd3169b77fc685fb9b"),
        "README" to ModelFile(82, "7362be411c609f2a1c9d2dfe22c2d72555c15eb90bfe47c41772f49ef94840ce"),
    )

    data class ModelFile(val bytes: Long, val sha256: String)
}

/**
 * 将 APK 内已固定摘要的模型安全安装到应用私有目录。
 *
 * 安装前先复验完整 ZIP 的 SHA-256；解压阶段限制根目录、文件数量、单文件和总大小，
 * 拒绝路径穿越。完成标记不匹配时不会加载旧目录。
 */
@Singleton
class VoskModelAssetInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun install(): File = withContext(Dispatchers.IO) {
        val parent = File(context.noBackupFilesDir, PRIVATE_MODEL_PARENT)
        val destination = File(parent, GuardianWakeModelManifest.MODEL_VERSION)
        if (isComplete(destination)) return@withContext destination

        require(parent.exists() || parent.mkdirs()) { "WAKE_MODEL_DIRECTORY_UNAVAILABLE" }
        val actualHash = context.assets.open(
            GuardianWakeModelManifest.ARCHIVE_ASSET,
            android.content.res.AssetManager.ACCESS_STREAMING,
        ).use { input ->
            VoskModelArchive.sha256(input, MAX_ARCHIVE_BYTES)
        }
        require(actualHash == GuardianWakeModelManifest.ARCHIVE_SHA256) {
            "WAKE_MODEL_ARCHIVE_INVALID"
        }

        val staging = File(parent, "${GuardianWakeModelManifest.MODEL_VERSION}.staging")
        clearPrivateDirectory(staging)
        require(staging.mkdirs()) { "WAKE_MODEL_STAGING_UNAVAILABLE" }
        try {
            context.assets.open(
                GuardianWakeModelManifest.ARCHIVE_ASSET,
                android.content.res.AssetManager.ACCESS_STREAMING,
            ).use { input ->
                VoskModelArchive.extract(
                    input = input,
                    destination = staging,
                    archiveRoot = GuardianWakeModelManifest.ARCHIVE_ROOT,
                    requiredFiles = GuardianWakeModelManifest.files.keys,
                    maximumEntries = MAX_ARCHIVE_ENTRIES,
                    maximumFileBytes = MAX_MODEL_FILE_BYTES,
                    maximumTotalBytes = MAX_MODEL_BYTES,
                )
            }
            File(staging, COMPLETE_MARKER).writeText(actualHash, Charsets.US_ASCII)
            clearPrivateDirectory(destination)
            require(staging.renameTo(destination)) { "WAKE_MODEL_COMMIT_FAILED" }
            require(isComplete(destination)) { "WAKE_MODEL_INSTALL_INVALID" }
            destination
        } catch (exception: Exception) {
            clearPrivateDirectory(staging)
            throw exception
        }
    }

    private fun isComplete(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val marker = File(directory, COMPLETE_MARKER)
        if (!marker.isFile || marker.length() > 128L) return false
        if (marker.readText(Charsets.US_ASCII).trim() != GuardianWakeModelManifest.ARCHIVE_SHA256) {
            return false
        }
        return GuardianWakeModelManifest.files.all { (relative, expected) ->
            val file = File(directory, relative)
            file.isFile && file.length() == expected.bytes && (
                expected.bytes == 0L && expected.sha256 == EMPTY_SHA256 ||
                    expected.bytes > 0L && file.inputStream().use { input ->
                        VoskModelArchive.sha256(input, expected.bytes) == expected.sha256
                    }
                )
        }
    }

    private fun clearPrivateDirectory(directory: File) {
        if (!directory.exists()) return
        require(directory.canonicalFile.toPath().startsWith(context.noBackupFilesDir.canonicalFile.toPath())) {
            "WAKE_MODEL_DELETE_SCOPE_INVALID"
        }
        require(directory.deleteRecursively()) { "WAKE_MODEL_CLEANUP_FAILED" }
    }

    private companion object {
        const val PRIVATE_MODEL_PARENT = "guardian-models"
        const val COMPLETE_MARKER = ".archive-sha256"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val MAX_ARCHIVE_BYTES = 50L * 1024L * 1024L
        const val MAX_MODEL_BYTES = 96L * 1024L * 1024L
        const val MAX_MODEL_FILE_BYTES = 64L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 64
    }
}

/** 纯 JVM 的模型归档摘要和受限解压逻辑。 */
object VoskModelArchive {
    fun sha256(input: InputStream, maximumBytes: Long): String {
        require(maximumBytes > 0L) { "ARCHIVE_LIMIT_INVALID" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        BufferedInputStream(input).use { buffered ->
            while (true) {
                val count = buffered.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximumBytes) { "WAKE_MODEL_ARCHIVE_TOO_LARGE" }
                digest.update(buffer, 0, count)
            }
        }
        buffer.fill(0)
        require(total > 0L) { "WAKE_MODEL_ARCHIVE_EMPTY" }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    @Suppress("LongParameterList")
    fun extract(
        input: InputStream,
        destination: File,
        archiveRoot: String,
        requiredFiles: Set<String>,
        maximumEntries: Int,
        maximumFileBytes: Long,
        maximumTotalBytes: Long,
    ) {
        require(destination.isDirectory && archiveRoot.endsWith('/')) { "ARCHIVE_TARGET_INVALID" }
        require(maximumEntries > 0 && maximumFileBytes > 0L && maximumTotalBytes > 0L) {
            "ARCHIVE_LIMIT_INVALID"
        }
        val targetRoot = destination.canonicalFile
        val seen = mutableSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= maximumEntries) { "WAKE_MODEL_ENTRY_LIMIT_EXCEEDED" }
                require(entry.name.startsWith(archiveRoot)) { "WAKE_MODEL_ARCHIVE_ROOT_INVALID" }
                val relative = entry.name.removePrefix(archiveRoot).trimEnd('/')
                if (relative.isEmpty()) {
                    require(entry.isDirectory) { "WAKE_MODEL_ENTRY_INVALID" }
                    zip.closeEntry()
                    continue
                }
                require(!relative.startsWith('/') && '\\' !in relative) {
                    "WAKE_MODEL_ENTRY_INVALID"
                }
                val target = File(targetRoot, relative).canonicalFile
                require(target.toPath().startsWith(targetRoot.toPath()) && target != targetRoot) {
                    "WAKE_MODEL_PATH_TRAVERSAL"
                }
                if (entry.isDirectory) {
                    require(target.exists() || target.mkdirs()) { "WAKE_MODEL_DIRECTORY_UNAVAILABLE" }
                } else {
                    require(seen.add(relative)) { "WAKE_MODEL_DUPLICATE_ENTRY" }
                    require(target.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                        "WAKE_MODEL_DIRECTORY_UNAVAILABLE"
                    }
                    var fileBytes = 0L
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= maximumFileBytes && totalBytes <= maximumTotalBytes) {
                                "WAKE_MODEL_EXPANDED_TOO_LARGE"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        buffer.fill(0)
        require(seen.containsAll(requiredFiles)) { "WAKE_MODEL_REQUIRED_FILE_MISSING" }
    }
}
