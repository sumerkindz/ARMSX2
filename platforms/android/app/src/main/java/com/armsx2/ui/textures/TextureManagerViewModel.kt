package com.armsx2.ui.textures

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx2.TexturePackInstallState
import com.armsx2.TexturePackInstaller
import com.armsx2.data.library.GameLibraryRepository
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.config.ConfigStore
import com.armsx2.config.Settings
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.iefriends.pcsx2.NativeApp

data class TexturePackItem(
    val serial: String,
    val directory: File,
    val fileCount: Int,
    val size: Long,
    /** Resolved from the library scan cache so the row can say "Guitar Hero II" and not just
     *  "SLUS-21447". Null when the pack's serial isn't in the library (installed for a game the
     *  user doesn't have, or an .ELF-named folder), in which case the serial is all we can show. */
    val gameTitle: String? = null,
)

data class TextureDumpItem(
    val serial: String,
    val file: File,
    val replacementExists: Boolean,
)

data class TextureDumpGame(
    val serial: String,
    val fileCount: Int,
    val totalBytes: Long,
    val gameTitle: String? = null,
)

data class TextureManagerUiState(
    val settings: Settings = Settings(),
    val packs: List<TexturePackItem> = emptyList(),
    val activeSerial: String? = null,
    val dumpGames: List<TextureDumpGame> = emptyList(),
    val selectedDumpSerial: String? = null,
    val dumps: List<TextureDumpItem> = emptyList(),
    val dumpingEnabled: Boolean? = null,
    /** Serials in the user's ROM library, upper-cased. Read from the scan cache, never scanned here. */
    val librarySerials: Set<String> = emptySet(),
    val busy: Boolean = false,
    val progress: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

private data class TextureManagerScanResult(
    val packs: List<TexturePackItem>,
    val settings: Settings,
    val librarySerials: Set<String>,
    val activeSerial: String?,
    val dumpGames: List<TextureDumpGame>,
    val selectedDumpSerial: String?,
    val dumps: List<TextureDumpItem>,
)

class TextureManagerViewModel(application: Application) : AndroidViewModel(application) {
    var state = androidx.compose.runtime.mutableStateOf(TextureManagerUiState())
        private set

    /**
     * Rescan installed packs and refresh the library set.
     *
     * All of the disk work is on Dispatchers.IO. It used to run inline, and opening this screen
     * then walked every installed pack on the MAIN thread — a real pack is thousands of files
     * (DBZ BT3: 4277 / 1.8 GB), so the screen froze on open and could ANR outright, which is what
     * "the texture packs menu hangs and I have to close the app" was.
     */
    fun refresh() {
        val activeSerial = runtimeSerial()
        val requestedDumpSerial = state.value.selectedDumpSerial
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                val root = textureRoot().apply { mkdirs() }
                TexturePackInstaller.sweepRemovedPacks(root)
                val serialDirs = root.listFiles().orEmpty().filter(File::isDirectory)
                val packs = serialDirs.mapNotNull { serialDir ->
                    val replacements = File(serialDir, "replacements")
                    if (!replacements.isDirectory) return@mapNotNull null
                    // Sizes come from a CACHE, not a fresh walk. Walking every pack's tree on every
                    // open made the Texture Manager freeze when a user had many large packs.
                    val cached = PackSizeCache.get(serialDir.name, replacements.lastModified())
                    val (count, bytes) = cached ?: run {
                        var c = 0
                        var b = 0L
                        replacements.walkTopDown().forEach { f ->
                            if (f.isFile) { c++; b += f.length() }
                        }
                        PackSizeCache.put(serialDir.name, replacements.lastModified(), c, b)
                        c to b
                    }
                    TexturePackItem(serialDir.name, replacements, count, bytes)
                }

                TexturePackInstallState.reconcile(packs.map { it.serial.uppercase() }.toSet())
                val settings = ConfigStore.loadGlobal()
                val cachedGames = runCatching {
                    GameLibraryRepository(getApplication()).loadCached().games
                }.getOrDefault(emptyList())
                val library = cachedGames
                    .mapNotNull { it.serial?.takeIf(String::isNotBlank)?.uppercase() }
                    .toSet()
                val forceEn = com.armsx2.EnglishTitles.enabled.value
                val titles = cachedGames.mapNotNull { g ->
                    val serial = g.serial?.takeIf(String::isNotBlank)?.uppercase() ?: return@mapNotNull null
                    g.displayTitle(forceEn).takeIf(String::isNotBlank)?.let { serial to it }
                }.toMap()

                val named = packs
                    .map { it.copy(gameTitle = titles[it.serial.uppercase()]) }
                    .sortedBy { (it.gameTitle ?: it.serial).lowercase() }

                // Search every existing game dump folder, not only the currently running game.
                // This keeps previously captured textures available for later editing.
                val dumpGames = serialDirs.mapNotNull { serialDir ->
                    val dumpDir = File(serialDir, "dumps")
                    if (!dumpDir.isDirectory) return@mapNotNull null
                    val pngs = dumpDir.listFiles().orEmpty().filter {
                        it.isFile && it.extension.equals("png", ignoreCase = true)
                    }
                    if (pngs.isEmpty()) return@mapNotNull null
                    TextureDumpGame(
                        serial = serialDir.name,
                        fileCount = pngs.size,
                        totalBytes = pngs.sumOf { it.length() },
                        gameTitle = titles[serialDir.name.uppercase()],
                    )
                }.sortedBy { (it.gameTitle ?: it.serial).lowercase() }

                val selectedSerial = dumpGames.firstOrNull {
                    it.serial.equals(activeSerial, ignoreCase = true)
                }?.serial ?: dumpGames.firstOrNull {
                    it.serial.equals(requestedDumpSerial, ignoreCase = true)
                }?.serial ?: dumpGames.firstOrNull()?.serial

                val selectedDumpItems = selectedSerial?.let { serial ->
                    val dumpDir = File(root, "$serial/dumps")
                    val replacementDir = File(root, "$serial/replacements")
                    dumpDir.listFiles().orEmpty()
                        .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                        .sortedByDescending { it.lastModified() }
                        .take(120)
                        .map { file -> TextureDumpItem(serial, file, File(replacementDir, file.name).isFile) }
                }.orEmpty()

                TextureManagerScanResult(
                    packs = named,
                    settings = settings,
                    librarySerials = library,
                    activeSerial = activeSerial,
                    dumpGames = dumpGames,
                    selectedDumpSerial = selectedSerial,
                    dumps = selectedDumpItems,
                )
            }
            state.value = state.value.copy(
                settings = scanned.settings,
                packs = scanned.packs,
                librarySerials = scanned.librarySerials,
                activeSerial = scanned.activeSerial,
                dumpGames = scanned.dumpGames,
                selectedDumpSerial = scanned.selectedDumpSerial,
                dumps = scanned.dumps,
                dumpingEnabled = if (state.value.activeSerial == scanned.activeSerial) state.value.dumpingEnabled else null,
            )
        }
    }

    fun selectDumpGame(serial: String) {
        if (state.value.selectedDumpSerial.equals(serial, ignoreCase = true)) return
        state.value = state.value.copy(selectedDumpSerial = serial, dumps = emptyList())
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { textureRoot() }
            val files = withContext(Dispatchers.IO) {
                val dumpDir = File(root, "$serial/dumps")
                val replacementDir = File(root, "$serial/replacements")
                dumpDir.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                    .sortedByDescending { it.lastModified() }
                    .take(120)
                    .map { file -> TextureDumpItem(serial, file, File(replacementDir, file.name).isFile) }
            }
            if (state.value.selectedDumpSerial.equals(serial, ignoreCase = true)) {
                state.value = state.value.copy(dumps = files)
            }
        }
    }

    /**
     * The serial the CORE will look under — VMManager::GetDiscSerial(), the same value
     * GSTextureReplacements keys its `textures/<serial>/replacements` path off.
     *
     * This is NOT always the library's serial. Booting a raw .ELF (Persona/Aemulus mod
     * setups do this) takes VMManager's elf-override branch, where the serial becomes
     * Path::GetFileTitle(elf) — e.g. "PERSONA 3 FES" for PERSONA 3 FES.ELF — because with
     * no disc mounted there is no real serial to read. Importing under the library serial
     * ("SLUS-21621") then writes to a folder the core never scans, and the pack silently
     * does nothing. Prefer the running core's answer; fall back to the library entry when
     * nothing is running.
     */
    private fun runtimeSerial(): String? {
        if (MainActivityRuntime.nativeReady.value) {
            val live = runCatching { NativeApp.getGameSerial() }.getOrNull()
            if (!live.isNullOrBlank()) return live
        }
        return MainActivityRuntime.currentGame.value?.serial
            // Survives quitting to the library, where the two sources above are both blank.
            ?: MainActivityRuntime.contextGame.value?.serial?.takeIf { it.isNotBlank() }
    }

    /** Toggle the core's existing runtime-only texture dump switch for the running game. */
    fun toggleTextureDumping() {
        if (!MainActivityRuntime.nativeReady.value) {
            state.value = state.value.copy(error = "Start a game before enabling texture dumping.")
            return
        }
        val liveSerial = runCatching { NativeApp.getGameSerial() }.getOrNull()
        if (liveSerial.isNullOrBlank()) {
            state.value = state.value.copy(error = "Start a game before enabling texture dumping.")
            return
        }
        val enabled = runCatching { NativeApp.toggleTextureDumping() }.getOrElse {
            state.value = state.value.copy(error = "Unable to toggle texture dumping: ${it.message ?: "native error"}")
            return
        }
        state.value = state.value.copy(
            dumpingEnabled = enabled,
            message = if (enabled) "Texture dumping is ON. Play the game, then turn it OFF to avoid collecting unnecessary textures."
                else "Texture dumping is OFF.",
        )
    }

    /** Copy a dump to a user-selected location so it can be edited by another Android app. */
    fun exportDump(item: TextureDumpItem, uri: Uri) {
        val context = getApplication<Application>()
        state.value = state.value.copy(busy = true, progress = 0, message = null, error = null)
        viewModelScope.launch {
            val exported = withContext(Dispatchers.IO) {
                runCatching {
                    check(item.file.isFile) { "The dump file no longer exists." }
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open the destination.")
                    output.use { destination ->
                        item.file.inputStream().use { input -> input.copyTo(destination) }
                    }
                    true
                }.getOrDefault(false)
            }
            state.value = state.value.copy(
                busy = false,
                message = if (exported) "Exported ${item.file.name}."
                    else null,
                error = if (exported) null else "Unable to export this texture dump.",
            )
        }
    }

    /**
     * Import an edited PNG for a dumped texture. The output filename is deliberately taken from
     * the dump, not the picked image: that name contains the PS2 texture hash the core matches.
     */
    fun importReplacementImage(item: TextureDumpItem, uri: Uri) {
        val context = getApplication<Application>()
        state.value = state.value.copy(busy = true, progress = 0, message = null, error = null)
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                val root = textureRoot().canonicalFile
                val gameDir = File(root, item.serial).canonicalFile
                val dumpDir = File(gameDir, "dumps").canonicalFile
                val sourceDump = item.file.canonicalFile
                val replacementDir = File(gameDir, "replacements").canonicalFile
                val target = File(replacementDir, sourceDump.name)
                val temporary = File(replacementDir, sourceDump.name + ".importing")
                runCatching {
                    check(gameDir.parentFile == root) { "Invalid game texture folder." }
                    check(sourceDump.parentFile == dumpDir && sourceDump.isFile) {
                        "The selected texture dump no longer exists."
                    }
                    if (!replacementDir.exists()) check(replacementDir.mkdirs()) {
                        "Unable to create the replacements folder."
                    }
                    check(replacementDir.isDirectory && replacementDir.parentFile == gameDir) {
                        "Invalid replacements folder."
                    }
                    temporary.delete()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temporary.outputStream().use(input::copyTo)
                    } ?: error("Unable to open the selected image.")
                    val pngSignature = byteArrayOf(
                        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                    )
                    val isPng = temporary.inputStream().use { stream ->
                        val header = ByteArray(8)
                        stream.read(header) == header.size && header.contentEquals(pngSignature)
                    }
                    check(isPng) { "Choose an edited PNG image. Other image formats are not accepted yet." }

                    // A PNG signature alone does not prove that the full image is decodable. Check
                    // its dimensions and decode a downsampled copy before replacing a working file.
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(temporary.absolutePath, bounds)
                    check(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) {
                        "The PNG is invalid or exceeds the supported 16384-pixel texture limit."
                    }
                    val sampleSize = Integer.highestOneBit(
                        maxOf(bounds.outWidth, bounds.outHeight) / 512
                    ).coerceAtLeast(1)
                    val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    check(android.graphics.BitmapFactory.decodeFile(temporary.absolutePath, decodeOptions) != null) {
                        "The PNG could not be decoded."
                    }

                    runCatching {
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    }.getOrElse {
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    true
                }.getOrElse {
                    temporary.delete()
                    false
                }
            }
            state.value = state.value.copy(
                busy = false,
                message = if (imported) "Imported replacement as ${item.file.name} for ${item.serial}."
                    else null,
                error = if (imported) null else "Unable to import the PNG. Check that it is a valid image and try again.",
            )
            if (imported) {
                if (!ConfigStore.loadGlobal().graphics.loadTextureReplacements) {
                    update { it.copy(graphics = it.graphics.copy(loadTextureReplacements = true)) }
                }
                val liveSerial = runCatching { NativeApp.getGameSerial() }.getOrNull()
                if (item.serial.equals(liveSerial, ignoreCase = true)) reloadCore()
                refresh()
            }
        }
    }

    fun update(transform: (Settings) -> Settings) {
        // Transform the CURRENT global, not this screen's snapshot — see the note in
        // EmulationMenuViewModel.updateSettings. This one saves to global explicitly, so read
        // global explicitly rather than whatever was loaded when the screen opened.
        val updated = transform(ConfigStore.loadGlobal())
        ConfigStore.saveGlobal(updated)
        if (MainActivityRuntime.nativeReady.value) runCatching { updated.applyTo() }
        state.value = state.value.copy(settings = updated)
    }

    fun importFolder(uri: Uri) {
        val context = getApplication<Application>()
        val source = DocumentFile.fromTreeUri(context, uri)
        if (source == null) {
            state.value = state.value.copy(error = "Unable to open the selected folder.")
            return
        }
        val serial = state.value.activeSerial
            ?.takeIf(String::isNotBlank)
            ?: source.name?.let(::normalizeSerial)
        if (serial.isNullOrBlank()) {
            state.value = state.value.copy(error = "Name the selected folder with the game's serial, for example SLES-52187.")
            return
        }
        // Off the main thread. A real pack is thousands of files (DBZ BT3: 4277 files /
        // 1.8 GB) and copyTree walks it over SAF, where every listFiles()/openInputStream()
        // is an IPC round-trip to the DocumentsProvider. Run inline this blocked the UI
        // thread for minutes — the busy state never even rendered — so the user got a black
        // screen and an ANR, worse still with the folder on SD. Progress is published as it
        // copies so the screen can show something moving.
        state.value = state.value.copy(busy = true, progress = 0, message = null, error = null)
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                val destination = File(textureRoot(), "$serial/replacements").apply { mkdirs() }
                val contentRoot = source.findFile("replacements")?.takeIf(DocumentFile::isDirectory) ?: source
                runCatching { copyTree(contentRoot, destination) }.getOrDefault(0)
            }
            state.value = if (copied > 0) {
                state.value.copy(busy = false, progress = 0, message = "Imported $copied texture files for $serial.")
            } else {
                state.value.copy(busy = false, progress = 0, error = "No texture files were imported.")
            }
            if (copied > 0) reloadCore()
            refresh()
        }
    }

    /** Installs a .zip or .tar.zst pack file. The serial comes from the running game, else from the
     *  file's name, as for [importFolder]. */
    fun importArchive(uri: Uri) {
        val context = getApplication<Application>()
        val name = DocumentFile.fromSingleUri(context, uri)?.name
        val serial = state.value.activeSerial
            ?.takeIf(String::isNotBlank)
            ?: name?.let(::normalizeSerial)
        if (serial.isNullOrBlank()) {
            state.value = state.value.copy(error = "Name the pack file with the game's serial, for example SLES-52187.tar.zst.")
            return
        }
        state.value = state.value.copy(busy = true, progress = 0, message = null, error = null)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                TexturePackInstaller.installLocal(
                    context, uri, serial,
                    onProgress = { p ->
                        if (p is TexturePackInstaller.Progress.Extracting) {
                            state.value = state.value.copy(progress = p.done)
                        }
                    },
                    isCancelled = { false },
                )
            }
            state.value = if (outcome.ok) {
                state.value.copy(busy = false, progress = 0, message = "Imported ${outcome.textures} texture files for $serial.")
            } else {
                state.value.copy(busy = false, progress = 0, error = outcome.error ?: "No texture files were imported.")
            }
            if (outcome.ok) reloadCore()
            refresh()
        }
    }

    fun delete(pack: TexturePackItem) {
        val serialDirectory = pack.directory.parentFile ?: return
        // Also off the main thread: deleting a 1.8 GB / 4000-file pack is just as capable of
        // ANRing as the import was.
        state.value = state.value.copy(busy = true, message = null, error = null)
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { TexturePackInstaller.removePack(serialDirectory) }
            // The record goes whatever happened on disk. A pack that is gone, or half gone, is not
            // installed, and a record left behind keeps a greyed-out "Installed" in the catalog,
            // which is the one thing that stops a reinstall.
            TexturePackInstallState.forgetSerial(pack.serial)
            // Drop the cached size too, or a reinstall of the same serial could be measured
            // against the deleted pack's mtime and report stale counts.
            PackSizeCache.forget(pack.serial)
            state.value = if (deleted) {
                state.value.copy(busy = false, message = "Deleted texture pack ${pack.serial}.")
            } else {
                state.value.copy(busy = false, error = "Unable to delete ${pack.serial}.")
            }
            if (pack.serial.equals(state.value.activeSerial, true)) reloadCore()
            refresh()
        }
    }

    /**
     * A catalog pack just landed. Texture replacement defaults to OFF, so without this the files
     * are on disk and the game looks exactly the same — the single most confusing possible outcome.
     */
    fun onPackInstalled() {
        if (!state.value.settings.graphics.loadTextureReplacements) {
            update { it.copy(graphics = it.graphics.copy(loadTextureReplacements = true)) }
        }
        refresh()
        reloadCore()
    }

    fun dismissMessage() {
        state.value = state.value.copy(message = null, error = null)
    }

    private fun copyTree(source: DocumentFile, destination: File, running: IntArray = IntArray(1)): Int {
        var copied = 0
        source.listFiles().forEach { child ->
            val name = child.name?.replace(Regex("[/:*?\"<>|]"), "_") ?: return@forEach
            if (child.isDirectory) {
                copied += copyTree(child, File(destination, name).apply { mkdirs() }, running)
            } else if (child.isFile) {
                val success = getApplication<Application>().contentResolver.openInputStream(child.uri)?.use { input ->
                    File(destination, name).outputStream().use(input::copyTo)
                    true
                } ?: false
                if (success) {
                    copied++
                    // Publish a running count so a multi-thousand-file import shows movement
                    // instead of looking hung. Throttled — this runs on Dispatchers.IO and
                    // state is read by Compose, so every file would be needless recomposition.
                    running[0]++
                    if (running[0] % 25 == 0) {
                        state.value = state.value.copy(progress = running[0])
                    }
                }
            }
        }
        return copied
    }

    private fun textureRoot(): File = File(MainActivityRuntime.assetCopyRoot(getApplication()), "textures")

    private fun normalizeSerial(value: String): String? {
        val match = Regex("([A-Za-z]{4})[-_ ]?(\\d{3})[._ ]?(\\d{2})").find(value) ?: return null
        return "${match.groupValues[1].uppercase()}-${match.groupValues[2]}${match.groupValues[3]}"
    }

    private fun reloadCore() {
        if (!MainActivityRuntime.nativeReady.value) return
        runCatching { NativeApp.reloadTextureReplacements() }
    }
}

