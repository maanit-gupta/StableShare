package com.maanit.stableshare.ui.screens

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.maanit.stableshare.StableShareApp
import com.maanit.stableshare.ui.LocalAppContainer
import com.maanit.stableshare.ui.LocalSnackbar
import com.maanit.stableshare.ui.upload.UploadScreen
import com.maanit.stableshare.ui.upload.UploadViewModel
import com.maanit.stableshare.ui.theme.StableShareTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** UI-SPEC §5.6: Upload stays disabled until a file is selected and fully generated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UploadScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun uploadIsDisabledUntilATestFileIsGenerated() {
        val container = ApplicationProvider.getApplicationContext<StableShareApp>().container
        val halfway = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val vm = UploadViewModel(
            files = container.fileStore,
            uploadUri = { container.transferController.uploadUri(it) },
            repo = container.transferRepository,
            progress = container.progressTracker.progress,
            restored = container.transferEngine.restoredIds,
            classifier = container.errorClassifier,
            appScope = container.applicationScope,
            generateFile = { mb, progress ->
                progress(mb * 1024L * 1024 / 2)
                halfway.complete(Unit)
                finish.await()
                File(tmp.root, "test-${mb}MB-1.bin").apply { writeBytes(ByteArray(16)) }
            },
        )
        compose.setContent {
            StableShareTheme(reducedMotion = true) {
                CompositionLocalProvider(LocalAppContainer provides container, LocalSnackbar provides SnackbarHostState()) {
                    UploadScreen(vm, onBack = {}, onOpenDetail = {}, perform = { _, _ -> })
                }
            }
        }
        compose.onNodeWithText("No file yet").assertExists()
        compose.onNodeWithTag("upload-button").assertIsNotEnabled()

        compose.onNodeWithText("50 MB").performScrollTo().performClick()
        compose.waitUntil(5_000) { halfway.isCompleted }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Generating test file, 50%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upload-button").assertIsNotEnabled()

        finish.complete(Unit)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("test-50MB-1.bin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ready when you are").assertExists()
        compose.onNodeWithTag("upload-button").assertIsEnabled()
    }
}
