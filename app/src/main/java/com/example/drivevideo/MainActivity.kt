package com.example.drivevideo

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var pending: ((String?) -> Unit)? = null
    private val launcher = registerForActivityResult(StartIntentSenderForResult()) { r ->
        val t = try { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data).accessToken } catch (e: Exception) { null }
        pending?.invoke(t); pending = null
    }

    /** Token OAuth (escopo drive.readonly). Silencioso quando já autorizado. */
    private suspend fun token(): String? = suspendCancellableCoroutine { cont ->
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveApi.SCOPE))).build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { res ->
                if (res.hasResolution()) {
                    pending = { cont.resume(it) }
                    launcher.launch(IntentSenderRequest.Builder(res.pendingIntent!!.intentSender).build())
                } else cont.resume(res.accessToken)
            }.addOnFailureListener { cont.resume(null) }
    }

    private fun fullscreen(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { App() } } }
    }

    private val tabs = listOf("Meu Drive", "Compartilhados", "Recentes", "Com estrela")

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        val scope = rememberCoroutineScope()
        var tok by remember { mutableStateOf<String?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var stack by remember { mutableStateOf(listOf<DriveFile>()) } // pastas abertas
        var query by remember { mutableStateOf("") }
        var items by remember { mutableStateOf(listOf<DriveFile>()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var playing by remember { mutableStateOf<DriveFile?>(null) }
        var subs by remember { mutableStateOf(listOf<DriveFile>()) }

        suspend fun load() {
            loading = true; error = null
            try {
                var t = tok ?: token().also { tok = it } ?: run { error = "Login necessário"; loading = false; return }
                val folder = stack.lastOrNull()?.id
                val q = when {
                    query.isNotBlank() -> "name contains '${DriveApi.esc(query)}' and mimeType contains 'video/' and trashed=false"
                    folder != null -> "'$folder' in parents and trashed=false and ${DriveApi.VIDEO_OR_FOLDER}"
                    tab == 0 -> "'root' in parents and trashed=false and ${DriveApi.VIDEO_OR_FOLDER}"
                    tab == 1 -> "sharedWithMe=true and trashed=false and ${DriveApi.VIDEO_OR_FOLDER}"
                    tab == 2 -> "mimeType contains 'video/' and trashed=false and viewedByMeTime != null"
                    else -> "starred=true and trashed=false and ${DriveApi.VIDEO_OR_FOLDER}"
                }
                val order = if (tab == 2 && folder == null && query.isBlank()) "viewedByMeTime desc" else "folder,name"
                items = try { DriveApi.list(t, q, order) } catch (e: java.io.IOException) {
                    if (e.message?.contains("401") == true) { t = token().also { tok = it } ?: throw e; DriveApi.list(t, q, order) } else throw e
                }
            } catch (e: Exception) { error = e.message }
            loading = false
        }

        LaunchedEffect(tab, stack, query) { load() }

        playing?.let { f ->
            LaunchedEffect(f) {
                subs = try {
                    f.parent?.let { p ->
                        val base = f.name.substringBeforeLast('.')
                        DriveApi.list(tok!!, "'$p' in parents and trashed=false and (name contains '.srt' or name contains '.vtt')")
                            .filter { it.name.startsWith(base) }
                    } ?: emptyList()
                } catch (e: Exception) { emptyList() }
            }
            PlayerScreen(f, tok!!, subs, onFullscreen = { fullscreen(it) }) { fullscreen(false); playing = null }
            return
        }

        BackHandler(stack.isNotEmpty()) { stack = stack.dropLast(1) }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Pesquisar vídeos no Drive") }, singleLine = true)
            ScrollableTabRow(tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i; stack = emptyList(); query = "" }, text = { Text(t) }) }
            }
            if (stack.isNotEmpty()) Text("← ${stack.last().name}", Modifier.clickable { stack = stack.dropLast(1) }.padding(16.dp),
                style = MaterialTheme.typography.titleSmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Column(Modifier.padding(16.dp)) { Text("Erro: $it"); Button({ scope.launch { load() } }) { Text("Tentar de novo") } }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { f ->
                    ListItem(
                        headlineContent = { Text(f.name, maxLines = 2) },
                        supportingContent = { Text(if (f.isFolder) "Pasta" else "${f.modified} · ${f.size / 1_048_576} MB") },
                        leadingContent = { Text(if (f.isFolder) "📁" else "🎬") },
                        modifier = Modifier.clickable { if (f.isFolder) { stack = stack + f; query = "" } else playing = f }
                    )
                }
                if (!loading && items.isEmpty() && error == null) item { Text("Nenhum vídeo aqui.", Modifier.padding(16.dp)) }
            }
        }
    }

    @Composable
    fun PlayerScreen(file: DriveFile, token: String, subs: List<DriveFile>, onFullscreen: (Boolean) -> Unit, onBack: () -> Unit) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var full by remember { mutableStateOf(false) }
        val player = remember {
            val ds = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
            ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        }
        LaunchedEffect(file, subs) {
            val pos = player.currentPosition
            val cfg = subs.map {
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(DriveApi.mediaUrl(it.id)))
                    .setMimeType(if (it.name.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                    .setLabel(it.name).setLanguage("und").build()
            }
            player.setMediaItem(MediaItem.Builder().setUri(DriveApi.mediaUrl(file.id)).setMimeType(file.mime)
                .setSubtitleConfigurations(cfg).build(), pos)
            player.prepare(); player.playWhenReady = true
        }
        DisposableEffect(Unit) { onDispose { player.release() } }
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            // Controles nativos: play/pause, ±seek, barra de progresso, velocidade, legendas, faixa de áudio
            AndroidView({ PlayerView(it).apply { this.player = player; setShowSubtitleButton(true); controllerShowTimeoutMs = 3000 } },
                Modifier.fillMaxSize())
            Row(Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("←") }
                Text(file.name, Modifier.weight(1f), maxLines = 1)
                TextButton({ full = !full; onFullscreen(full) }) { Text("⛶") }
            }
        }
    }
}
