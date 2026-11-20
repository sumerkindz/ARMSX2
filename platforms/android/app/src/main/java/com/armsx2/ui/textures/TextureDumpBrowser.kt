package com.armsx2.ui.textures

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.armsx2.EmuState
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.SectionTitle

/**
 * Browse textures dumped by PCSX2's existing GS replacement dumper.
 *
 * A replacement must keep the dump's exact filename: that filename is the GS texture hash used by
 * the native replacement map. Import therefore converts the selected image to PNG but never
 * renames the hash.
 */
@Composable
fun TextureDumpBrowser(state: TextureManagerUiState, viewModel: TextureManagerViewModel) {
    var previewItem by remember { mutableStateOf<TextureDumpItem?>(null) }
    var exportItem by remember { mutableStateOf<TextureDumpItem?>(null) }
    var replacementItem by remember { mutableStateOf<TextureDumpItem?>(null) }
    var confirmReplacement by remember { mutableStateOf<TextureDumpItem?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val item = exportItem
        if (uri != null && item != null) viewModel.exportDump(uri, item)
        exportItem = null
    }
    val replacementLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val item = replacementItem
        if (uri != null && item != null) viewModel.importReplacement(uri, item)
        replacementItem = null
    }

    val canDump = MainActivityRuntime.nativeReady.value &&
        MainActivityRuntime.eState.value != EmuState.STOPPED

    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Texture Studio · Dump Browser", state.dumpTotalCount.toString())
            Text(
                "Game folder: ${state.dumpSerial ?: state.activeSerial ?: "No game selected"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Enable dumping, return to the game and play. Come back here and refresh to browse the unique textures captured by the emulator.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = viewModel::toggleTextureDumping,
                    enabled = canDump,
                ) {
                    Text(if (state.dumpEnabled) "Stop dumping" else "Start dumping")
                }
                TextButton(onClick = viewModel::refreshDumps) {
                    Text("Refresh")
                }
                if (state.dumpBusy) CircularProgressIndicator()
            }
            Text(
                if (state.dumpEnabled) "Dumping is ON" else "Dumping is OFF",
                color = if (state.dumpEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (!canDump) {
                Text(
                    "Start a PS2 game to enable or disable live texture dumping. Previously dumped files remain browsable below when a game folder is selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.dumpItems.isEmpty()) {
                Text(
                    if (state.dumpSerial == null) {
                        "No game serial is available yet."
                    } else {
                        "No PNG dumps found for ${state.dumpSerial}. Start dumping, play a section of the game, then press Refresh."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(
                    "Showing ${state.dumpItems.size} of ${state.dumpTotalCount} dumped textures",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.dumpItems, key = { it.file.name }) { item ->
                        Surface(
                            modifier = Modifier.width(188.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AsyncImage(
                                    model = item.file,
                                    contentDescription = item.file.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(112.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { previewItem = item },
                                )
                                Text(
                                    item.file.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${item.width} × ${item.height} · ${textureDumpHumanSize(item.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    TextButton(onClick = {
                                        exportItem = item
                                        exportLauncher.launch(item.file.name)
                                    }) { Text("Export") }
                                    TextButton(onClick = { confirmReplacement = item }) { Text("Replace") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewItem?.let { item ->
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text("Texture preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = item.file,
                        contentDescription = item.file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    )
                    Text(item.file.name, style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${item.width} × ${item.height} · ${textureDumpHumanSize(item.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportItem = item
                    previewItem = null
                    exportLauncher.launch(item.file.name)
                }) { Text("Export PNG") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmReplacement = item
                        previewItem = null
                    }) { Text("Replace") }
                    TextButton(onClick = { previewItem = null }) { Text("Close") }
                }
            },
        )
    }

    confirmReplacement?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmReplacement = null },
            title = { Text("Replace this texture?") },
            text = {
                Text(
                    "Choose an edited image. It will be converted to PNG and saved as the exact dump filename ${item.file.name} in this game's replacements folder. An existing replacement with that filename will be overwritten.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    replacementItem = item
                    confirmReplacement = null
                    replacementLauncher.launch(arrayOf("image/*"))
                }) { Text("Choose image") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplacement = null }) { Text("Cancel") }
            },
        )
    }
}

private fun textureDumpHumanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
