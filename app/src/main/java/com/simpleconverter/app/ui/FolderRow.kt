package com.simpleconverter.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simpleconverter.app.R
import com.simpleconverter.app.convert.OutputStore
import com.simpleconverter.app.model.MediaKind

/**
 * Zeigt, wohin Dateien gespeichert werden, mit „Ändern“ (Ordnerauswahl des Systems)
 * und „Standard“ (zurück zum Standardordner).
 *
 * @param isDefault true, wenn [folder] nur der Standard ist (beim Umwandeln: nichts eigenes gewählt).
 */
@Composable
fun FolderRow(
    title: String,
    folder: String?,
    kind: MediaKind,
    onPick: (Uri?) -> Unit,
    isDefault: Boolean = folder == null,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onPick(uri)
    }
    val path = OutputStore.folderLabel(folder) ?: OutputStore.defaultFolderLabel(kind)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                if (isDefault) stringResource(R.string.folder_default_suffix, path) else path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isDefault) {
            TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.folder_reset)) }
        }
        TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.folder_change)) }
    }
}
