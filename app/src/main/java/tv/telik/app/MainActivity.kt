package tv.telik.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(SmotretCatalog.PREFS, MODE_PRIVATE) }
    private var player: ExoPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var categoryBar: LinearLayout
    private lateinit var channelList: LinearLayout
    private var channels: List<TvChannel> = emptyList()
    private var selectedCategory = "Все"
    private var query = ""
    private var favoritesOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildHome()
        loadCache()
        refreshCatalog()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(34, 20, 34, 20); setBackgroundColor(0xFF101820.toInt()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply { text = "TELIK-TV"; textSize = 28f; setTextColor(-1); setPadding(0, 0, 24, 0) }
        header.addView(title)
        header.addView(action("Поиск") { searchDialog() })
        header.addView(action("Избранное") { favoritesOnly = !favoritesOnly; renderChannels() })
        header.addView(action("Обновить") { refreshCatalog() })
        header.addView(action("Источник") { feedDialog() })
        root.addView(header)
        status = TextView(this).apply { text = "Загружаем каталог…"; textSize = 15f; setTextColor(0xFFB8C7D1.toInt()); setPadding(0, 10, 0, 12) }
        root.addView(status)
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(ScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(categoryBar) }, LinearLayout.LayoutParams(-1, 54))
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFocusable = false; addView(channelList) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun action(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(-1); setPadding(16, 12, 16, 12); isFocusable = true
        setOnClickListener { click() }
    }

    private fun loadCache() {
        val raw = prefs.getString("cached_channels", null) ?: return
        runCatching {
            val arr = org.json.JSONArray(raw)
            channels = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i); TvChannel(o.getString("id"), o.getString("name"), o.getString("category"), o.optString("page").takeIf { it.isNotBlank() }, o.optString("stream").takeIf { it.isNotBlank() })
            }
            renderCategories(); renderChannels()
            status.text = "Сохранённый каталог · ${channels.size} каналов. Проверяем обновления…"
        }
    }

    private fun refreshCatalog() {
        status.text = "Обновляем каталог Smotret.tv и дополнительные адреса…"
        worker.execute {
            val result = runCatching { SmotretCatalog(this).refresh() }
            runOnUiThread {
                if (result.isSuccess && result.getOrNull().orEmpty().isNotEmpty()) {
                    channels = result.getOrDefault(emptyList())
                    val arr = org.json.JSONArray()
                    channels.forEach { c -> arr.put(org.json.JSONObject().put("id", c.id).put("name", c.name).put("category", c.category).put("page", c.pageUrl ?: "").put("stream", c.streamUrl ?: "")) }
                    prefs.edit().putString("cached_channels", arr.toString()).apply()
                    renderCategories(); renderChannels()
                    status.text = "Каталог обновлён · ${channels.size} каналов"
                } else status.text = "Не удалось обновить каталог. ${channels.size} сохранённых каналов доступны, если они есть. Проверьте интернет."
            }
        }
    }

    private fun renderCategories() {
        categoryBar.removeAllViews()
        (listOf("Все") + channels.map { it.category }.distinct().sorted()).forEach { category ->
            val b = action(category) { selectedCategory = category; renderChannels() }
            b.setTextColor(if (category == selectedCategory) 0xFF65D6AD.toInt() else -1)
            categoryBar.addView(b)
        }
    }

    private fun renderChannels() {
        channelList.removeAllViews()
        val favs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        val filtered = channels.filter { (selectedCategory == "Все" || it.category == selectedCategory) &&
            (!favoritesOnly || it.id in favs) && (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true)) }
        filtered.forEach { channel ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply { text = "${channel.name}   ·   ${channel.category}"; textSize = 18f; setTextColor(-1); setPadding(14, 13, 14, 13); isFocusable = true; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { play(channel) } }
            val star = action(if (channel.id in favs) "★" else "☆") {
                val next = (prefs.getStringSet("favorites", emptySet()) ?: emptySet()).toMutableSet()
                if (!next.add(channel.id)) next.remove(channel.id)
                prefs.edit().putStringSet("favorites", next).apply(); renderChannels()
            }
            row.addView(name); row.addView(star); channelList.addView(row)
        }
        if (filtered.isEmpty()) channelList.addView(TextView(this).apply { text = "Каналов по этому фильтру нет"; setTextColor(-1); textSize = 18f; setPadding(16, 20, 16, 20) })
    }

    private fun searchDialog() {
        val input = EditText(this).apply { hint = "Название канала или категория"; setText(query) }
        AlertDialog.Builder(this).setTitle("Поиск каналов").setView(input)
            .setPositiveButton("Найти") { _, _ -> query = input.text.toString(); favoritesOnly = false; renderChannels() }
            .setNegativeButton("Сбросить") { _, _ -> query = ""; renderChannels() }.show()
    }

    private fun feedDialog() {
        val input = EditText(this).apply { hint = "https://ваш-сервер/channels.json"; setSingleLine(); setText(prefs.getString(SmotretCatalog.KEY_FEED, "")) }
        AlertDialog.Builder(this).setTitle("Дополнительный источник адресов")
            .setMessage("Укажите HTTPS-адрес JSON-каталога, который вы имеете право использовать. Его адреса будут обновляться без переустановки APK.")
            .setView(input).setPositiveButton("Сохранить") { _, _ -> prefs.edit().putString(SmotretCatalog.KEY_FEED, input.text.toString().trim()).apply(); refreshCatalog() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun play(channel: TvChannel) {
        status.text = "Проверяем доступный прямой поток: ${channel.name}…"
        worker.execute {
            val stream = runCatching { SmotretCatalog(this).directMediaUrl(channel) }.getOrNull()
            runOnUiThread {
                if (stream == null) status.text = "Для «${channel.name}» прямой HTTPS-адрес не опубликован в открытом HTML или дополнительном каталоге."
                else startPlayer(stream, channel.name)
            }
        }
    }

    private fun startPlayer(url: String, channelName: String) {
        val exo = ExoPlayer.Builder(this).build(); player?.release(); player = exo
        val playerView = PlayerView(this).apply { player = exo; useController = true }
        root.removeAllViews()
        root.addView(TextView(this).apply { text = "TELIK-TV  ·  $channelName"; textSize = 20f; setTextColor(-1); setPadding(20, 12, 20, 12) })
        root.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                statusOrToast("Ошибка воспроизведения: ${error.errorCodeName}")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url)); exo.prepare(); exo.playWhenReady = true
    }

    private fun statusOrToast(message: String) = runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    override fun onBackPressed() {
        if (player != null) { player?.release(); player = null; buildHome(); renderCategories(); renderChannels() }
        else super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { refreshCatalog(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { player?.release(); worker.shutdownNow(); super.onDestroy() }
}
