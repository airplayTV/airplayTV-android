package com.airplay.tv.feature.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.flow.first

class DataStorePlaybackProgressRepository(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson = Gson(),
) : PlaybackProgressRepository {
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
        val recordKeyName = playbackRecordKey(record.source, record.vid, record.pid)
        val recordKey = stringPreferencesKey(recordKeyName)
        val json = gson.toJson(record)
        dataStore.edit { preferences ->
            preferences[recordKey] = json

            val currentLatestKeyName = preferences[LATEST_RECORD_KEY]
            val currentLatest = currentLatestKeyName?.let { keyName ->
                decodeOrRemove(preferences, stringPreferencesKey(keyName))
            }
            val finalLatestKeyName = if (
                currentLatest == null || record.updatedAtMs >= currentLatest.updatedAtMs
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
                        StoredRecord(key.name, decoded.updatedAtMs)
                    }
                }
                .toList()

            val excessCount = (records.size - MAX_RECORDS).coerceAtLeast(0)
            records.sortedWith(
                compareBy<StoredRecord> { it.updatedAtMs }
                    .thenBy { if (it.keyName == finalLatestKeyName) 1 else 0 }
                    .thenBy { it.keyName },
            ).take(excessCount).forEach { storedRecord ->
                preferences.remove(stringPreferencesKey(storedRecord.keyName))
            }
        }
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
    )

    private companion object {
        const val RECORD_KEY_PREFIX = "record_"
        const val MAX_RECORDS = 500
        val LATEST_RECORD_KEY = stringPreferencesKey("latest_record_key")
    }
}
