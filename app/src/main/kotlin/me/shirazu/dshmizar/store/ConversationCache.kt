package me.shirazu.dshmizar.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.shirazu.mizar.kit.SessionMessagesResult
import me.shirazu.mizar.kit.SessionStatsResult
import java.io.File

/**
 * The last transcript and figures this phone read for one session.
 *
 * The index already remembers which sessions exist; without this, opening one on a
 * train showed an empty screen under its title. Plain JSON files in the app's own
 * storage: the content is the operator's conversation, not a credential, and putting
 * it behind a Keystore that may be unavailable is exactly how an offline reader
 * stops reading.
 *
 * Records are merged by their `seq`, bounded to the newest [MAX_RECORDS], and written
 * atomically; a cache that is missing or unreadable is simply no cache.
 */
@Serializable
data class CachedConversation(
    val messages: SessionMessagesResult,
    val stats: SessionStatsResult? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

class ConversationCache(context: Context) {

    private val directory = File(context.filesDir, "conversation-cache")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(hostId: String, sessionId: String): CachedConversation? = withContext(Dispatchers.IO) {
        runCatching { fileFor(hostId, sessionId).takeIf { it.isFile }?.readText() }
            .getOrNull()?.let { text -> runCatching { json.decodeFromString<CachedConversation>(text) }.getOrNull() }
    }

    /** Fold one page of records into the cache, keeping the newest [MAX_RECORDS]. */
    suspend fun record(hostId: String, sessionId: String, page: SessionMessagesResult) = withContext(Dispatchers.IO) {
        runCatching {
            val previous = load(hostId, sessionId)
            val merged = merge(previous?.messages?.records.orEmpty(), page.records)
            val snapshot = CachedConversation(
                messages = page.copy(records = merged, hasMore = page.hasMore),
                stats = previous?.stats,
            )
            write(hostId, sessionId, snapshot)
        }
    }

    suspend fun recordStats(hostId: String, sessionId: String, stats: SessionStatsResult) = withContext(Dispatchers.IO) {
        runCatching {
            val previous = load(hostId, sessionId) ?: return@runCatching
            write(hostId, sessionId, previous.copy(stats = stats, updatedAtMillis = System.currentTimeMillis()))
        }
    }

    /** Merge two record lists by `seq`, newest kept, oldest dropped at the bound. */
    private fun merge(old: List<JsonElement>, fresh: List<JsonElement>): List<JsonElement> {
        val bySeq = LinkedHashMap<String, JsonElement>()
        for (record in old + fresh) {
            val key = record.toString()
            val seq = runCatching {
                (record as? kotlinx.serialization.json.JsonObject)
                    ?.get("event")
                    ?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("seq") }
                    ?.toString()
            }.getOrNull()
            bySeq[(seq ?: key) + "|" + key] = record
        }
        return bySeq.values.toList().takeLast(MAX_RECORDS)
    }

    private fun write(hostId: String, sessionId: String, snapshot: CachedConversation) {
        directory.mkdirs()
        val target = fileFor(hostId, sessionId)
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(json.encodeToString(snapshot))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    private fun fileFor(hostId: String, sessionId: String): File =
        File(File(directory, safe(hostId)), safe(sessionId) + ".json")

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val MAX_RECORDS = 400
    }
}
