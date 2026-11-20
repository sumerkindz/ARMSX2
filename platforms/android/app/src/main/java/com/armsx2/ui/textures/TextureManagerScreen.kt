package com.armsx2.ui.textures

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.armsx2.ui.Colors
import com.armsx2.runtime.MainActivityRuntime
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.EmptyState
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.common.SectionTitle
import com.armsx2.ui.common.SettingSwitchRow
import com.armsx2.ui.settings.controllerFocusable

@Composable
fun TextureManagerScreen(onBack: () -> Unit, viewModel: TextureManagerViewModel = viewModel()) {
    val state = viewModel.state.value
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::importFolder) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importArchive) }
    val pendingReplacement = remember { androidx.compose.runtime.mutableStateOf<TextureDumpItem?>(null) }
    val pendingExport = remember { androidx.compose.runtime.mutableStateOf<TextureDumpItem?>(null) }
    val pendingPackExport = remember { androidx.compose.runtime.mutableStateOf<TexturePackItem?>(null) }
    val replacementImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selectedDump = pendingReplacement.value
        if (uri != null && selectedDump != null) viewModel.importReplacementImage(selectedDump, uri)
        pendingReplacement.value = null
    }
    val dumpExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val selectedDump = pendingExport.value
        if (uri != null && selectedDump != null) viewModel.exportDump(selectedDump, uri)
        pendingExport.value = null
    }
    val packExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val selectedPack = pendingPackExport.value
        if (uri != null && selectedPack != null) viewModel.exportPack(selectedPack, uri)
        pendingPackExport.value = null
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Options on the left, packs on the right: the controller keeps to a column and crosses with
    // Left/Right.
    com.armsx2.ui.settings.ColumnControllerNav()
    // The texture screen has its own music while it is open, in the library music's place.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.armsx2.LibraryMusic.playTheme(context, com.armsx2.R.raw.texture_packs_music)
        onDispose { com.armsx2.LibraryMusic.endTheme(context, com.armsx2.R.raw.texture_packs_music) }
    }

    ArmsBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ArmsTopBar(
                title = str("renderer.section.texturePacks"),
                subtitle = state.activeSerial ?: str("games.info.perGameSettings.body"),
                // Controller-reachable, so Up from the first control scrolls back to the top of the screen.
                leading = { RoundAction("←", str("action.back"), onBack, controllerId = "textureMgr.top.back") },
                actions = {
                    // Providers label .tar.zst inconsistently (application/zstd, octet-stream, or
                    // nothing), so offer every file and let the installer read the format.
                    RoundAction("＋", str("action.import"), { archivePicker.launch(arrayOf("*/*")) }, controllerId = "textureMgr.top.import")
                    RoundAction("🗀", str("action.importFolder"), { folderPicker.launch(null) }, controllerId = "textureMgr.top.folder")
                    RoundAction("↻", str("games.card.refresh"), viewModel::refresh, controllerId = "textureMgr.top.refresh")
                },
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 820.dp
                if (compact) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        TextureOptions(state, viewModel, Modifier.fillMaxWidth())
                        Spacer(Modifier.padding(top = 10.dp))
                        TextureOnlineSection(catalogSerial(state), librarySerials(state), ownedSerials(state), Modifier.fillMaxWidth()) {
                            viewModel.onPackInstalled()
                        }
                        Spacer(Modifier.padding(top = 10.dp))
                        TexturePacks(
                            state,
                            viewModel,
                            Modifier.fillMaxWidth(),
                            onBack,
                            onExportPack = { pack ->
                                pendingPackExport.value = pack
                                packExportPicker.launch(pack.serial + ".zip")
                            },
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TextureOptions(state, viewModel, Modifier.width(310.dp))
                        Column(Modifier.weight(1f)) {
                            TextureOnlineSection(catalogSerial(state), librarySerials(state), ownedSerials(state), Modifier.fillMaxWidth()) {
                                viewModel.onPackInstalled()
                            }
                            Spacer(Modifier.padding(top = 10.dp))
                            TexturePacks(
                                state,
                                viewModel,
                                Modifier.fillMaxWidth(),
                                onBack,
                                onExportPack = { pack ->
                                    pendingPackExport.value = pack
                                    packExportPicker.launch(pack.serial + ".zip")
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextureDumpBrowser(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                onExportImage = { item ->
                    pendingExport.value = item
                    dumpExportPicker.launch(item.file.name)
                },
                onImportImage = { item ->
                    pendingReplacement.value = item
                    replacementImagePicker.launch(arrayOf("image/png"))
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    (state.error ?: state.message)?.let { message ->
        com.armsx2.ui.common.NotifyOverlay(
            title = if (state.error == null) str("action.ok") else str("renderer.section.texturePacks"),
            message = message,
            onDismiss = viewModel::dismissMessage,
            idPrefix = "textures.message",
        )
    }
}

@Composable
private fun TextureDumpBrowser(
    state: TextureManagerUiState,
    viewModel: TextureManagerViewModel,
    modifier: Modifier,
    onExportImage: (TextureDumpItem) -> Unit,
    onImportImage: (TextureDumpItem) -> Unit,
) {
    val query = remember { androidx.compose.runtime.mutableStateOf("") }
    val onlyWithoutReplacement = remember { androidx.compose.runtime.mutableStateOf(false) }
    val selectedGame = state.dumpGames.firstOrNull {
        it.serial.equals(state.selectedDumpSerial, ignoreCase = true)
    }
    val visibleDumps = state.dumps.filter { item ->
        item.file.name.contains(query.value.trim(), ignoreCase = true) &&
            (!onlyWithoutReplacement.value || !item.replacementExists)
    }
    GlassPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionTitle(str("textures.dump.title"), selectedGame?.gameTitle ?: selectedGame?.serial ?: str("textures.dump.noSaved"))
                TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text(str("textures.dump.refresh")) }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = viewModel::toggleTextureDumping,
                    enabled = state.activeSerial != null &&
                        MainActivityRuntime.eState.value != com.armsx2.EmuState.STOPPED &&
                        !state.busy,
                ) {
                    Text(
                        when (state.dumpingEnabled) {
                            true -> str("textures.dump.stop")
                            false -> str("textures.dump.start")
                            null -> str("textures.dump.toggle")
                        },
                    )
                }
                Text(
                    when (state.dumpingEnabled) {
                        true -> str("textures.dump.on")
                        false -> str("textures.dump.off")
                        null -> str("textures.dump.unknown")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                str("textures.dump.instructions"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.dumpGames.isEmpty()) {
                Text(
                    if (state.activeSerial != null) {
                        str("textures.dump.noneForGame").format(state.activeSerial)
                    } else {
                        str("textures.dump.noneGeneral")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Dumps remain available after a game is closed. Choose any game folder that has
                // previously captured PNGs; the runtime toggle above still targets the live game.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    state.dumpGames.forEach { game ->
                        FilterChip(
                            selected = game.serial.equals(state.selectedDumpSerial, ignoreCase = true),
                            onClick = { viewModel.selectDumpGame(game.serial) },
                            label = { Text("${game.gameTitle ?: game.serial} (${game.fileCount})") },
                        )
                    }
                }
                selectedGame?.let { game ->
                    Text(
                        "${game.serial} · ${game.fileCount} saved texture(s) · ${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, game.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.dumps.isEmpty()) {
                    Text(
                        if (state.loadingDumps) str("textures.dump.loading")
                        else str("textures.dump.folderEmpty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = query.value,
                        onValueChange = { query.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(str("textures.dump.search")) },
                    )
                    TextButton(onClick = { onlyWithoutReplacement.value = !onlyWithoutReplacement.value }) {
                        Text(if (onlyWithoutReplacement.value) str("textures.dump.showAll") else str("textures.dump.onlyUnreplaced"))
                    }
                    if (visibleDumps.isEmpty()) {
                        Text(
                            str("textures.dump.noMatch"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            str("textures.dump.shown").format(visibleDumps.size, selectedGame?.fileCount ?: state.dumps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(440.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            items(visibleDumps, key = { it.file.name }) { item ->
                                TextureDumpRow(
                                    item = item,
                                    enabled = !state.busy,
                                    onExport = { onExportImage(item) },
                                    onImport = { onImportImage(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextureDumpRow(
    item: TextureDumpItem,
    enabled: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextureDumpThumbnail(item.file)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.file.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.serial} · ${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, item.file.length())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (item.replacementExists) str("textures.dump.replacementExists") else str("textures.dump.noReplacement"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.replacementExists) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onExport, enabled = enabled) { Text(str("textures.dump.export")) }
                TextButton(onClick = onImport, enabled = enabled) {
                    Text(if (item.replacementExists) str("textures.dump.importUpdate") else str("textures.dump.import"))
                }
            }
        }
    }
}

@Composable
private fun TextureDumpThumbnail(file: java.io.File) {
    val thumbnail = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.absolutePath, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            val sampleSize = if (longestSide > 256) {
                Integer.highestOneBit(longestSide / 256).coerceAtLeast(1)
            } else {
                1
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
        }
    }
    Surface(
        modifier = Modifier.size(76.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (thumbnail.value != null) {
            Image(
                bitmap = thumbnail.value!!,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("PNG", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TextureOptions(state: TextureManagerUiState, viewModel: TextureManagerViewModel, modifier: Modifier) {
    GlassPanel(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SectionTitle(str("renderer.section.texturePacks"), str("scope.global"))
            SettingSwitchRow(
                str("renderer.loadTexturePacks.label"),
                str("renderer.loadTexturePacks.description"),
                state.settings.graphics.loadTextureReplacements,
                onCheckedChange = { value -> viewModel.update { it.copy(graphics = it.graphics.copy(loadTextureReplacements = value)) } },
                modifier = Modifier.controllerFocusable(
                    "textureMgr.opt.loadReplacements",
                    RoundedCornerShape(22.dp),
                    onConfirm = { viewModel.update { it.copy(graphics = it.graphics.copy(loadTextureReplacements = !state.settings.graphics.loadTextureReplacements)) } },
                ),
            )
            SettingSwitchRow(
                str("renderer.asyncTextureLoading.label"),
                str("renderer.asyncTextureLoading.description"),
                state.settings.graphics.loadTextureReplacementsAsync,
                onCheckedChange = { value -> viewModel.update { it.copy(graphics = it.graphics.copy(loadTextureReplacementsAsync = value)) } },
                modifier = Modifier.controllerFocusable(
                    "textureMgr.opt.asyncLoading",
                    RoundedCornerShape(22.dp),
                    onConfirm = { viewModel.update { it.copy(graphics = it.graphics.copy(loadTextureReplacementsAsync = !state.settings.graphics.loadTextureReplacementsAsync)) } },
                ),
            )
            SettingSwitchRow(
                str("renderer.precacheTexturePacks.label"),
                str("renderer.precacheTexturePacks.description"),
                state.settings.graphics.precacheTextureReplacements,
                onCheckedChange = { value -> viewModel.update { it.copy(graphics = it.graphics.copy(precacheTextureReplacements = value)) } },
                modifier = Modifier.controllerFocusable(
                    "textureMgr.opt.precache",
                    RoundedCornerShape(22.dp),
                    onConfirm = { viewModel.update { it.copy(graphics = it.graphics.copy(precacheTextureReplacements = !state.settings.graphics.precacheTextureReplacements)) } },
                ),
            )
        }
    }
}

@Composable
private fun TexturePacks(
    state: TextureManagerUiState,
    viewModel: TextureManagerViewModel,
    modifier: Modifier,
    onBack: () -> Unit,
    onExportPack: (TexturePackItem) -> Unit,
) {
    Column(modifier) {
        SectionTitle(str("renderer.section.texturePacks"), state.packs.size.toString())
        // A pack is thousands of files over SAF, so show the running count next to the
        // spinner — a bare spinner on a multi-minute copy is indistinguishable from a hang.
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                if (state.progress > 0) Text(str("renderer.texturePacks.copying").format(state.progress))
            }
        }
        // Name the folder the CORE will actually scan. A pack only applies if its folder
        // matches this exactly, and for a raw .ELF boot that is the ELF's filename rather
        // than the disc serial — a mismatch that is otherwise completely invisible and
        // looks identical to "texture packs are broken".
        state.activeSerial?.takeIf(String::isNotBlank)?.let { serial ->
            val matched = state.packs.any { it.serial.equals(serial, ignoreCase = true) }
            Text(
                str("renderer.texturePacks.activeSerial").format(serial),
                color = if (matched) MaterialTheme.colorScheme.onSurfaceVariant else Colors.pasx2_blue,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            if (!matched && state.packs.isNotEmpty()) {
                Text(
                    str("renderer.texturePacks.serialMismatch").format(serial),
                    color = Colors.pasx2_blue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (state.packs.isEmpty()) {
            EmptyState(
                str("renderer.section.texturePacks"),
                str("renderer.loadTexturePacks.description"),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(220.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.packs.forEach { pack ->
                    TexturePackRow(
                        pack = pack,
                        enabled = !state.busy,
                        onExport = { onExportPack(pack) },
                        onDelete = { viewModel.delete(pack) },
                    )
                }
            }
        }
        // The replacement map is only built in GSTextureReplacements::Initialize / GameChanged
        // — i.e. at boot. Importing a pack or toggling Load Texture Packs mid-session changes
        // nothing on screen until the game restarts, which reads as "texture packs are
        // broken". Spelled-out row rather than another circular-arrow glyph next to Refresh:
        // the earlier icon was both indistinguishable from it and rendered as tofu.
        if (MainActivityRuntime.eState.value != com.armsx2.EmuState.STOPPED) {
            Spacer(Modifier.height(10.dp))
            Text(
                str("renderer.texturePacks.restartHint"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Button(
                // Dismiss FIRST: restart() alone reboots the VM behind this screen, so the
                // user sees nothing change and concludes the button is dead.
                onClick = { onBack(); MainActivityRuntime.restart() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(str("renderer.texturePacks.restartNow")) }
        }
    }
}

@Composable
private fun TexturePackRow(
    pack: TexturePackItem,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▧", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Lead with the game name when the library can supply one — a list of bare
                // serials is unreadable once you have more than a handful of packs. The serial
                // stays on the detail line, since that's the folder the core actually reads.
                Text(
                    pack.gameTitle ?: pack.serial,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = if (pack.gameTitle != null) "${pack.serial} · " else ""
                Text(
                    "$detail${pack.fileCount} · ${humanSize(pack.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    onClick = onExport,
                    enabled = enabled,
                    modifier = Modifier.controllerFocusable("textureMgr.export.${pack.serial}", onConfirm = onExport),
                ) { Text("Export ZIP") }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.controllerFocusable("textureMgr.delete.${pack.serial}", onConfirm = onDelete),
                ) { Text(str("action.delete")) }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = if (bytes >= 1024L * 1024L) "%.1f MB".format(bytes / (1024f * 1024f)) else "${bytes / 1024L} KB"

/**
 * The serial to match catalog packs against.
 *
 * Not simply [TextureManagerUiState.activeSerial]: booting a raw .ELF makes the live serial the
 * executable's name ("PERSONA 3 FES"), which is right for "will this pack load" but matches nothing
 * in a catalog keyed on disc serials. Accept it only when it is actually a PS2 serial.
 */
private fun catalogSerial(state: TextureManagerUiState): String? =
    state.activeSerial?.takeIf { Regex("^[A-Za-z]{4}-[0-9]{5}$").matches(it.trim()) }?.trim()?.uppercase()

/**
 * Serials the user actually has, so packs for owned games sort to the top.
 *
 * The ROM library is the point of this — it used to be only the installed-pack list plus the game
 * in context, which meant an owned game sat under "Other games" until you launched it once, and
 * then moved. Reported by Beep. The library set comes from the scan cache, so this still costs no
 * storage walk.
 *
 * The other two are kept as well: a pack can be installed for a game whose disc is not in a scanned
 * folder, and the game in context may be an .ELF boot with no library entry at all.
 */
private fun librarySerials(state: TextureManagerUiState): Set<String> =
    buildSet {
        addAll(state.librarySerials)
        state.packs.forEach { add(it.serial.uppercase()) }
        state.activeSerial?.let { add(it.uppercase()) }
    }

/**
 * Serials of games the player can actually boot: the library scan and the game in context. This is
 * what picks a multi-region pack's install folder, so unlike [librarySerials] it leaves out the
 * installed pack folders. With those in, a pack already installed under the wrong region's serial
 * would count as owned and keep being installed there.
 */
private fun ownedSerials(state: TextureManagerUiState): Set<String> =
    buildSet {
        addAll(state.librarySerials)
        catalogSerial(state)?.let { add(it) }
    }
