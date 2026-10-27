// @file YouTubeLinks.kt
// @author: ForUltraplayer
// @brief Keep YouTube videos as phone-only list items and resolve their audio when they play.
package com.music.spotui.video

import android.net.Uri
import app.myspoti.extensions.AudioStream
import com.music.spotui.data.local.LocalTrack
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * A YouTube video kept in a phone-only list (as the PC version's YouTube links): stored as a
 * `myspoti-yt://video/ID` item and played by the local player, whose data source asks
 * [resolve] for the audio address on its loading thread. Nothing is downloaded or stored
 * beyond the link, its title and channel.
 */
object YouTubeLinks {
    const val SCHEME = "myspoti-yt"
    private val ID = Regex("[A-Za-z0-9_-]{11}")

    /** A private, removed or otherwise unavailable video: shown as such, never retried. */
    class Unavailable(message: String) : IOException(message)

    fun uri(videoId: String): Uri = Uri.Builder().scheme(SCHEME).authority("video").appendPath(videoId).build()
    fun videoId(u: Uri): String? = u.lastPathSegment?.takeIf { u.scheme == SCHEME && ID.matches(it) }

    private fun info(id: String, fresh: Boolean = false) = try {
        YouTubeSource.info("https://www.youtube.com/watch?v=$id", fresh)
    } catch (e: ContentNotAvailableException) {
        throw Unavailable("비공개이거나 삭제된 영상, 또는 외부 재생이 막힌 영상입니다.").apply { initCause(e) }
    }

    /**
     * Blocking: the link as a list item, with the video's title and channel unless the user
     * gave their own (the lyrics are looked up by them).
     */
    fun track(link: String, title: String = "", artist: String = ""): LocalTrack {
        val id = YouTubeUrlKey.videoId(link.trim()) ?: throw IllegalArgumentException("YouTube 영상 주소를 확인해 주세요.")
        val info = info(id)
        return LocalTrack(
            id = id.hashCode().toLong() and 0x7fffffffL,
            uri = uri(id),
            title = title.trim().ifEmpty { info.name },
            artist = artist.trim().ifEmpty { info.uploaderName.orEmpty().removeSuffix(" - Topic") },
            album = "YouTube",
            durationMs = info.duration * 1000,
            artUri = Uri.parse("https://i.ytimg.com/vi/$id/hqdefault.jpg"),
        )
    }

    /** For the player's data source: the audio of a link item. [again]: the kept address was refused. */
    fun resolve(u: Uri, again: Boolean = false): AudioStream? {
        val id = videoId(u) ?: return null
        val info = info(id, fresh = again)
        val best = info.audioStreams.filter { it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA_OPUS }
            .maxByOrNull { it.averageBitrate } ?: return null
        return AudioStream(
            url = best.content,
            headers = MusicVideoController.streamHeaders(best.content),
            mimeType = best.format?.mimeType,
            durationMs = info.duration * 1000,
            source = "YouTube" + (if (best.averageBitrate > 0) " · ${best.averageBitrate} kbps" else ""),
        )
    }

    /** How long a newly issued address that answers 403 is waited for (googlevideo serves it after 1-4 s). */
    private const val SERVE_WAIT_MS = 6_000L

    /**
     * Opens a newly issued googlevideo address, waiting while it still answers 403: the PC
     * version saw fresh addresses refused for 1-4 s before they are served. Any other error,
     * or a 403 past [SERVE_WAIT_MS], is thrown as it is (the caller then asks for a new address).
     */
    fun <T> untilServed(status: (Throwable) -> Int?, open: () -> T): T {
        val deadline = System.nanoTime() + SERVE_WAIT_MS * 1_000_000
        while (true) {
            try {
                return open()
            } catch (e: IOException) {
                val refused = generateSequence<Throwable>(e) { it.cause }.take(8).mapNotNull(status).any { it == 403 }
                if (!refused || System.nanoTime() + 1_000_000_000 > deadline) throw e
                try { Thread.sleep(1_000) } catch (i: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("YouTube audio wait interrupted")
                }
            }
        }
    }
}
