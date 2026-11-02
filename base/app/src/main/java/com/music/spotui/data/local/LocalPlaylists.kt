package com.music.spotui.data.local

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import app.myspoti.engine.R
import com.music.spotui.di.Graph
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One occurrence of a phone file in a list (its own [key]: the same file can be there twice). */
data class LocalItem(
    val key: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String,
) {
    fun toTrack() = LocalTrack(
        id = key.hashCode().toLong() and 0x7fffffffL,
        uri = Uri.parse(uri), title = title, artist = artist, album = album,
        durationMs = durationMs, artUri = Uri.parse(artUri),
    )

    companion object {
        fun of(t: LocalTrack) = LocalItem(
            key = UUID.randomUUID().toString(), uri = t.uri.toString(), title = t.title,
            artist = t.artist, album = t.album, durationMs = t.durationMs, artUri = t.artUri.toString(),
        )
    }
}

/** A playlist kept on this phone only (Spotify cannot hold phone files). */
data class LocalPlaylist(val id: String, val name: String, val items: List<LocalItem>, val createdAt: Long)

/**
 * Playlists of phone files, and phone files added to Spotify playlists ("Added on this
 * device"). Stored in the app's files; nothing is sent to Spotify, whose API does not
 * accept local files.
 */
object LocalPlaylists {
    private const val VERSION = 1
    private lateinit var file: File
    private val lock = Any()

    val playlists: SnapshotStateMap<String, LocalPlaylist> = mutableStateMapOf()
    /** Spotify playlist id → phone files added to it on this phone. */
    val additions: SnapshotStateMap<String, List<LocalItem>> = mutableStateMapOf()
    /** Spotify playlist id → its rows and the phone's items in the user's order (MixedOrder keys); Spotify's order is untouched. */
    val orders: SnapshotStateMap<String, List<String>> = mutableStateMapOf()

