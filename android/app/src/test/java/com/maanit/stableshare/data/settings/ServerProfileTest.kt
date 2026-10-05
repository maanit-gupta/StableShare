package com.maanit.stableshare.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfileTest {

    private val serverUrl = stringPreferencesKey("server_url")
    private val serverProfile = stringPreferencesKey("server_profile")
    private val lanHost = stringPreferencesKey("lan_host")
    private val lanPort = intPreferencesKey("lan_port")
    private val customUrl = stringPreferencesKey("custom_url")
    private val serverChosen = booleanPreferencesKey("server_chosen")
    private val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    private val migration = SettingsRepository.serverProfileMigration

    // ---- normalisation ----

    @Test
    fun normaliseTrimsStripsSlashAndAddsHttp() {
        assertEquals("http://192.168.1.20:8080", ServerProfiles.normalizeServerUrl("  192.168.1.20:8080/ "))
        assertEquals("https://example.com/base", ServerProfiles.normalizeServerUrl("https://example.com/base/"))
        assertEquals("https://example.com", ServerProfiles.normalizeServerUrl("HTTPS://Example.com"))
    }

    @Test
    fun normaliseRejectsSpacesMissingHostAndOtherSchemes() {
        assertNull(ServerProfiles.normalizeServerUrl(""))
        assertNull(ServerProfiles.normalizeServerUrl("   "))
        assertNull(ServerProfiles.normalizeServerUrl("my server:8080"))
        assertNull(ServerProfiles.normalizeServerUrl("http://host/a b"))
        assertNull(ServerProfiles.normalizeServerUrl("http://"))
        assertNull(ServerProfiles.normalizeServerUrl("http://:8080"))
        assertNull(ServerProfiles.normalizeServerUrl("ftp://host"))
        assertNull(ServerProfiles.normalizeServerUrl("http://h:8080/?x=1"))
    }

    @Test
    fun lanUrlNeedsABareHostAndAValidPort() {
        assertEquals("http://192.168.1.20:8080", ServerProfiles.lanUrl(" 192.168.1.20 ", 8080))
        assertEquals("http://laptop.local:3000", ServerProfiles.lanUrl("laptop.local", 3000))
        assertNull(ServerProfiles.lanUrl("", 8080))
        assertNull(ServerProfiles.lanUrl("192.168.1.20:8080", 8080))
        assertNull(ServerProfiles.lanUrl("http://192.168.1.20", 8080))
        assertNull(ServerProfiles.lanUrl("192.168.1.20", 0))
        assertNull(ServerProfiles.lanUrl("192.168.1.20", 70_000))
    }

    @Test
    fun privateRanges() {
        listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.1", "192.168.0.10").forEach {
            assertTrue(it, ServerProfiles.isPrivateIpv4(it))
        }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "8.8.8.8", "10.0.0", "10.0.0.256", "example.com").forEach {
            assertFalse(it, ServerProfiles.isPrivateIpv4(it))
        }
    }

    @Test
    fun urlOfEachProfile() {
        assertEquals(ServerProfiles.HOSTED_URL, ServerProfiles.urlOf(ServerChoice(ServerProfile.HOSTED)))
        assertEquals("http://10.0.2.2:8080", ServerProfiles.urlOf(ServerChoice(ServerProfile.EMULATOR)))
        assertEquals("http://192.168.1.5:8080", ServerProfiles.urlOf(ServerChoice(ServerProfile.LAN, lanHost = "192.168.1.5")))
        assertNull(ServerProfiles.urlOf(ServerChoice(ServerProfile.LAN)))
        assertEquals("https://x.dev", ServerProfiles.urlOf(ServerChoice(ServerProfile.CUSTOM, customUrl = "https://x.dev/")))
        assertNull(ServerProfiles.urlOf(ServerChoice(ServerProfile.CUSTOM)))
    }

    // ---- migration ----

    @Test
    fun hostedHostOverHttpBecomesHosted() = runBlocking {
        val out = migration.migrate(preferencesOf(serverUrl to "http://stableshare.onrender.com"))
        assertEquals("HOSTED", out[serverProfile])
        assertNull(out[serverUrl])
        assertEquals(true, out[serverChosen])
    }

    @Test
    fun emulatorAddressBecomesEmulator() = runBlocking {
        assertEquals("EMULATOR", migration.migrate(preferencesOf(serverUrl to "http://10.0.2.2:8080"))[serverProfile])
    }

    @Test
    fun emulatorHostOnAnotherPortKeepsItsPortAsLan() = runBlocking {
        val out = migration.migrate(preferencesOf(serverUrl to "http://10.0.2.2:3000"))
        assertEquals("LAN", out[serverProfile])
        assertEquals("10.0.2.2", out[lanHost])
        assertEquals(3000, out[lanPort])
    }

    @Test
    fun privateIpBecomesLan() = runBlocking {
        val out = migration.migrate(preferencesOf(serverUrl to "http://192.168.1.20:8080"))
        assertEquals("LAN", out[serverProfile])
        assertEquals("192.168.1.20", out[lanHost])
        assertEquals(8080, out[lanPort])
    }

    @Test
    fun anythingElseBecomesCustom() = runBlocking {
        val out = migration.migrate(preferencesOf(serverUrl to "https://my.server.dev/base"))
        assertEquals("CUSTOM", out[serverProfile])
        assertEquals("https://my.server.dev/base", out[customUrl])
        // https to a private address is not the plain http LAN shape.
        assertEquals("CUSTOM", migration.migrate(preferencesOf(serverUrl to "https://192.168.1.20"))[serverProfile])
    }

    @Test
    fun existingUserWithoutSavedUrlIsHostedAndChosen() = runBlocking {
        val out = migration.migrate(preferencesOf(onboardingCompleted to true))
        assertEquals("HOSTED", out[serverProfile])
        assertEquals(true, out[serverChosen])
    }

    @Test
    fun freshInstallIsHostedAndNotChosen() = runBlocking {
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val out = migration.migrate(emptyPreferences())
        assertEquals("HOSTED", out[serverProfile])
        assertEquals(false, out[serverChosen])
        assertFalse(migration.shouldMigrate(out))
    }
}
