package com.airplay.tv.feature.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePlaybackProgressRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun saveCanBeFoundAndBecomesLatest() = runTest {
        val repository = repository(backgroundScope)
        val expected = record(pid = "p1", updatedAtMs = 10)

        repository.save(expected)

        assertEquals(expected, repository.find("source", "vid", "p1"))
        assertEquals(expected, repository.latest())
    }

    @Test
    fun olderSaveDoesNotReplaceLatestPointer() = runTest {
        val repository = repository(backgroundScope)
        repository.save(record(pid = "newer", updatedAtMs = 20))

        repository.save(record(pid = "older", updatedAtMs = 19))

        assertEquals("newer", repository.latest()?.pid)
        assertEquals("older", repository.find("source", "vid", "older")?.pid)
    }

    @Test
    fun equallyRecentSaveReplacesLatestPointer() = runTest {
        val repository = repository(backgroundScope)
        repository.save(record(pid = "first", updatedAtMs = 20))

        repository.save(record(pid = "second", updatedAtMs = 20))

        assertEquals("second", repository.latest()?.pid)
    }

    @Test
    fun storeKeepsNewestFiveHundred() = runTest {
        val repository = repository(backgroundScope)
        repeat(501) { repository.save(record(pid = "p$it", updatedAtMs = it.toLong())) }

        assertNull(repository.find("source", "vid", "p0"))
        assertEquals("p500", repository.latest()?.pid)
    }

    @Test
    fun corruptRecordIsDeletedWithoutDeletingOtherRecords() = runTest {
        val dataStore = dataStore(backgroundScope)
        val repository = DataStorePlaybackProgressRepository(dataStore)
        val valid = record(pid = "valid", updatedAtMs = 1)
        repository.save(valid)
        val corruptKey = playbackRecordKey("source", "vid", "corrupt")
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(corruptKey)] = "{not-json"
        }

        assertNull(repository.find("source", "vid", "corrupt"))

        val stored = dataStore.data.first()
        assertNull(stored[stringPreferencesKey(corruptKey)])
        assertTrue(stored.contains(stringPreferencesKey(playbackRecordKey("source", "vid", "valid"))))
        assertEquals(valid, repository.find("source", "vid", "valid"))
    }

    private fun repository(scope: CoroutineScope): DataStorePlaybackProgressRepository =
        DataStorePlaybackProgressRepository(dataStore(scope))

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = {
                File(temporaryFolder.root, "${UUID.randomUUID()}.preferences_pb")
            },
        )

    private fun record(
        pid: String,
        updatedAtMs: Long,
    ) = PlaybackRecord(
        source = "source",
        vid = "vid",
        pid = pid,
        title = "title-$pid",
        episodeName = "episode-$pid",
        thumb = "thumb-$pid",
        positionMs = 10_000,
        durationMs = 100_000,
        completed = false,
        updatedAtMs = updatedAtMs,
    )
}