/**
 * Remembers each pack's file count and byte total so the Texture Manager doesn't re-walk every
 * pack's tree every time it opens.
 *
 * Keyed on the `replacements` folder's mtime: install, delete or overwrite touches the directory,
 * so a changed pack misses the cache and is re-measured while untouched ones cost nothing. Backed
 * by SharedPreferences (a few dozen short entries) so it also survives a restart — the first open
 * after a cold start was the worst case.
 */
private object PackSizeCache {
    private const val KEY = "textures.sizeCache"

    private fun load(): org.json.JSONObject = runCatching {
        MainActivityRuntime.prefs.getString(KEY, null)?.let { org.json.JSONObject(it) }
    }.getOrNull() ?: org.json.JSONObject()

    fun get(serial: String, mtime: Long): Pair<Int, Long>? {
        val o = load().optJSONObject(serial) ?: return null
        // Stale mtime => the pack changed on disk; force a re-measure.
        if (o.optLong("m", -1L) != mtime) return null
        val c = o.optInt("c", -1)
        val b = o.optLong("b", -1L)
        return if (c >= 0 && b >= 0) c to b else null
    }

    fun put(serial: String, mtime: Long, count: Int, bytes: Long) {
        runCatching {
            val root = load()
            root.put(serial, org.json.JSONObject().apply {
                put("m", mtime); put("c", count); put("b", bytes)
            })
            MainActivityRuntime.prefs.edit().putString(KEY, root.toString()).apply()
        }
    }

    /** Drop one pack's entry, so the next scan re-measures it. */
    fun forget(serial: String) {
        runCatching {
            val root = load()
            if (!root.has(serial)) return
            root.remove(serial)
            MainActivityRuntime.prefs.edit().putString(KEY, root.toString()).apply()
        }
    }
}
