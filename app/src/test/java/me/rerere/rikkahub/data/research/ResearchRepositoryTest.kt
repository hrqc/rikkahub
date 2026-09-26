package me.rerere.rikkahub.data.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResearchRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun reader(onRead: () -> Unit = {}) = PublicWebReader(PublicWebTransport {
        onRead()
        PublicWebResponse(200, "text/plain", null, "Verified source text".toByteArray())
    })

    @Test fun `same chat can reread a source without network but another chat cannot borrow it`() = runBlocking {
        var requests = 0
        val repository = ResearchRepository(temporary.newFolder(), reader { requests++ })
        val first = repository.read("chat-a", "https://example.com/source")
        assertEquals(first, repository.source("chat-a", first.id))
        assertEquals(1, requests)
        assertEquals("SOURCE_NOT_FOUND", (runCatching { repository.source("chat-b", first.id) }.exceptionOrNull() as ResearchReadException).code)
        assertEquals("SOURCE_NOT_FOUND", (runCatching { repository.source("chat-a", "../other") }.exceptionOrNull() as ResearchReadException).code)
    }

    @Test fun `cache expiry never deletes an unrelated user file`() = runBlocking {
        val directory = temporary.newFolder()
        val unrelated = File(directory, "keep-my-notes.txt").apply { writeText("User notes") }
        var now = 1_700_000_000_000L
        val repository = ResearchRepository(directory, reader(), nowMillis = { now })
        val source = repository.read("chat", "https://example.com/source")
        now += 8 * 24 * 60 * 60 * 1_000L
        assertEquals("SOURCE_NOT_FOUND", (runCatching { repository.source("chat", source.id) }.exceptionOrNull() as ResearchReadException).code)
        assertEquals("User notes", unrelated.readText())
    }

    @Test fun `source count is capped and newest actual source remains available`() = runBlocking {
        val directory = temporary.newFolder()
        var now = 1_700_000_000_000L
        val repository = ResearchRepository(directory, reader(), nowMillis = { now++ })
        val first = repository.read("chat", "https://example.com/first")
        var last = first
        repeat(64) { last = repository.read("chat", "https://example.com/$it") }
        assertEquals(64, directory.listFiles()!!.count { it.extension == "json" })
        assertEquals(last, repository.source("chat", last.id))
        assertEquals("SOURCE_NOT_FOUND", (runCatching { repository.source("chat", first.id) }.exceptionOrNull() as ResearchReadException).code)
    }

    @Test fun `corrupt local evidence cannot be reported as a verified source`() = runBlocking {
        val directory = temporary.newFolder()
        val repository = ResearchRepository(directory, reader())
        val source = repository.read("chat", "https://example.com/source")
        val file = directory.listFiles()!!.single { it.extension == "json" }
        file.writeText(file.readText().replace("Verified source text", "Altered source text"))
        assertEquals("SOURCE_NOT_FOUND", (runCatching { repository.source("chat", source.id) }.exceptionOrNull() as ResearchReadException).code)
    }
}
