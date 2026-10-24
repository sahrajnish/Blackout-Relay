package org.blackoutrelay.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

enum class Screen { HOME, QUEUE, POOL, CANNOT_DO }

private fun titleOf(s: Screen) = when (s) {
    Screen.HOME -> "Pocket Notes"
    Screen.QUEUE -> "Queue"
    Screen.POOL -> "Drop pool"
    Screen.CANNOT_DO -> "What this app cannot do"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.HOME) }
    var identity by remember { mutableStateOf<IdentityStore.Identity?>(null) }
    var queue by remember { mutableStateOf<List<QueueStore.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var poolHealth by remember { mutableStateOf<PoolStore.Health?>(null) }
    var viewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var verify by remember { mutableStateOf<QueueStore.VerifyResult?>(null) }

    fun refresh() {
        scope.launch {
            val id = withContext(Dispatchers.IO) { IdentityStore.loadOrCreate(context) }
            val q = withContext(Dispatchers.IO) { QueueStore.list(context) }
            val h = withContext(Dispatchers.IO) {
                PoolStore.health(context, System.currentTimeMillis() / 1000)
            }
            identity = id
            queue = q
            poolHealth = h
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun sealBytes(bytes: ByteArray, kind: Long, thumb: ByteArray?, durationMs: Long) {
        scope.launch {
            busy = true
            val sealed = withContext(Dispatchers.IO) {
                try {
                    val id = IdentityStore.loadOrCreate(context)
                    val slot = PoolStore.nextSlot(context, System.currentTimeMillis() / 1000)
                    Sealer.seal(context, id, bytes, kind, thumb, durationMs, System.currentTimeMillis(), slot = slot)
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (sealed != null) {
                status = "Sealed ${sealed.bundleIdHex.take(12)}… — ${sealed.originalBytes / 1024} KB ${if (kind == Sealer.KIND_VIDEO) "video" else "photo"}, encrypted" +
                    if (sealed.queued) " · queued for upload" else " · export only (no pool)"
                if (sealed.queued) UploadScheduler.enqueue(context)
            } else status = "Seal failed"
            refresh()
        }
    }

    fun sealUri(uri: Uri, isVideo: Boolean) {
        scope.launch {
            busy = true
            val sealed = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val id = IdentityStore.loadOrCreate(context)
                    val kind = if (isVideo) Sealer.KIND_VIDEO else Sealer.KIND_PHOTO
                    val thumb = if (isVideo) Sealer.videoThumbnailJpeg(context, uri) else null
                    val dur = if (isVideo) Sealer.videoDurationMs(context, uri) else 0L
                    val slot = PoolStore.nextSlot(context, System.currentTimeMillis() / 1000)
                    Sealer.seal(context, id, bytes, kind, thumb, dur, System.currentTimeMillis(), slot = slot)
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (sealed != null) {
                status = "Sealed ${sealed.bundleIdHex.take(12)}… — ${sealed.originalBytes / 1024} KB ${if (isVideo) "video" else "photo"}, encrypted" +
                    if (sealed.queued) " · queued for upload" else " · export only (no pool)"
                if (sealed.queued) UploadScheduler.enqueue(context)
            } else status = "Seal failed"
            refresh()
        }
    }

    val captureUri = remember { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri.value
        if (ok && uri != null) sealUri(uri, false)
    }
    val videoUri = remember { mutableStateOf<Uri?>(null) }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = videoUri.value
        if (ok && uri != null) sealUri(uri, true)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
            sealUri(uri, isVideo)
        }
    }

    fun startCapture() {
        try {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val f = File(dir, "cap-${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            captureUri.value = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            status = "No camera available: ${e.message}"
        }
    }

    fun startVideo() {
        try {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            val f = File(dir, "vid-${System.currentTimeMillis()}.mp4")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            videoUri.value = uri
            captureVideo.launch(uri)
        } catch (e: Exception) {
            status = "No video camera: ${e.message}"
        }
    }

    fun startGallery() {
        try {
            pickMedia.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                )
            )
        } catch (e: Exception) {
            status = "No gallery: ${e.message}"
        }
    }

    fun testCapture() = sealBytes(makeSampleJpeg(), Sealer.KIND_PHOTO, null, 0L)

    val importPool = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val res = withContext(Dispatchers.IO) {
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        PoolStore.import(context, IdentityStore.loadOrCreate(context), bytes)
                    } catch (e: Exception) {
                        Result.failure<Unit>(e)
                    }
                }
                status = res.fold({ "Pool imported" }, { "Import failed: ${it.message}" })
                refresh()
            }
        }
    }

    fun startImportPool() {
        try {
            importPool.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            status = "No file picker: ${e.message}"
        }
    }

    fun clearPool() {
        scope.launch {
            withContext(Dispatchers.IO) { PoolStore.clear(context) }
            status = "Pool cleared"
            refresh()
        }
    }

    fun syncNow() {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { Uploader.uploadAll(context) }
            busy = false
            status = if (r.ok) "Uploaded ${r.bundlesUploaded} bundle(s)" else "Upload: ${r.reason}"
            refresh()
        }
    }

    fun shareIdentity() {
        val text = IdentityStore.toJson(IdentityStore.loadOrCreate(context))
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share identity (reviewer)"))
    }

    Scaffold(topBar = { TopAppBar(title = { Text(titleOf(screen)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (screen) {
                Screen.HOME -> Home(
                    identity, queue.size, busy, status, poolHealth,
                    ::startCapture, ::startVideo, ::startGallery, ::testCapture, ::shareIdentity,
                    { screen = Screen.QUEUE }, { screen = Screen.POOL }, { screen = Screen.CANNOT_DO },
                )
                Screen.QUEUE -> QueueScreen(
                    queue, status,
                    onBack = { screen = Screen.HOME },
                    onCapture = ::startCapture,
                    onTestCapture = ::testCapture,
                    onSync = ::syncNow,
                    onView = { id ->
                        scope.launch {
                            val bmp = withContext(Dispatchers.IO) {
                                android.util.Log.d("Relay", "view $id")
                                IdentityStore.loadOrCreate(context).let { QueueStore.decryptThumb(context, it, id) }
                                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            }
                            viewBitmap = bmp
                        }
                    },
                    onVerify = { id ->
                        scope.launch {
                            verify = withContext(Dispatchers.IO) {
                                IdentityStore.loadOrCreate(context)
                                    .let { QueueStore.verify(context, it, id) }
                            }
                        }
                    },
                    onDelete = { id ->
                        scope.launch {
                            withContext(Dispatchers.IO) { QueueStore.delete(context, id) }
                            refresh()
                        }
                    },
                )
                Screen.POOL -> PoolScreen(
                    poolHealth, identity?.accountIdHex ?: "",
                    ::startImportPool, ::clearPool, { screen = Screen.HOME },
                )
                Screen.CANNOT_DO -> CannotDoScreen({ screen = Screen.HOME })
            }
        }
    }

    viewBitmap?.let { bmp ->
        AlertDialog(
            onDismissRequest = { viewBitmap = null },
            confirmButton = { TextButton(onClick = { viewBitmap = null }) { Text("Close") } },
            title = { Text("Decrypted thumbnail") },
            text = { Image(bmp.asImageBitmap(), contentDescription = "Decrypted thumbnail") },
        )
    }
    verify?.let { v ->
        AlertDialog(
            onDismissRequest = { verify = null },
            confirmButton = { TextButton(onClick = { verify = null }) { Text("Close") } },
            title = { Text(if (v.ok) "VALID" else "INVALID") },
            text = { Column { v.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } },
        )
    }
}

@Composable
private fun Home(
    identity: IdentityStore.Identity?,
    count: Int,
    busy: Boolean,
    status: String?,
    pool: PoolStore.Health?,
    onCapture: () -> Unit,
    onRecordVideo: () -> Unit,
    onPickGallery: () -> Unit,
    onTestCapture: () -> Unit,
    onShareIdentity: () -> Unit,
    onQueue: () -> Unit,
    onPool: () -> Unit,
    onCannotDo: () -> Unit,
) {
    Notice("Prototype. Not audited. Not for use where personal safety depends on it.")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vault", style = MaterialTheme.typography.titleMedium)
            Text("Account: ${identity?.accountIdHex?.take(16) ?: "creating…"}…",
                style = MaterialTheme.typography.bodyMedium)
            Text("Bundles: $count queued", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (pool?.hasPool == true)
                    "Drop pool: ${pool.slotsRemaining}/${pool.slotsTotal} slots left"
                else "Drop pool: not imported (export only)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Button(onClick = onCapture, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "Sealing…" else "Capture photo & seal")
    }
    OutlinedButton(onClick = onRecordVideo, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Record video & seal")
    }
    OutlinedButton(onClick = onPickGallery, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Pick photo/video from gallery")
    }
    OutlinedButton(onClick = onTestCapture, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text("Test capture (no camera)")
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    OutlinedButton(onClick = onQueue, modifier = Modifier.fillMaxWidth()) {
        Text("Queue ($count)")
    }
    OutlinedButton(onClick = onPool, modifier = Modifier.fillMaxWidth()) { Text("Drop pool health") }
    OutlinedButton(onClick = onCannotDo, modifier = Modifier.fillMaxWidth()) {
        Text("What this app cannot do")
    }
    OutlinedButton(onClick = onShareIdentity, modifier = Modifier.fillMaxWidth()) {
        Text("Export identity (for reviewer)")
    }
}

@Composable
private fun QueueScreen(
    queue: List<QueueStore.Entry>,
    status: String?,
    onBack: () -> Unit,
    onCapture: () -> Unit,
    onTestCapture: () -> Unit,
    onSync: () -> Unit,
    onView: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (queue.isEmpty()) {
        Notice("No bundles yet. Capture a photo to create one. Bundles are encrypted on this device before they enter the queue.")
    } else {
        Notice("Each bundle is sealed on-device: only ciphertext is stored. \u201cView\u201d decrypts the thumbnail with your key; \u201cVerify\u201d checks the manifest signature and every hash.")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onCapture, modifier = Modifier.weight(1f)) { Text("Capture") }
        OutlinedButton(onClick = onTestCapture, modifier = Modifier.weight(1f)) { Text("Test") }
    }
    OutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
        Text("Sync now (upload queued)")
    }
    queue.forEach { e ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.bundleIdHex.take(16) + "…", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text("state: ${e.state}${if (e.exportOnly) " (export only)" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                Text("created: ${fmtTime(e.createdAtSec)}  ·  manifest ${e.manifestBytes} B",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onView(e.bundleIdHex) }) { Text("View") }
                    TextButton(onClick = { onVerify(e.bundleIdHex) }) { Text("Verify") }
                    TextButton(onClick = { onDelete(e.bundleIdHex) }) { Text("Delete") }
                }
            }
        }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    BackButton(onBack)
}

