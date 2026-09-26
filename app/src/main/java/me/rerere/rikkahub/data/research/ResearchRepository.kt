package me.rerere.rikkahub.data.research

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Bounded app-private source cache; IDs are scoped to the chat that actually fetched the page. */
class ResearchRepository(
    private val cacheDirectory: File,
    private val reader: PublicWebReader = PublicWebReader(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val disk = Mutex()
    private val requests = Semaphore(2)
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun read(conversationId: String, url: String): ResearchSource = requests.withPermit {
        requireConversation(conversationId)
        val source = reader.read(url)
        withContext(Dispatchers.IO) {
            disk.withLock {
                try {
                    if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) storageUnavailable()
                    cleanExpired()
                    val encoded = json.encodeToString(source).toByteArray(Charsets.UTF_8)
                    if (encoded.size > MAX_SOURCE_BYTES || cacheDirectory.usableSpace < encoded.size + 1_048_576L) storageUnavailable()
                    trimToFit(encoded.size.toLong())
                    val output = sourceFile(conversationId, source.id)
                    val temporary = File(cacheDirectory, ".${output.name}.tmp")
                    try {
                        temporary.writeBytes(encoded)
                        if (!temporary.renameTo(output)) storageUnavailable()
                        output.setLastModified(nowMillis())
                    } finally { temporary.delete() }
                } catch (error: ResearchReadException) {
                    throw error
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    storageUnavailable()
                }
            }
        }
        source
    }

    suspend fun source(conversationId: String, sourceId: String): ResearchSource = withContext(Dispatchers.IO) {
        requireConversation(conversationId)
        if (!sourceId.matches(Regex("[0-9a-f]{32}"))) missing()
        disk.withLock {
            try {
                cleanExpired()
                val file = sourceFile(conversationId, sourceId)
                if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES.toLong()) missing()
                if (nowMillis() - file.lastModified() > MAX_AGE_MILLIS || file.lastModified() > nowMillis() + 60_000) missing()
                val source = json.decodeFromString<ResearchSource>(file.readText(Charsets.UTF_8))
                if (source.id != sourceId || source.textSha256 != researchSha256(source.text)) missing()
                source
            } catch (error: ResearchReadException) {
                throw error
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                missing()
            }
        }
    }

    private fun sourceFile(conversationId: String, id: String): File =
        File(cacheDirectory, "${researchSha256(conversationId)}_$id.json")

    private fun ownedFiles(): List<File> = cacheDirectory.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("[0-9a-f]{64}_[0-9a-f]{32}\\.json"))
    }.orEmpty()

    private fun cleanExpired() {
        val now = nowMillis()
        ownedFiles().filter { now - it.lastModified() > MAX_AGE_MILLIS || it.lastModified() > now + 60_000 }
            .forEach { it.delete() }
    }

    private fun trimToFit(incomingBytes: Long) {
        val files = ownedFiles().sortedBy(File::lastModified).toMutableList()
        var total = files.sumOf(File::length)
        while (files.size >= 64 || total + incomingBytes > 8_388_608L) {
            val oldest = files.removeFirstOrNull() ?: storageUnavailable()
            val size = oldest.length()
            if (!oldest.delete()) storageUnavailable()
            total -= size
        }
    }

    private fun requireConversation(value: String) {
        if (value.isBlank() || value.length > 256) throw ResearchReadException("CONVERSATION_REQUIRED", "缺少有效聊天上下文，未读取网页。")
    }

    private fun missing(): Nothing = throw ResearchReadException("SOURCE_NOT_FOUND", "本聊天没有该来源记录，或缓存已过期；不能使用其他聊天的来源 ID。")
    private fun storageUnavailable(): Nothing = throw ResearchReadException("SOURCE_STORAGE_UNAVAILABLE", "未能保存本地来源记录，请检查应用可用空间后重试。")

    private companion object {
        const val MAX_SOURCE_BYTES = 262_144
        const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1_000L
    }
}
