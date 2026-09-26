package me.rerere.rikkahub.data.research

import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.MessageDigest

class ResearchReadException(val code: String, message: String) : IOException(message)

@Serializable
data class ResearchLink(val title: String, val url: String, val kind: String = "page")

@Serializable
data class ResearchSource(
    val id: String,
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val retrievedAt: String,
    /** A declaration in the page, not an independently verified publication date. */
    val pageDeclaredPublishedAt: String? = null,
    val mimeType: String,
    val text: String,
    /** Hash of exactly the locally retained UTF-8 text, not the original website or a download. */
    val textSha256: String,
    val links: List<ResearchLink>,
    val extractionMethod: String,
    val removedAdvertisingElements: Int,
    val removedNoiseElements: Int,
    val truncated: Boolean,
    val limitations: List<String>,
)

internal fun researchSha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
