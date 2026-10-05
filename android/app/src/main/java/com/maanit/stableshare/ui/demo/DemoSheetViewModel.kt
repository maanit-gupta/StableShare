package com.maanit.stableshare.ui.demo

import android.util.Log
import androidx.lifecycle.ViewModel
import com.maanit.stableshare.R
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.data.net.ErrorClassifier
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.engine.DemoController
import com.maanit.stableshare.engine.DemoKind
import com.maanit.stableshare.engine.DemoProgress
import com.maanit.stableshare.engine.NoSampleException
import com.maanit.stableshare.ui.components.MAX_TRIES
import com.maanit.stableshare.ui.model.ErrorCopy
import com.maanit.stableshare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * "Try a demo" sheet. The demo runs in the application scope, so closing the sheet never stops
 * it; [started] tells an open sheet to close once the transfer exists.
 */
class DemoSheetViewModel(
    private val run: suspend (DemoKind) -> TransferEntity?,
    /** The running demo, shared by every entry point. */
    val progress: StateFlow<DemoProgress?>,
    private val classifier: ErrorClassifier,
    private val appScope: CoroutineScope,
) : ViewModel() {

    /** Not replayed: a demo that started while the sheet was closed never closes it later. */
    private val startedIds = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val started: Flow<String> = startedIds.asSharedFlow()

    private val failures = Channel<UiText>(Channel.BUFFERED)
    val failureMessages: Flow<UiText> = failures.receiveAsFlow()

    fun start(kind: DemoKind) {
        if (progress.value != null) return
        appScope.launch {
            try {
                run(kind)?.let { startedIds.tryEmit(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "demo $kind failed: $e")
                failures.trySend(message(kind, e))
            }
        }
    }

    private fun message(kind: DemoKind, e: Exception): UiText {
        if (e is NoSampleException) return UiText.res(R.string.download_empty)
        val name = if (kind == DemoKind.DOWNLOAD) DemoController.SAMPLE_FILE_ID else "${kind.sizeMb} MB"
        val code = runCatching { classifier.classify(e).code }.getOrDefault(ErrorCode.UNKNOWN)
        return UiText.res(R.string.couldnt_add, name, ErrorCopy.short(code, MAX_TRIES))
    }

    private companion object {
        const val TAG = "StableShare"
    }
}
