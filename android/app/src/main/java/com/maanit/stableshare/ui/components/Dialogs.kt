package com.maanit.stableshare.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maanit.stableshare.R
import com.maanit.stableshare.ui.theme.Neutral

/** Every dialog is Neutral (UI-SPEC §5.4.1, §5.8.5): white card, heading title, body text, text buttons. */
@Composable
fun NeutralDialog(
    title: String,
    body: String,
    dismissLabel: String,
    confirmLabel: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Neutral.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        titleContentColor = c.inkPrimary,
        textContentColor = c.inkSecondary,
        title = { Text(title, style = Neutral.type.heading) },
        text = { Text(body, style = Neutral.type.body) },
        dismissButton = { NeutralTextButton(dismissLabel, onClick = onDismiss, color = c.inkPrimary) },
        confirmButton = {
            NeutralTextButton(confirmLabel, onClick = onConfirm, color = if (destructive) c.danger else c.inkPrimary)
        },
    )
}

/** "Cancel this transfer?" confirmation. */
@Composable
fun CancelTransferDialog(fileName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    NeutralDialog(
        title = stringResource(R.string.cancel_dialog_title),
        body = stringResource(R.string.cancel_dialog_body, fileName),
        dismissLabel = stringResource(R.string.cancel_dialog_keep),
        confirmLabel = stringResource(R.string.cancel_dialog_confirm),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}
