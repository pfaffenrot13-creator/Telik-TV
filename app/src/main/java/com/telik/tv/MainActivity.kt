package com.telik.tv

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

private const val DEFAULT_CATALOGUE = "https://raw.githubusercontent.com/pfaffenrot13-creator/Telik-TV/main/channels.json"
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val category: String,
    val logo: String = "",
    val embedUrl: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TelikTvApp() }
    }
}

@Composable
fun TelikTvApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("telik_tv", android.content.Context.MODE_PRIVATE) }
    val player = remember { ExoPlayer.Builder(context).build() }
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Все") }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    var onlyFavorites by remember { mutableStateOf(false) }
    var catalogueUrl by remember { mutableStateOf(prefs.getString("catalogue_url", DEFAULT_CATALOGUE) ?: DEFAULT_CATALOGUE) }
    var status by remember { mutableStateOf("Подключение к открытому каталогу…") }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadCatalogue() {
        scope.launch {
            status = "Обновление открытого каталога…"
            try {
                val loaded = withContext(Dispatchers.IO) {
                    require(catalogueUrl.startsWith("https://")) { "Разрешены только HTTPS-адреса" }
                    val connection = URL(catalogueUrl).openConnection().apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        setRequestProperty("User-Agent", "Telik-TV/1.0")
                    }
                    val text = connection.getInputStream().bufferedReader().use { it.readText() }
                    val root = JSONObject(text)
                    val array = root.getJSONArray("channels")
                    (0 until array.length()).mapNotNull { i ->
                        val item = array.getJSONObject(i)
                        val stream = item.optString("url").ifBlank { item.optString("streamUrl") }
                        val embed = item.optString("embedUrl")
                        val validStream = stream.startsWith("https://")
                        val validEmbed = isAllowedEmbed(embed)
                        if (!validStream && !validEmbed) null else Channel(
                            item.optString("id").ifBlank { stream.ifBlank { embed } },
                            item.optString("name", "Канал"),
                            if (validStream) stream else "",
                            item.optString("category", "Другое"),
                            item.optString("logo"),
                            if (validEmbed) embed else ""
                        )
                    }
                }
                channels = loaded
                status = if (loaded.isEmpty()) "Каталог доступен, но в нём нет каналов" else "Каналов загружено: ${loaded.size}"
                if (selected == null && loaded.isNotEmpty()) selected = loaded.first()
            } catch (e: Exception) {
                status = "Ошибка каталога: ${e.localizedMessage ?: "проверьте интернет"}"
            }
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(Unit) { loadCatalogue() }
    LaunchedEffect(selected?.url, selected?.embedUrl) {
        val channel = selected
        if (channel == null || channel.embedUrl.isNotBlank()) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(channel.url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF70D6C1), background = Color(0xFF101820), surface = Color(0xFF18232D))) {
        Row(Modifier.fillMaxSize().background(Color(0xFF101820)).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                Text("TELIK-TV", color = Color(0xFF70D6C1), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    val current = selected
                    if (current?.embedUrl?.isNotBlank() == true) {
                        OfficialEmbedPlayer(current.embedUrl, Modifier.fillMaxSize())
                    } else {
                        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
                    }
                    if (selected == null) Text("Выберите канал", color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Text(selected?.name ?: status, color = Color.White, fontSize = 18.sp)
                Text(status, color = Color.LightGray, fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text("Поиск каналов") }, singleLine = true)
                    IconButton(onClick = { onlyFavorites = !onlyFavorites; category = if (onlyFavorites) "Избранное" else "Все" }) { Icon(Icons.Default.Favorite, "Избранное", tint = if (onlyFavorites) Color(0xFFFF6B81) else Color.White) }
                    IconButton(onClick = { loadCatalogue() }) { Icon(Icons.Default.Refresh, "Обновить", tint = Color.White) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { category = "Все"; onlyFavorites = false }) { Text("Все") }
                    TextButton(onClick = { category = "Избранное"; onlyFavorites = true }) { Text("Избранное") }
                    TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                }
                val categories = channels.map { it.category }.distinct()
                Row {
                    categories.take(4).forEach { c -> TextButton(onClick = { category = c; onlyFavorites = false }) { Text(c, maxLines = 1) } }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(channels.filter {
                        (category == "Все" || category == "Избранное" || it.category == category) &&
                        it.name.contains(query, true) && (!onlyFavorites || favorites.contains(it.id))
                    }) { channel ->
                        Row(Modifier.fillMaxWidth().background(if (selected?.id == channel.id) Color(0xFF24433F) else Color(0xFF1A2833), RoundedCornerShape(8.dp)).clickable { selected = channel }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.name, Modifier.weight(1f), color = Color.White)
                            IconButton(onClick = {
                                favorites = if (favorites.contains(channel.id)) favorites - channel.id else favorites + channel.id
                                prefs.edit().putStringSet("favorites", favorites).apply()
                            }) {
                                Icon(Icons.Default.Favorite, "В избранное", tint = if (favorites.contains(channel.id)) Color(0xFFFF6B81) else Color.Gray)
                            }
                        }
                    }
                }
            }
        }
        if (showSettings) AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Каталог каналов") },
            text = { OutlinedTextField(catalogueUrl, { catalogueUrl = it }, label = { Text("HTTPS URL channels.json") }) },
            confirmButton = { TextButton(onClick = {
                prefs.edit().putString("catalogue_url", catalogueUrl.trim()).apply()
                catalogueUrl = catalogueUrl.trim()
                showSettings = false
                loadCatalogue()
            }) { Text("Сохранить и обновить") } },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } }
        )
    }
}


private fun isAllowedEmbed(raw: String): Boolean {
    if (!raw.startsWith("https://")) return false
    return try {
        val host = java.net.URI(raw).host?.lowercase() ?: return false
        host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru") ||
            host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru") ||
            host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "stream.1tv.ru"
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun OfficialEmbedPlayer(embedUrl: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                setBackgroundColor(android.graphics.Color.BLACK)
                tag = embedUrl
                loadUrl(embedUrl)
            }
        },
        update = { view ->
            if (view.tag != embedUrl) {
                view.tag = embedUrl
                view.loadUrl(embedUrl)
            }
        }
    )
}
