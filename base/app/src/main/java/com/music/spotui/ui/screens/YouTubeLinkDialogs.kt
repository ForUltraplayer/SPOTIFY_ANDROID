// @file YouTubeLinkDialogs.kt
// @author: ForUltraplayer
// @brief Add YouTube links to phone lists and move phone lists in and out as files.
package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.myspoti.engine.R
import com.music.spotui.ui.theme.AppMuted
import com.music.spotui.ui.theme.AppText
import com.music.spotui.ui.theme.tone
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.data.local.LocalPlaylist
import com.music.spotui.data.local.LocalPlaylists
import com.music.spotui.data.local.LocalTrack
import com.music.spotui.video.YouTubeLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Asks for a YouTube address, checks the video, and hands it over as a phone-list item. */
@Composable
fun YouTubeLinkDialog(onDismiss: () -> Unit, onAdded: (LocalTrack) -> Unit) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("YouTube 링크 추가") },
        text = {
            Column {
                Text("영상의 소리를 이 휴대폰의 목록에서 재생합니다. 가사는 아래 제목과 가수로 찾고, 비워 두면 영상 제목과 채널을 씁니다.", fontSize = 13.sp)
                OutlinedTextField(link, { link = it; error = null }, label = { Text("YouTube 또는 YouTube Music 주소") },
                    singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(title, { title = it }, label = { Text("가사 검색용 곡 제목 (선택)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(artist, { artist = it }, label = { Text("가사 검색용 가수 (선택)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { YouTubeLinks.track(link, title, artist) } }
                    busy = false
                    result.onSuccess(onAdded).onFailure { e ->
                        error = if (e is YouTubeLinks.Unavailable || e is IllegalArgumentException) e.message
                        else "영상 정보를 가져오지 못했습니다. 네트워크를 확인해 주세요."
                    }
                }
            }) { Text(if (busy) "확인 중…" else "추가") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } },
    )
}

/** A playlist's "+": its Spotify songs (search, suggestions) or a YouTube link kept on this phone under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistAddChooser(onDismiss: () -> Unit, onSpotify: () -> Unit, onYouTube: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = tone(0xFF282828)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            ChooserRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.add_songs_title), null) { onDismiss(); onSpotify() }
            ChooserRow(Icons.Outlined.Link, "YouTube 링크 추가", "이 휴대폰에서 이 플레이리스트 아래에 표시") { onDismiss(); onYouTube() }
        }
    }
}

@Composable
private fun ChooserRow(icon: ImageVector, label: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = AppMuted, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = AppText, fontSize = 16.sp)
            if (detail != null) Text(detail, color = AppMuted, fontSize = 12.sp)
        }
    }
}

/** Saves a phone list to a file the user picks (as the PC version's list export). */
@Composable
fun rememberLocalPlaylistExport(): (id: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pending?.let(LocalPlaylists::export)
        pending = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }
            Toast.makeText(context, if (saved.isSuccess) "목록을 파일로 저장했습니다." else "목록 파일을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }
    return { id ->
        pending = id
        launcher.launch((LocalPlaylists.playlists[id]?.name ?: "목록").replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".json")
    }
}

/**
 * Reads a list file made by the export into a new phone list; existing lists stay as they are.
 * [onDone] gets the new list, or null when nothing was picked or the file was refused.
 */
@Composable
fun rememberLocalPlaylistImport(onDone: (LocalPlaylist?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onDone(null); return@rememberLauncherForActivityResult }
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytesCompat(LocalPlaylists.MAX_FILE_BYTES + 1)
                        require(bytes.size <= LocalPlaylists.MAX_FILE_BYTES) { "3 MB 이하의 목록 파일만 가져올 수 있습니다." }
                        String(bytes, Charsets.UTF_8)
                    }
                }
            }
            val imported = text.mapCatching(LocalPlaylists::import)
            Toast.makeText(context, imported.fold({ "'${it.name}' 목록을 가져왔습니다." }, { (it as? IllegalArgumentException)?.message ?: "목록 파일을 읽지 못했습니다." }),
                Toast.LENGTH_SHORT).show()
            onDone(imported.getOrNull())
        }
    }
    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
}

/** At most [limit] bytes (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
