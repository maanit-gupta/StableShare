package com.maanit.stableshare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository
    private val mib = 1024 * 1024

    @Before
    fun setUp() {
        store = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaults() = runBlocking {
        assertEquals(Settings("http://10.0.2.2:8080", 2, 2 * mib, true, onboardingCompleted = false), repo.current())
    }

    @Test
    fun onboardingFlagPersistsAndCanBeReset() = runBlocking {
        repo.setOnboardingCompleted(true)
        assertTrue(SettingsRepository(store).current().onboardingCompleted)
        repo.setOnboardingCompleted(false)
        assertFalse(repo.current().onboardingCompleted)
    }

    @Test
    fun maxConcurrentIsClampedToOneThroughFour() = runBlocking {
        repo.setMaxConcurrent(0)
        assertEquals(1, repo.current().maxConcurrent)
        repo.setMaxConcurrent(9)
        assertEquals(4, repo.current().maxConcurrent)
        repo.setMaxConcurrent(3)
        assertEquals(3, repo.current().maxConcurrent)
    }

    @Test
    fun chunkSizeMustBeOneTwoOrFiveMib() = runBlocking {
        repo.setUploadChunkSizeBytes(5 * mib)
        assertEquals(5 * mib, repo.current().uploadChunkSizeBytes)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.setUploadChunkSizeBytes(3 * mib) } }
        assertEquals(5 * mib, repo.current().uploadChunkSizeBytes)
    }

    @Test
    fun serverUrlIsValidatedAndNormalised() = runBlocking {
        assertTrue(repo.setServerUrl("  192.168.1.20:8080/ "))
        assertEquals("http://192.168.1.20:8080", repo.current().serverUrl)
        assertFalse(repo.setServerUrl("not a url ::"))
        assertFalse(repo.setServerUrl(""))
        assertFalse(repo.setServerUrl("ftp://host"))
        assertEquals("http://192.168.1.20:8080", repo.current().serverUrl)
        assertEquals("https://example.com/base", SettingsRepository.normalizeServerUrl("https://example.com/base/"))
        assertNull(SettingsRepository.normalizeServerUrl("http://h:8080/?x=1"))
    }

    @Test
    fun corruptStoredValuesFallBackToSafeValues() = runBlocking {
        store.edit {
            it[intPreferencesKey("max_concurrent")] = 99
            it[intPreferencesKey("upload_chunk_size_bytes")] = 12345
            it[stringPreferencesKey("server_url")] = ":::"
        }
        assertEquals(Settings(maxConcurrent = 4), repo.current())
    }

    @Test
    fun autoRetryToggleAndFlowEmissions() = runBlocking {
        repo.settings.test {
            assertTrue(awaitItem().autoRetryEnabled)
            repo.setAutoRetryEnabled(false)
            assertFalse(awaitItem().autoRetryEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun wifiOnlyIsOffByDefaultAndPersists() = runBlocking {
        assertFalse(repo.current().wifiOnly)
        repo.settings.test {
            assertFalse(awaitItem().wifiOnly)
            repo.setWifiOnly(true)
            assertTrue(awaitItem().wifiOnly)
            cancelAndIgnoreRemainingEvents()
        }
        // A second repository over the same store (a new process) reads it back.
        assertTrue(SettingsRepository(store).current().wifiOnly)
        repo.setWifiOnly(false)
        assertFalse(repo.current().wifiOnly)
    }
}
