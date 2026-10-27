package com.music.spotui.di

import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.data.local.LocalTrack

/**
 * Plays the device's own audio files (Local Files). While it plays, the Spotify engine is
 * paused and SongPlayer routes the controls here (player, mini player, notification);
 * playing anything from Spotify again hands back to the engine.
 */
object LocalPlayer {
    /** Local playback is the current source. */
    val active = mutableStateOf(false)
    /** The play order, kept in step with the player's items (shuffle reorders it, see [setShuffle]). */
    private val tracks = mutableListOf<LocalTrack>()
    /** The order before shuffle, to go back to. */
    private var unshuffled: List<LocalTrack>? = null
    /**
     * The songs the user queued ("Play next", "Add to queue"), kept right after the current one
     * as Spotify's queue is (see [LocalQueueOrder]); a song leaves it when it starts.
     */
    private val queued: MutableSet<LocalTrack> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private val isQueued: (LocalTrack) -> Boolean = { it in queued }
    /** The queued song playing now (the player then says it plays from the queue). */
    private var playingQueued: LocalTrack? = null
    /** Where the queue comes from (Local Files, Downloaded…). */
    private var listName: String = ""
    /** The songs of the song radio added after the list (see continueIfLast), and its name. */
    private val radioSongs: MutableSet<LocalTrack> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    private var radioName: String = ""
    /** Where the song playing comes from, shown in the player: the list, or the song radio that goes on after it. */
    val contextName: String get() = if (current()?.let { it in radioSongs } == true) radioName else listName
    /** The Spotify list the queue was taken from ("" for Local Files and the Downloaded list, and in its song radio). */
    val contextUri: String get() = if (current()?.let { it in radioSongs } == true) "" else listUri
    private var listUri: String = ""
    private var exo: ExoPlayer? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(Graph.app)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        // Downloaded songs are encrypted files: the player reads them through the decrypting source.
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.music.spotui.data.offline.OfflineCrypto.Factory(Graph.app)).setLoadErrorHandlingPolicy(NoAudioNotRetried))
        .build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()

                // Each change of what the player does, one line each, so a song that never starts says why.
                override fun onPlaybackStateChanged(state: Int) {
                    log("state ${stateName(state)} at ${p.currentPosition} ms, item ${p.currentMediaItemIndex + 1}/${p.mediaItemCount}")
                    if (state == Player.STATE_ENDED) atEnd(p)
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                    log("playWhenReady $playWhenReady (${PLAY_WHEN_READY_REASONS.getOrElse(reason - 1) { "reason $reason" }})")
                override fun onIsPlayingChanged(isPlaying: Boolean) =
                    log("isPlaying $isPlaying at ${p.currentPosition} ms")
                override fun onPlaybackSuppressionReasonChanged(reason: Int) =
                    log("suppressed: ${if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) "no" else "reason $reason"}")

                // The next song an extension streams is asked for as soon as this one starts: it then
                // starts at once instead of waiting for the extension when this one ends.
                // The last one starting asks what goes on after it (see continueIfLast).
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { prefetchNext(p); continueIfLast(p) }

                // A song whose audio an extension did not give or that would not play (audio-source
                // hook) is never skipped silently: Spotify plays it, where it was, and goes on.
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    log("error ${error.errorCodeName}: ${generic(error.cause?.toString() ?: error.message.orEmpty())}")
                    val item = p.currentMediaItem?.localConfiguration?.uri ?: return
                    if (item.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME) return
                    val t = current() ?: return
                    if (SongPlayer.playUnavailableOnSpotify(t, p.currentPosition)) return
                    if (!p.hasNextMediaItem()) return
                    log("skipped ${t.title}: no extension had it and Spotify cannot play it now")
                    p.seekToNextMediaItem()
                    p.prepare()
                    p.play()
                }
            })
            exo = p
        }

    /**
     * The player's load errors: a song no extension gave usable audio for fails at once (each
     * retry would ask the extensions again, five asks in two seconds were seen); others as usual.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private object NoAudioNotRetried : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (com.music.spotui.extensions.AudioRoute.isNoAudio(info.exception)
                // A private or removed YouTube link stays so; asking again would only repeat the look-up.
                || generateSequence<Throwable>(info.exception) { it.cause }.take(8).any { it is com.music.spotui.video.YouTubeLinks.Unavailable })
                C.TIME_UNSET else super.getRetryDelayMsFor(info)
    }

    private val prefetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private var ahead: kotlinx.coroutines.Job? = null

    /**
     * Resolves the next item's audio ahead (ExtensionAudio keeps the answer for its data source),
     * and reads its start: the first bytes of a url (StreamHead), the index and first segment of a
     * DASH answer, so a skip starts at once. Off a metered network only, apart from the answer
     * and the DASH index; the next song changing abandons it.
     */
    private fun prefetchNext(p: ExoPlayer) {
        ahead?.cancel()
        val i = p.nextMediaItemIndex
        if (i == C.INDEX_UNSET) return
        val uri = p.getMediaItemAt(i).localConfiguration?.uri ?: return
        if (uri.scheme != com.music.spotui.extensions.ExtensionAudio.SCHEME) return
        ahead = prefetch.launch {
            // Not at once: a song skipped in its first seconds would have cost its source an ask
            // for nothing, and sources limit how often they are asked (the song is asked anyway
            // when it starts).
            kotlinx.coroutines.delay(AHEAD_DELAY_MS)
            runCatching {
                val s = com.music.spotui.extensions.ExtensionAudio.resolve(uri, ahead = true) ?: return@launch
                val cheap = !com.music.spotui.data.DataSaver.metered
                if (s.manifest != null) {
                    val live = com.music.spotui.data.offline.DashLive.open(s.manifest!!, s.headers, com.music.spotui.data.offline.OfflineDownloader.DownloadUa)
                    if (cheap && isActive) live.warm()
                } else if (cheap && isActive) {
                    com.music.spotui.data.offline.StreamHead.fetch(uri.lastPathSegment.orEmpty(), s.url, s.headers,
                        System.currentTimeMillis() + s.keepMs(HEAD_KEEP_MS)) { isActive }
                }
            }
        }
    }

    private const val AHEAD_DELAY_MS = 10_000L

    /** The most a song's start read ahead is kept (less when its address expires sooner). */
    private const val HEAD_KEEP_MS = 10 * 60_000L

    /**
     * [startMs]: where the song at [index] starts (a resume). [continues]: the extensions play the
     * queue, it goes on after its last song as Spotify's does (see continueIfLast).
     */
    fun play(list: List<LocalTrack>, index: Int, shuffle: Boolean = false, context: String? = null, contextUri: String = "", startMs: Long = 0L, continues: Boolean = false) {
        if (list.isEmpty()) return
        MixedPlayback.localStarted(contextUri)
        SongPlayer.pauseEngineForLocal()
        forgetRadio(); this.continues = continues
        tracks.clear(); tracks.addAll(list); unshuffled = null
        queued.clear(); playingQueued = null; radioSongs.clear()
        listName = context ?: Graph.app.getString(app.myspoti.engine.R.string.local_files_title)
        listUri = contextUri
        active.value = true
        val p = player()
        p.shuffleModeEnabled = false
        p.setMediaItems(list.map(::item), index.coerceIn(0, list.lastIndex), startMs.coerceAtLeast(0L))
        p.prepare()
        if (shuffle) setShuffle(true)
        p.play()
        continueIfLast(p)
    }

    /** Back to Spotify: stop and forget the local queue. */
    fun stop() {
        if (!active.value) return
        active.value = false
        forgetRadio(); continues = false
        exo?.let { it.stop(); it.clearMediaItems() }
        tracks.clear(); unshuffled = null
        queued.clear(); playingQueued = null; radioSongs.clear()
        SongPlayer.restoreEngineQueue()
    }

    /** A player stopped by an error (idle) is prepared again first: a play alone does nothing there. */
    fun resume() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) { log("resume after an error: preparing again"); p.prepare() }
        p.play()
    }
    fun pause() { exo?.pause() }
    fun next() {
        exo?.let {
            if (it.hasNextMediaItem()) { com.music.spotui.extensions.Extensions.skipAsked("forward"); it.seekToNextMediaItem() }
            else if (atEnd(it)) com.music.spotui.extensions.Extensions.skipAsked("forward")
        }
    }
    fun previous() {
        // A mixed list: before the first row of this run comes a Spotify song, played there.
        if (MixedPlayback.previous()) return
        exo?.let {
            if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0)
            else { com.music.spotui.extensions.Extensions.skipAsked("backward"); it.seekToPreviousMediaItem() }
        }
    }
    fun seekTo(ms: Long) { exo?.seekTo(ms) }
    /**
     * Shuffle reorders the songs after the current one (and off puts the list's order back),
     * so the queue shown is the order that really plays and can be edited like any list.
     */
    fun setShuffle(on: Boolean) {
        val p = exo ?: return
        if (on == (unshuffled != null) || tracks.isEmpty()) return
        val cur = p.currentMediaItemIndex.coerceIn(0, tracks.lastIndex)
        if (on) {
            unshuffled = tracks.filterNot(isQueued)
            arrange(LocalQueueOrder.shuffled(tracks.toList(), cur, isQueued) { it.shuffled() })
        } else {
            val before = unshuffled.orEmpty()
            unshuffled = null
            arrange(LocalQueueOrder.unshuffled(tracks.toList(), cur, before, isQueued))
        }
    }

    val shuffled get() = unshuffled != null

    /** The songs after the current one, in play order, with their index in the list. */
    fun upcoming(): List<Pair<Int, LocalTrack>> {
        val i = exo?.currentMediaItemIndex ?: return emptyList()
        return (i + 1 until tracks.size).map { it to tracks[it] }
    }

    /** How many of [upcoming] the user queued (they come first). */
    fun queuedCount(): Int = exo?.let { LocalQueueOrder.queuedAfter(tracks, it.currentMediaItemIndex, isQueued) } ?: 0

    /** The current song is one the user queued ("Playing from the queue"). */
    fun fromQueue(): Boolean = playingQueued?.let { it === current() } == true

    /** A tap on the song at [index] of the queue: it plays at once; the songs queued before it stay queued. */
    fun skipTo(index: Int) {
        val p = exo ?: return
        if (index !in tracks.indices) return
        val (order, at) = LocalQueueOrder.skip(tracks.toList(), p.currentMediaItemIndex, index, isQueued)
        if (order != tracks) arrange(order)
        p.seekTo(at, 0L); p.play()
    }

    /**
     * Moves the song at [from] before or [after] the one at [target], into [target]'s part of the
     * queue (queued, or the list's songs), as a drag in the queue does.
     */
    fun moveBeside(from: Int, target: Int, after: Boolean) {
        if (exo == null) return
        if (from !in tracks.indices || target !in tracks.indices || from == target) return
        val t = tracks[from]
        if (tracks[target] in queued) queued += t else queued -= t
        val order = tracks.toMutableList().apply { removeAt(from) }
        val at = order.indexOfFirst { it === tracks[target] }
        order.add(if (after) at + 1 else at, t)
        val playing = current()
        arrange(LocalQueueOrder.settle(order, order.indexOfFirst { it === playing }, isQueued))
    }

    /** Moves the song at [from] into the queued songs: first ([target] null), else beside [target]. */
    fun moveIntoQueue(from: Int, target: Int?, after: Boolean) {
        val p = exo ?: return
        if (from !in tracks.indices) return
        if (target != null) return moveBeside(from, target, after)
        val t = tracks[from]
        queued += t
        val cur = p.currentMediaItemIndex
        val order = tracks.toMutableList().apply { removeAt(from) }
        order.add((if (from > cur) cur + 1 else cur).coerceIn(0, order.size), t)
        arrange(order)
    }

    /**
     * "Play next" / "Add to queue": [t] joins the queued songs, first ([next]) or after those
     * queued before it, ahead of the list's own next songs (as in Spotify's queue).
     */
    fun enqueue(t: LocalTrack, next: Boolean) {
        val p = exo ?: return
        val at = LocalQueueOrder.insertIndex(tracks, p.currentMediaItemIndex, next, isQueued)
        queued += t
        p.addMediaItem(at, item(t))
        tracks.add(at, t)
        publish()
    }

    /** "Clear queue": the songs the user queued go; the list's own stay. */
    fun clearQueued() {
        val p = exo ?: return
        for (i in tracks.indices.reversed()) if (i > p.currentMediaItemIndex && tracks[i] in queued) {
            p.removeMediaItem(i); queued -= tracks.removeAt(i)
        }
        publish()
    }

    fun remove(index: Int) {
        val p = exo ?: return
        if (index !in tracks.indices || index == p.currentMediaItemIndex) return
        p.removeMediaItem(index)
        val t = tracks.removeAt(index)
        queued -= t
        unshuffled = unshuffled?.filterNot { it === t }
        publish()
    }

    /** Puts the player's items in [target]'s order with moves only, so the current song keeps playing. */
    private fun arrange(target: List<LocalTrack>) {
        val p = exo ?: return
        for (k in target.indices) {
            val j = (k until tracks.size).firstOrNull { tracks[it] === target[k] } ?: continue
            if (j != k) { p.moveMediaItem(j, k); tracks.add(k, tracks.removeAt(j)) }
        }
        publish()
    }

    // ── After the last song ──
    /** The extensions play the queue: it goes on after its last song (files of the device never do). */
    private var continues = false
    /** The song whose song radio was asked, once per last song. */
    private var radioOf: String? = null
    private var radio: kotlinx.coroutines.Job? = null
    /** Its song radio gave nothing: Spotify goes on after it. */
    private var radioEmpty = false
    /** The song ended (or Next) while its song radio was asked: the radio plays as soon as it comes. */
    private var moveOnWhenReady = false

    private fun forgetRadio() { radio?.cancel(); radio = null; radioOf = null; radioEmpty = false; moveOnWhenReady = false }

    /** The Spotify id of a song an extension streams or of a downloaded one, "" for a file of the device. */
    private fun spotifyId(t: LocalTrack?): String = t?.let(::spotifyIdOf).orEmpty()

    /**
     * The last song of a queue the extensions play started: its song radio (what Spotify's autoplay
     * goes on with) is asked now, in the background, and added after it, each song asked to the
     * extensions when it plays as any other, so the queue goes on as Spotify's would.
     */
    private fun continueIfLast(p: ExoPlayer) {
        if (p.mediaItemCount == 0 || p.currentMediaItemIndex != p.mediaItemCount - 1) return
        val id = spotifyId(current())
        if (id == radioOf || !com.music.spotui.extensions.AudioRoute.continues(continues, id,
                repeatAll = p.repeatMode == Player.REPEAT_MODE_ALL, autoplay = com.music.spotui.data.preferences.AutoplayPref.on)) return
        forgetRadio(); radioOf = id
        radio = prefetch.launch {
            val songs = runCatching { Graph.api.getRecommendations(listOf(id)) }.getOrDefault(emptyList())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { radioCame(p, id, songs) }
        }
    }

    private fun radioCame(p: ExoPlayer, id: String, songs: List<com.music.spotui.data.entity.SongsModel>) {
        if (!active.value || radioOf != id) return
        val more = com.music.spotui.extensions.AudioRoute.continuation(songs, tracks.map(::spotifyId))
        if (more.isEmpty()) {
            radioEmpty = true
            if (moveOnWhenReady) spotifyContinues()
            return
        }
        val added = more.map(com.music.spotui.extensions.ExtensionAudio::localTrack).let { if (shuffled) it.shuffled() else it }
        val from = tracks.size
        // Named after the song it goes on from, as Spotify's song radio is.
        radioName = Graph.app.getString(app.myspoti.engine.R.string.song_radio_name, tracks.lastOrNull { spotifyId(it) == id }?.title ?: current()?.title.orEmpty())
        radioSongs += added
        tracks.addAll(added)
        p.addMediaItems(added.map(::item))
        log("queue ended, continuing with ${added.size} songs (song radio)")
        if (moveOnWhenReady) { moveOnWhenReady = false; skipTo(from) } else prefetchNext(p)
        publish()
    }

    /**
     * No song after the current one (it ended, or Next): its song radio plays, now or when it
     * comes, or Spotify goes on when it had none. False: the queue just ends (see AudioRoute.continues).
     */
    private fun atEnd(p: ExoPlayer): Boolean {
        // The phone's rows of a mixed list are over: Spotify goes on with the list's next song.
        if (MixedPlayback.runEnded()) return true
        continueIfLast(p)
        if (radioOf == null || radioOf != spotifyId(current())) return false
        if (radioEmpty) spotifyContinues() else moveOnWhenReady = true
        return true
    }

    private fun spotifyContinues() {
        moveOnWhenReady = false
        val t = current() ?: return
        SongPlayer.continueOnSpotify(t, spotifyId(t))
    }

    private fun item(t: LocalTrack) = MediaItem.Builder().setUri(t.uri).setMediaId(t.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setArtworkUri(android.net.Uri.parse(app.myspoti.host.android.Covers.large(t.artUri.toString()))).setDurationMs(t.durationMs.takeIf { it > 0 }).build()).build()
    fun setRepeatMode(mode: Int) {
        exo?.repeatMode = when (mode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    /** The Spotify id of [t]: a downloaded song's, or the song an extension streams; "" for the phone's own files. */
    fun spotifyIdOf(t: LocalTrack): String =
        com.music.spotui.data.offline.OfflineStore.byLocalId(t.id)?.trackId
            ?: t.uri.takeIf { it.scheme == com.music.spotui.extensions.ExtensionAudio.SCHEME }?.lastPathSegment.orEmpty()

    fun isPlaying() = exo?.isPlaying == true
    /** For the diagnostic (`status`): whether it is to play, and its state (idle, buffering, ready, ended). */
    fun playWhenReady() = exo?.playWhenReady == true
    fun playbackState(): String = exo?.let { stateName(it.playbackState) } ?: "none"
    fun position() = exo?.currentPosition ?: 0L
    fun duration() = exo?.duration?.takeIf { it > 0 } ?: current()?.durationMs ?: 0L
    fun current(): LocalTrack? = exo?.currentMediaItemIndex?.let { tracks.getOrNull(it) }
    /** Where the current song is in the queue given to [play] (no shuffle). */
    fun currentIndex(): Int = exo?.currentMediaItemIndex ?: 0

    private fun log(line: String) = app.myspoti.host.android.Logbook.log("local: $line")

    /** An error's text without the addresses it may carry. */
    private fun generic(text: String) = text.replace(Regex("\\b\\w+://\\S+"), "<address>").take(200)

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "idle"; Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "state $state"
    }

    /** Player.PLAY_WHEN_READY_CHANGE_REASON_*, from 1. */
    private val PLAY_WHEN_READY_REASONS = listOf("asked", "audio focus lost", "audio becoming noisy", "remote", "end of item", "suppressed too long")

    private fun publish() {
        if (!active.value) return
        val t = current() ?: return
        // A queued song that started (or was skipped past) leaves the queue: it plays from there now.
        if (t in queued) playingQueued = t
        exo?.currentMediaItemIndex?.let { cur -> for (i in 0..minOf(cur, tracks.lastIndex)) queued -= tracks[i] }
        SongPlayer.onLocalState(t, exo?.playWhenReady == true, position(), duration())
        SongPlayer.onLocalQueue(t, upcoming(), queuedCount())
    }
}
