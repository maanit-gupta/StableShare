package com.maanit.stableshare.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.data.db.TransferEntity
import com.maanit.stableshare.domain.ErrorCode
import com.maanit.stableshare.domain.StateMachine
import com.maanit.stableshare.domain.TransferAction
import com.maanit.stableshare.domain.TransferState
import com.maanit.stableshare.engine.TransferPhase
import kotlinx.coroutines.delay
import java.util.Locale

private const val MIB = 1024.0 * 1024.0

/** Temporary Phase 3 screen: drives and observes the engine. Replaced by the real UI in Phase 4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(viewModel: DebugViewModel, localNetworkDenied: Boolean, onRequestLocalNetwork: () -> Unit) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val now = rememberNow()

    Scaffold(topBar = { TopAppBar(title = { Text("StableShare · debug") }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ServerCard(serverUrl, status, onRefresh = viewModel::refresh)
            }
            if (localNetworkDenied) {
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Text("Local network access is denied: transfers cannot reach the server.", Modifier.weight(1f))
                            TextButton(onClick = onRequestLocalNetwork) { Text("Allow") }
                        }
                    }
                }
            }
            item {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 200, 500).forEach { mb ->
                        Button(onClick = { viewModel.generateAndUpload(mb) }) { Text("Upload $mb MB") }
                    }
                }
                message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
            }
            if (files.isNotEmpty()) {
                item { SectionTitle("Server files") }
                items(files, key = { "file-" + it.fileId }) { file ->
                    OutlinedButton(
                        onClick = { viewModel.download(file) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Download ${file.name} (${mb(file.size)} MB)") }
                }
            }
            item { SectionTitle("Transfers (${transfers.size})") }
            items(transfers, key = { it.transfer.id }) { row ->
                TransferItem(row, now, onAction = { viewModel.perform(row.transfer.id, it) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ServerCard(url: String, status: ServerStatus, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Server: $url", style = MaterialTheme.typography.bodyMedium)
                val text = when (status) {
                    ServerStatus.Checking -> "Checking…"
                    ServerStatus.Reachable -> "Server: OK"
                    is ServerStatus.Unreachable -> "Unreachable: ${status.reason}"
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun TransferItem(row: TransferRow, now: Long, onAction: (TransferAction) -> Unit) {
    val t = row.transfer
    val live = row.live
    val bytes = live?.bytes ?: t.bytesDone
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${t.type} · ${t.fileName}", style = MaterialTheme.typography.bodyLarge)
        Text("${t.state} · ${phaseText(t, live?.phase, now)}", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(
            progress = { if (t.fileSize == 0L) (if (t.state == TransferState.COMPLETED) 1f else 0f) else bytes.toFloat() / t.fileSize },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        val speed = live?.takeIf { it.bytesPerSecond > 0 }?.let {
            " · %.1f MB/s".format(Locale.US, it.bytesPerSecond / MIB) + (it.etaSeconds?.let { s -> " · ETA ${s}s" } ?: "")
        } ?: ""
        Text("${mb(bytes)} / ${mb(t.fileSize)} MB · attempts ${t.attemptCount}$speed", style = MaterialTheme.typography.bodySmall)
        if (t.errorCode != null || t.errorMessage != null) {
            Text(
                "${t.errorCode ?: ""} ${t.errorMessage ?: ""}".trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateMachine.allowedActions(t.state).forEach { action ->
                TextButton(onClick = { onAction(action) }) { Text(action.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
    }
}

private fun phaseText(t: TransferEntity, phase: TransferPhase?, now: Long): String = when (phase) {
    is TransferPhase.Preparing -> "Preparing (${mb(phase.hashedBytes)} MB hashed)"
    TransferPhase.Transferring -> "Transferring"
    is TransferPhase.Verifying -> if (phase.hashedBytes > 0) "Verifying (${mb(phase.hashedBytes)} MB)" else "Verifying"
    TransferPhase.WaitingForNetwork -> "Waiting for network"
    is TransferPhase.Retrying -> "Retrying in ${((phase.atMs - now).coerceAtLeast(0) + 999) / 1000}s"
    null -> when {
        t.state == TransferState.RETRYING && t.errorCode == ErrorCode.NETWORK_UNAVAILABLE -> "Waiting for network"
        t.state == TransferState.RETRYING && t.nextRetryAt != null ->
            "Retrying in ${((t.nextRetryAt - now).coerceAtLeast(0) + 999) / 1000}s"
        else -> "—"
    }
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / MIB)

/** Wall-clock time that ticks once a second, for "Retrying in Ns". */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}
