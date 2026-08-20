package com.airplay.tv.feature.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStorePlaybackProgressRepositoryTest {
    private val repositories = mutableListOf<DataStorePlaybackProgressRepository>()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun closeRepositories() {
        repositories.forEach(DataStorePlaybackProgressRepository::close)
    }

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
    fun queuedRevisionPersistsAcrossRepositoryInstancesAndClockRollback() = runTest {
        val dataStore = dataStore(backgroundScope)
        val firstRepository = track(DataStorePlaybackProgressRepository(
            dataStore = dataStore,
            persistenceScope = backgroundScope,
        ))
        firstRepository.enqueueSave(record(pid = "first", updatedAtMs = 100))
        firstRepository.enqueueSave(record(pid = "second", updatedAtMs = 100))
        firstRepository.drain()

        val secondRepository = track(DataStorePlaybackProgressRepository(
            dataStore = dataStore,
            persistenceScope = backgroundScope,
        ))
        secondRepository.enqueueSave(record(pid = "third", updatedAtMs = 90))
        secondRepository.drain()

        assertEquals(1L, firstRepository.find("source", "vid", "first")?.revision)
        assertEquals(2L, firstRepository.find("source", "vid", "second")?.revision)
        assertEquals(3L, secondRepository.find("source", "vid", "third")?.revision)
        assertEquals(90L, secondRepository.latest()?.updatedAtMs)
        assertEquals("third", secondRepository.latest()?.pid)
    }

    @Test
    fun legacyJsonWithoutRevisionMigratesIntoQueuedRevisionOrdering() = runTest {
        val dataStore = dataStore(backgroundScope)
        val legacyKeyName = playbackRecordKey("source", "vid", "legacy")
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey(legacyKeyName)] =
                """{"source":"source","vid":"vid","pid":"legacy","title":"Legacy title","episodeName":"Legacy episode","thumb":"legacy-thumb","positionMs":10000,"durationMs":100000,"completed":false,"updatedAtMs":100}"""
            preferences[stringPreferencesKey("latest_record_key")] = legacyKeyName
        }
        val repository = track(DataStorePlaybackProgressRepository(
            dataStore = dataStore,
            persistenceScope = backgroundScope,
        ))

        val legacy = checkNotNull(repository.find("source", "vid", "legacy"))
        repository.enqueueSave(record(pid = "new", updatedAtMs = 90))
        repository.drain()

        assertEquals(0L, legacy.revision)
        assertEquals(1L, repository.find("source", "vid", "new")?.revision)
        assertEquals("new", repository.latest()?.pid)
    }

    @Test
    fun olderSaveForSameKeyDoesNotOverwriteRecordOrLatest() = runTest {
        val repository = repository(backgroundScope)
        val newer = record(pid = "same", updatedAtMs = 20).copy(positionMs = 20_000)
        val older = record(pid = "same", updatedAtMs = 19).copy(positionMs = 19_000)
        repository.save(newer)

        repository.save(older)

        assertEquals(newer, repository.find("source", "vid", "same"))
        assertEquals(newer, repository.latest())
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
        val repository = track(DataStorePlaybackProgressRepository(dataStore))
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

    @Test
    fun drainReportsFailureFromPreviouslyQueuedSave() = runTest {
        val repository = track(DataStorePlaybackProgressRepository(
            dataStore = FailingUpdateDataStore(dataStore(backgroundScope)),
            persistenceScope = backgroundScope,
        ))
        repository.enqueueSave(record(pid = "failed", updatedAtMs = 1))

        val failure = runCatching { repository.drain() }.exceptionOrNull()
        val enqueueAfterFailure = runCatching {
            repository.enqueueSave(record(pid = "rejected", updatedAtMs = 2))
        }.exceptionOrNull()
        val drainAfterFailure = runCatching { repository.drain() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("save failed", failure?.message)
        assertTrue(enqueueAfterFailure is IOException)
        assertEquals("save failed", enqueueAfterFailure?.message)
        assertTrue(drainAfterFailure is IOException)
        assertEquals("save failed", drainAfterFailure?.message)
    }

    @Test
    fun closeIsIdempotentAndPostCloseOperationsFailFastWithoutOwningInjectedScope() = runTest {
        val externalJob = SupervisorJob()
        val externalScope = CoroutineScope(externalJob + StandardTestDispatcher(testScheduler))
        val repository = track(DataStorePlaybackProgressRepository(
            dataStore = dataStore(backgroundScope),
            persistenceScope = externalScope,
        ))

        repository.close()
        repository.close()
        val enqueueFailure = runCatching {
            repository.enqueueSave(record(pid = "closed", updatedAtMs = 1))
        }.exceptionOrNull()
        val drainFailure = runCatching { repository.drain() }.exceptionOrNull()

        assertTrue(enqueueFailure is IllegalStateException)
        assertEquals("Playback progress repository is closed", enqueueFailure?.message)
        assertTrue(drainFailure is IllegalStateException)
        assertEquals("Playback progress repository is closed", drainFailure?.message)
        assertTrue(externalJob.isActive)
        externalScope.cancel()
    }

    private fun repository(scope: CoroutineScope): DataStorePlaybackProgressRepository =
        track(DataStorePlaybackProgressRepository(dataStore(scope)))

    private fun track(
        repository: DataStorePlaybackProgressRepository,
    ): DataStorePlaybackProgressRepository = repository.also(repositories::add)

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

    private class FailingUpdateDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        override val data = delegate.data

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = throw IOException("save failed")
    }
}
