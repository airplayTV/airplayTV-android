package com.airplay.tv.feature.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DataStorePlaybackProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson(),
    persistenceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : PlaybackProgressRepository {
    private val saveQueue = Channel<SaveQueueEntry>(Channel.UNLIMITED)

    init {
        persistenceScope.launch {
            for (entry in saveQueue) {
                when (entry) {
                    is SaveQueueEntry.Save -> runCatching { saveQueued(entry.record) }
                    is SaveQueueEntry.Barrier -> entry.completion.complete(Unit)
                }
            }
        }
    }

    override suspend fun find(
        source: String,
        vid: String,
        pid: String,
    ): PlaybackRecord? {
        val key = stringPreferencesKey(playbackRecordKey(source, vid, pid))
        val json = dataStore.data.first()[key] ?: return null
        return decodeOrDelete(key, json)
    }

    override suspend fun latest(): PlaybackRecord? {
        val preferences = dataStore.data.first()
        val recordKeyName = preferences[LATEST_RECORD_KEY] ?: return null
        val recordKey = stringPreferencesKey(recordKeyName)
        val json = preferences[recordKey] ?: return null
        return decodeOrDelete(recordKey, json)
    }

    override suspend fun save(record: PlaybackRecord) {
        dataStore.edit { preferences ->
            persist(preferences, record)
            val currentRevision = preferences[CAPTURE_REVISION_KEY] ?: 0
            if (record.revision > currentRevision) {
                preferences[CAPTURE_REVISION_KEY] = record.revision
            }
        }
    }

    private suspend fun saveQueued(record: PlaybackRecord) {
        dataStore.edit { preferences ->
            val currentRevision = preferences[CAPTURE_REVISION_KEY] ?: 0
            val nextRevision = if (currentRevision == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                currentRevision + 1
            }
            persist(preferences, record.copy(revision = nextRevision))
            preferences[CAPTURE_REVISION_KEY] = nextRevision
        }
    }

    private fun persist(
        preferences: MutablePreferences,
        record: PlaybackRecord,
    ) {
        val recordKeyName = playbackRecordKey(record.source, record.vid, record.pid)
        val recordKey = stringPreferencesKey(recordKeyName)
        val json = gson.toJson(record)
        val existingRecord = decodeOrRemove(preferences, recordKey)
        val currentLatestKeyName = preferences[LATEST_RECORD_KEY]
        val currentLatest = when (currentLatestKeyName) {
            null -> null
            recordKeyName -> existingRecord
            else -> decodeOrRemove(preferences, stringPreferencesKey(currentLatestKeyName))
        }
        if (existingRecord != null && compareCaptureOrder(record, existingRecord) < 0) {
            return
        }

        preferences[recordKey] = json
        val finalLatestKeyName = if (
            currentLatest == null || compareCaptureOrder(record, currentLatest) >= 0
        ) {
            preferences[LATEST_RECORD_KEY] = recordKeyName
            recordKeyName
        } else {
            currentLatestKeyName
        }

        val records = preferences.asMap()
            .asSequence()
            .filter { (key, _) -> key.name.startsWith(RECORD_KEY_PREFIX) }
            .mapNotNull { (key, _) ->
                @Suppress("UNCHECKED_CAST")
                val stringKey = key as Preferences.Key<String>
                decodeOrRemove(preferences, stringKey)?.let { decoded ->
                    StoredRecord(key.name, decoded.updatedAtMs, decoded.revision)
                }
            }
            .toList()

        val excessCount = (records.size - MAX_RECORDS).coerceAtLeast(0)
        records.sortedWith { left, right ->
            compareCaptureOrder(left, right)
                .takeIf { it != 0 }
                ?: compareValues(
                    if (left.keyName == finalLatestKeyName) 1 else 0,
                    if (right.keyName == finalLatestKeyName) 1 else 0,
                ).takeIf { it != 0 }
                ?: left.keyName.compareTo(right.keyName)
        }.take(excessCount).forEach { storedRecord ->
            preferences.remove(stringPreferencesKey(storedRecord.keyName))
        }
    }

    override fun enqueueSave(record: PlaybackRecord) {
        check(saveQueue.trySend(SaveQueueEntry.Save(record)).isSuccess) {
            "Playback save queue is unavailable"
        }
    }

    override suspend fun drain() {
        val completion = CompletableDeferred<Unit>()
        saveQueue.send(SaveQueueEntry.Barrier(completion))
        completion.await()
    }

    private suspend fun decodeOrDelete(
        key: Preferences.Key<String>,
        json: String,
    ): PlaybackRecord? = try {
        decode(json)
    } catch (_: JsonParseException) {
        dataStore.edit { preferences ->
            if (preferences[key] == json) preferences.remove(key)
        }
        null
    }

    private fun decodeOrRemove(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
    ): PlaybackRecord? {
        val json = preferences[key] ?: return null
        return try {
            decode(json)
        } catch (_: JsonParseException) {
            preferences.remove(key)
            null
        }
    }

    private fun decode(json: String): PlaybackRecord =
        gson.fromJson(json, PlaybackRecord::class.java)
            ?: throw JsonParseException("Playback record JSON is null")

    private data class StoredRecord(
        val keyName: String,
        val updatedAtMs: Long,
        val revision: Long,
    )

    private fun compareCaptureOrder(
        left: PlaybackRecord,
        right: PlaybackRecord,
    ): Int = compareCaptureOrder(
        leftRevision = left.revision,
        leftUpdatedAtMs = left.updatedAtMs,
        rightRevision = right.revision,
        rightUpdatedAtMs = right.updatedAtMs,
    )

    private fun compareCaptureOrder(
        left: StoredRecord,
        right: StoredRecord,
    ): Int = compareCaptureOrder(
        leftRevision = left.revision,
        leftUpdatedAtMs = left.updatedAtMs,
        rightRevision = right.revision,
        rightUpdatedAtMs = right.updatedAtMs,
    )

    private fun compareCaptureOrder(
        leftRevision: Long,
        leftUpdatedAtMs: Long,
        rightRevision: Long,
        rightUpdatedAtMs: Long,
    ): Int = when {
        leftRevision > 0 || rightRevision > 0 -> leftRevision.compareTo(rightRevision)
        else -> leftUpdatedAtMs.compareTo(rightUpdatedAtMs)
    }

    private sealed interface SaveQueueEntry {
        data class Save(val record: PlaybackRecord) : SaveQueueEntry

        data class Barrier(val completion: CompletableDeferred<Unit>) : SaveQueueEntry
    }

    private companion object {
        const val RECORD_KEY_PREFIX = "record_"
        const val MAX_RECORDS = 500
        val LATEST_RECORD_KEY = stringPreferencesKey("latest_record_key")
        val CAPTURE_REVISION_KEY = longPreferencesKey("capture_revision")
    }
}