@Composable
private fun PoolScreen(
    health: PoolStore.Health?,
    destPrefixHex: String,
    onImport: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Text("Your dest_prefix (use as poolctl --prefix):", style = MaterialTheme.typography.bodySmall)
    Text(destPrefixHex, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    if (health?.hasPool == true) {
        Notice("Pool imported. Each capture consumes one slot; sealed bundles upload silently when a network appears.")
        Text("Slots left: ${health.slotsRemaining} / ${health.slotsTotal}",
            style = MaterialTheme.typography.bodyMedium)
        Text("Pool expires: ${fmtTime(health.poolExpiresAt)}",
            style = MaterialTheme.typography.bodyMedium)
        Text("Dest prefix: ${health.prefixHex.take(16)}…",
            style = MaterialTheme.typography.bodySmall)
    } else {
        Notice("No pool imported. Import a pool minted by poolctl to enable silent upload. Until then, bundles are export-only (EXPORT_ONLY).")
    }
    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import pool file") }
    if (health?.hasPool == true) {
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear pool") }
    }
    BackButton(onBack)
}

@Composable
private fun CannotDoScreen(onBack: () -> Unit) {
    Text("What this app cannot do", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    val lines = listOf(
        "It cannot upload in real time. During a shutdown there is no network; data leaves the zone physically.",
        "It cannot guarantee delivery. Nothing is \u201csent\u201d; bundles are queued and carried.",
        "It is not audited. The cryptography has not had an external review.",
        "It does not protect you if your phone is seized while unlocked.",
        "It does not make you anonymous against a determined state adversary. Pseudonymous, not magic.",
    )
    lines.forEach { Text("\u2022  $it", style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(8.dp))
    BackButton(onBack)
}

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

private fun fmtTime(sec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))

/** A synthetic JPEG so the seal→queue path can be exercised without a camera. */
private fun makeSampleJpeg(): ByteArray {
    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.argb(255, 38, 52, 78))
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
    }
    canvas.drawText("BLACKOUT RELAY", 40f, 110f, paint)
    canvas.drawText("test capture", 40f, 165f, paint)
    canvas.drawText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), 40f, 220f, paint)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    return out.toByteArray()
}
