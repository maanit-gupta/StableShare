package com.maanit.stableshare.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maanit.stableshare.data.db.TransferEntity

/** Phase 2 placeholder: lists transfers straight from Room and shows server health. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersPlaceholderScreen(viewModel: HomeViewModel) {
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("StableShare") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ServerStatusCard(serverUrl, status, onRetry = viewModel::checkServer)
            HorizontalDivider()
            if (transfers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No transfers yet", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(transfers, key = { it.id }) { TransferRow(it) }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(url: String, status: ServerStatus, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Server: $url", style = MaterialTheme.typography.bodyMedium)
                val text = when (status) {
                    ServerStatus.Checking -> "Checking…"
                    ServerStatus.Reachable -> "Server: OK"
                    is ServerStatus.Unreachable -> "Unreachable: ${status.reason}"
                }
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun TransferRow(t: TransferEntity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${t.type} · ${t.fileName}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${t.state} · ${t.bytesDone} / ${t.fileSize} bytes" + (t.errorCode?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
