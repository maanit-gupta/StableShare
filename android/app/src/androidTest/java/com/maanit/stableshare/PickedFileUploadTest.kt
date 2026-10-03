package com.maanit.stableshare

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.maanit.stableshare.data.files.FileStore
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.domain.TransferType
import com.maanit.stableshare.ui.LocalNetworkPermission
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * End to end through the Storage Access Framework (UI-SPEC §5.6): a file in Downloads is picked
 * in the system picker, its content:// URI keeps a persisted read grant, and the upload reaches
 * COMPLETED with the server-verified hash. Needs the mock server on the host (10.0.2.2:8080).
 */
@RunWith(AndroidJUnit4::class)
class PickedFileUploadTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = compose.activity.application as StableShareApp
    private val name = "stableshare-picked-${System.currentTimeMillis()}.bin"
    private val bytes = Random(42).nextBytes(3 * 1024 * 1024 + 123)
    private var mediaUri: Uri? = null

    @Before
    fun setUp() {
        if (LocalNetworkPermission.isRequired()) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, LocalNetworkPermission.PERMISSION)
        }
        runBlocking { app.container.settingsRepository.setOnboardingCompleted(true) }
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        mediaUri = uri
    }

    @After
    fun tearDown() {
        mediaUri?.let { instrumentation.targetContext.contentResolver.delete(it, null, null) }
    }

    @Test
    fun pickedFileUploadsAndVerifies() {
        // Transfers → Upload screen.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("New transfer").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Nothing in the air").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Nothing in the air").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Upload").performClick()
        } else {
            compose.onNodeWithContentDescription("New transfer").performClick()
            compose.onNodeWithText("Upload a file").performClick()
        }
        compose.onNodeWithText("Choose a file").performScrollTo().performClick()

        // The system picker: the new file is the most recent one.
        val file = device.wait(Until.findObject(By.text(name)), 20_000)
            ?: error("$name not shown in the picker")
        file.click()

        compose.waitUntil(15_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ready when you are").assertExists()
        compose.onNodeWithTag("upload-button").performClick()

        val repo = app.container.transferRepository
        compose.waitUntil(60_000) {
            runBlocking { repo.getInStates(TransferState.COMPLETED).any { it.fileName == name } }
        }
        val row = runBlocking { repo.getInStates(TransferState.COMPLETED).single { it.fileName == name } }
        assertEquals(TransferType.UPLOAD, row.type)
        assertEquals("content", Uri.parse(row.localUri).scheme)
        assertEquals(FileStore.sha256Hex(bytes), row.sha256)
        assertEquals(bytes.size.toLong(), row.fileSize)
        val grants = instrumentation.targetContext.contentResolver.persistedUriPermissions
        assertTrue("persisted read grant for ${row.localUri}", grants.any { it.uri.toString() == row.localUri && it.isReadPermission })
    }
}
