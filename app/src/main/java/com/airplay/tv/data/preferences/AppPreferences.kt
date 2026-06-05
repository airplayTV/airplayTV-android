package com.airplay.tv.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "airplay_tv")

class AppPreferences(private val context: Context) {
    companion object {
        val KEY_SOURCE = stringPreferencesKey("source")
        val KEY_TAG = stringPreferencesKey("tag")
        val KEY_SECRET = stringPreferencesKey("secret")
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_ROOM = stringPreferencesKey("room")
    }

    val source: Flow<String> = context.dataStore.data.map { it[KEY_SOURCE] ?: "" }
    val tag: Flow<String> = context.dataStore.data.map { it[KEY_TAG] ?: "" }
    val secret: Flow<String> = context.dataStore.data.map { it[KEY_SECRET] ?: "" }
    val username: Flow<String> = context.dataStore.data.map { it[KEY_USERNAME] ?: "" }
    val room: Flow<String> = context.dataStore.data.map { it[KEY_ROOM] ?: "" }

    suspend fun setSource(value: String) { context.dataStore.edit { it[KEY_SOURCE] = value } }
    suspend fun setTag(value: String) { context.dataStore.edit { it[KEY_TAG] = value } }
    suspend fun setSecret(value: String) { context.dataStore.edit { it[KEY_SECRET] = value } }
    suspend fun setUsername(value: String) { context.dataStore.edit { it[KEY_USERNAME] = value } }
    suspend fun setRoom(value: String) { context.dataStore.edit { it[KEY_ROOM] = value } }
}