    @Volatile private var loaded = false

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "local_playlists.json")
        // Read off the main thread; the screens show the lists as they land, and a change made
        // before then reads the file first ([ensureLoaded]) so it cannot overwrite it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded || !::file.isInitialized) return
            loaded = true
            // An unreadable list (damaged, or from a newer version) is kept aside: the next change would replace it.
            if (file.exists() && !runCatching { read(file.readText()) }.getOrDefault(false))
                runCatching { file.copyTo(File(file.parentFile, "local_playlists.unreadable-${System.currentTimeMillis()}.json")) }
        }
    }

    /** Newest first. */
    fun list(): List<LocalPlaylist> = playlists.values.sortedByDescending { it.createdAt }

    fun create(name: String): LocalPlaylist {
        val p = LocalPlaylist(UUID.randomUUID().toString(), name.trim(), emptyList(), System.currentTimeMillis())
        update { playlists[p.id] = p }
        return p
    }

    fun rename(id: String, name: String) = update { playlists[id]?.let { playlists[id] = it.copy(name = name.trim()) } }
    fun delete(id: String) = update { playlists.remove(id) }

    fun add(id: String, track: LocalTrack) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items + LocalItem.of(track)) } }

    fun remove(id: String, key: String) =
        update { playlists[id]?.let { playlists[id] = it.copy(items = it.items.filterNot { i -> i.key == key }) } }

    fun addToSpotify(playlistId: String, track: LocalTrack) = update {
        val item = LocalItem.of(track)
        additions[playlistId] = additions[playlistId].orEmpty() + item
        // A saved order gets the new item at its end, where it shows without one.
        orders[playlistId]?.let { orders[playlistId] = it + (MixedOrder.LOCAL + item.key) }
    }

    fun removeFromSpotify(playlistId: String, key: String) = update {
        val left = additions[playlistId].orEmpty().filterNot { it.key == key }
        if (left.isEmpty()) additions.remove(playlistId) else additions[playlistId] = left
        orders[playlistId]?.let { orders[playlistId] = it - (MixedOrder.LOCAL + key) }
    }

    /** The mixed order of a Spotify playlist; empty puts Spotify's order back (the phone's items after it). */
    fun setOrder(playlistId: String, keys: List<String>) = update {
        if (keys.isEmpty()) orders.remove(playlistId) else orders[playlistId] = keys.distinct().take(MixedOrder.MAX_KEYS)
    }

    private fun update(change: () -> Unit) {
        synchronized(lock) {
            ensureLoaded()
            change()
            if (::file.isInitialized) {
                runCatching { com.music.spotui.data.writeTextSafely(file, write()) }
                    .onFailure { app.myspoti.host.android.Logbook.log("local playlists not saved: ${it.message}") }
            }
        }
    }

    // ── JSON ──

    private fun itemJson(i: LocalItem) = JSONObject().put("key", i.key).put("uri", i.uri).put("title", i.title)
        .put("artist", i.artist).put("album", i.album).put("duration", i.durationMs).put("art", i.artUri)

    private fun itemOf(o: JSONObject) = LocalItem(o.getString("key"), o.getString("uri"), o.optString("title"),
        o.optString("artist"), o.optString("album"), o.optLong("duration"), o.optString("art"))

    private fun items(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { runCatching { itemOf(a!!.getJSONObject(it)) }.getOrNull() }

    internal fun write(): String {
        val lists = JSONArray()
        playlists.values.forEach { p ->
            lists.put(JSONObject().put("id", p.id).put("name", p.name).put("created", p.createdAt)
                .put("items", JSONArray().apply { p.items.forEach { put(itemJson(it)) } }))
        }
        val adds = JSONObject()
        additions.forEach { (id, list) -> adds.put(id, JSONArray().apply { list.forEach { put(itemJson(it)) } }) }
        val mixed = JSONObject()
        orders.forEach { (id, keys) -> mixed.put(id, JSONArray(keys)) }
        return JSONObject().put("version", VERSION).put("playlists", lists).put("additions", adds).put("orders", mixed).toString()
    }

    internal fun read(text: String): Boolean {
        val o = JSONObject(text)
        if (o.optInt("version") != VERSION) return false
        val lists = o.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until lists.length()) {
            val p = lists.getJSONObject(i)
            playlists[p.getString("id")] = LocalPlaylist(p.getString("id"), p.optString("name"), items(p.optJSONArray("items")), p.optLong("created"))
        }
        val adds = o.optJSONObject("additions") ?: JSONObject()
        adds.keys().forEach { id -> items(adds.optJSONArray(id)).takeIf { it.isNotEmpty() }?.let { additions[id] = it } }
        // Written by this version on: an older store has none, its items then follow Spotify's order.
        val mixed = o.optJSONObject("orders") ?: JSONObject()
        mixed.keys().forEach { id ->
            val keys = mixed.optJSONArray(id) ?: return@forEach
            (0 until minOf(keys.length(), MixedOrder.MAX_KEYS)).map { keys.optString(it) }
                .filter { it.startsWith(MixedOrder.SPOTIFY) || it.startsWith(MixedOrder.LOCAL) }
                .takeIf { it.isNotEmpty() }?.let { orders[id] = it }
        }
        return true
    }

    // ── Files (as the PC version's list export) ──

    private const val FILE_FORMAT = "myspoti-hangul-local-playlist"
    const val MAX_FILE_BYTES = 3 shl 20
    private const val MAX_FILE_ITEMS = 2000
    /** Why [import] refused a file (its IllegalArgumentException message); the screen words it. */
    const val REFUSED_TOO_LARGE = "list file over 3 MB"
    const val REFUSED_FOREIGN = "not a list file of this app"
    const val REFUSED_TOO_MANY = "too many songs in the list file"

    /** One list as a file. Phone files keep their phone address, which plays on this phone only. */
    fun export(id: String): String? = playlists[id]?.let { p ->
        JSONObject().put("format", FILE_FORMAT).put("version", VERSION).put("name", p.name)
            .put("items", JSONArray().apply { p.items.forEach { put(itemJson(it)) } }).toString(2)
    }

    /** What [import] made, and how many songs of the file a phone list cannot hold (a PC list's Spotify songs). */
    data class Imported(val playlist: LocalPlaylist, val leftOut: Int)

    /**
     * A new list from a file made by [export], or by the PC version's mixed list export; never
     * replaces an existing one.
     */
    fun import(text: String): Imported {
        require(text.length <= MAX_FILE_BYTES) { REFUSED_TOO_LARGE }
        val o = runCatching { JSONObject(text.removePrefix("\uFEFF")) }.getOrNull()
        require(o != null) { REFUSED_FOREIGN }
        val pc = if (o.optString("format") == PC_FORMAT && o.optInt("schema") == 1) o.optJSONObject("playlist") else null
        require(pc != null || o.optString("format") == FILE_FORMAT && o.optInt("version") == VERSION) { REFUSED_FOREIGN }
        val (found, leftOut) = if (pc != null) fromPc(pc) else items(o.optJSONArray("items")) to 0
        require(found.size <= MAX_FILE_ITEMS) { REFUSED_TOO_MANY }
        val name = if (pc != null) (pc.opt("name") as? String).orEmpty() else o.optString("name")
        val p = LocalPlaylist(UUID.randomUUID().toString(), name.trim().ifEmpty { Graph.app.getString(R.string.hangul_list_imported_name) }.take(200),
            found.map { it.copy(key = UUID.randomUUID().toString()) }, System.currentTimeMillis())
        update { playlists[p.id] = p }
        return Imported(p, leftOut)
    }

    // ── Files of the PC version (its mixed list export, SpotifyHangulMixedPlaylist schema 1) ──

    private const val PC_FORMAT = "SpotifyHangulMixedPlaylist"
    private val PC_VIDEO = Regex("[A-Za-z0-9_-]{11}")

    /** A string field of a PC file, or empty (the file is not trusted: no numbers or nulls made into text). */
    private fun pcText(o: JSONObject, name: String, limit: Int) = (o.opt(name) as? String).orEmpty().trim().take(limit)

    /**
     * A PC list's YouTube links as phone items, in its mixed order. Its Spotify songs cannot be in a
     * phone list (the local player cannot play them), so they are left out and counted, with any link
     * that is not a valid video and a video there twice. The PC's lyric search name, when set, becomes
     * the title and artist: the phone shows and looks the lyrics up by one title and artist.
     */
    private fun fromPc(p: JSONObject): Pair<List<LocalItem>, Int> {
        val spotify = p.optJSONArray("nativeTracks")?.length() ?: 0
        val tracks = p.optJSONArray("tracks") ?: JSONArray()
        require(spotify <= MAX_FILE_ITEMS && tracks.length() <= MAX_FILE_ITEMS) { REFUSED_TOO_MANY }
        val links = LinkedHashMap<String, LocalItem>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val id = (t.opt("videoId") as? String)?.takeIf { t.opt("kind") == "youtube" && PC_VIDEO.matches(it) } ?: continue
            if (id in links) continue
            val lyricTitle = pcText(t, "lyricTitle", 300)
            val seconds = (t.opt("duration") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..31_536_000.0 } ?: 0.0
            links[id] = LocalItem(key = id, uri = "${com.music.spotui.video.YouTubeLinks.SCHEME}://video/$id",
                title = lyricTitle.ifEmpty { pcText(t, "title", 300).ifEmpty { id } },
                artist = if (lyricTitle.isNotEmpty()) pcText(t, "lyricArtist", 200) else pcText(t, "artist", 200),
                album = "YouTube", durationMs = (seconds * 1000).toLong(), artUri = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        }
        val order = p.optJSONArray("order") ?: JSONArray()
        val ordered = (0 until minOf(order.length(), MixedOrder.MAX_KEYS))
            .mapNotNull { (order.opt(it) as? String)?.takeIf { key -> key.startsWith("yt:") }?.let { key -> links[key.removePrefix("yt:")] } }
        // Links the order does not name (a hand-made file) follow it in the file's order.
        val found = (ordered + links.values).distinctBy { it.key }
        return found to spotify + tracks.length() - found.size
    }
}
