// @file Runner.kt
// @brief The installed extensions, one sandbox each, and the audio sources asked in order, ported from the Android app's Extensions.kt.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Action
import app.myspoti.extensions.Area
import app.myspoti.extensions.AudioQuery
import app.myspoti.extensions.AudioStream
import app.myspoti.extensions.BasicHttp
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Conflicts
import app.myspoti.extensions.ExtensionBusy
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionFault
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.ExtensionRuntime
import app.myspoti.extensions.ExtensionStatus
import app.myspoti.extensions.ExtensionTimeout
import app.myspoti.extensions.Hook
import app.myspoti.extensions.HookBudgets
import app.myspoti.extensions.Host
import app.myspoti.extensions.HttpRequest
import app.myspoti.extensions.HttpResponse
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Mode
import app.myspoti.extensions.Origin
import app.myspoti.extensions.Outcome
import app.myspoti.extensions.PlayAttempt
import app.myspoti.extensions.PlayAttempts
import app.myspoti.extensions.Setting
import app.myspoti.extensions.SettingSection
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.SourceCooldown
import app.myspoti.extensions.StatusBook
import app.myspoti.extensions.Storage
import app.myspoti.extensions.TrackInfo
import app.myspoti.extensions.mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The extensions installed on this PC, under [dir] (`registry.json`, and per extension `<id>/`:
 * `package.zip`, `previous.zip`, `staging.zip`, `storage.json`, `settings.json`, `log.txt`), one
 * sandbox ([ExtensionRuntime]) each for the enabled ones, and the audio sources asked in the user's
 * order, as the Android app's `Extensions` does: the same budgets ([HookBudgets]), outcomes, cooldowns,
 * status probes, recent misses and [PlayAttempt]s, with the same English log lines and notes.
 *
 * What the PC cannot play is never chosen: an answer whose audio needs decrypting (`transform`), a
 * codec or a DASH manifest the request's [Accept] does not take, is passed over for the next source
 * and said in the attempt. Nothing here decrypts audio.
 *
 * [fetcher]: where packages and the blocked list are downloaded from; [send]: the extensions' own
 * `myspoti.http` requests (already checked by the sandbox); [autoUpdate]: the blocked list and the
 * update check run in the background.
 */
class Runner(
    val dir: File,
    /** The Myspoti version this engine is: what `minAppVersion` is compared with. */
    val engineVersion: String,
    fetcher: Fetcher = Fetcher.Https,
    private val send: (HttpRequest, String) -> HttpResponse = { r, ua -> BasicHttp.send(r, ua) },
    private val autoUpdate: Boolean = true,
) : AutoCloseable {
    companion object {
        const val AUDIO_MS = HookBudgets.AUDIO
        private const val ONLINE_MS = HookBudgets.ONLINE
        private const val SETTING_MS = HookBudgets.SETTING
        private const val SETTING_PAUSE_MS = 800L
        private const val STATUS_MS = HookBudgets.STATUS
        /** How long a song asked right after start waits for the extensions still loading (then Spotify plays it). */
        private const val START_AUDIO_MS = 5_000L
        /** How old a probe may be: asked again after it, and before a source is asked. */
        private const val STATUS_EVERY_MS = 5 * 60_000L
        private const val SLOW_LOAD_MS = 10_000L
        /** An answer is reused for a replay or the ask when the song starts a while, then asked again (addresses expire). */
        private const val KEEP_MS = 10 * 60_000L
        private val SOURCE_HOOKS = setOf(Hook.AUDIO_SOURCE, Hook.DOWNLOAD_SOURCE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile var registry = Registry()
        private set

    /** The last failure of each extension, for its page. */
    val errors = ConcurrentHashMap<String, String>()

    private val runtimes = ConcurrentHashMap<String, ExtensionRuntime>()
    private val packages = ConcurrentHashMap<String, ExtensionPackage>()
    private val stores = ConcurrentHashMap<String, FileStore>()
    private val settings = ConcurrentHashMap<String, FileStore>()
    private val logs = ConcurrentHashMap<String, ExtensionLog>()
    private val failures = FailureCounter()
    val updates = Updates(this, fetcher)
    /** The store's indexes, topic search and ratings, as the Android app reads them. */
    val store = ExtensionStore(this)

    /** The last [PlayAttempt]s (20), for the source-choice screen. */
    val attempts = PlayAttempts(capacity = 20)
    private val misses = AudioMisses()
    private val cooldown = SourceCooldown()
    /** The extensions whose source said the user must sign in, with what it said. */
    val signIns = ConcurrentHashMap<String, String>()
    private val health = StatusBook()
    private val busy = ConcurrentHashMap<String, Int>()
    private val probing = ConcurrentHashMap<String, Deferred<Unit>>()
    private val runningSha = ConcurrentHashMap<String, String>()
    private val startLocks = ConcurrentHashMap<String, Any>()
    private val started = CompletableDeferred<Unit>()
    private val settingJobs = ConcurrentHashMap<String, Job>()
    private val isrcs = ConcurrentHashMap<String, String>()

    @Volatile private var provided: Pair<List<String>, List<String>> = emptyList<String>() to emptyList()

    /** The audio source picked first for this run ([SessionSource]), or null: the saved order. Never saved. */
    @Volatile var sessionSource: String? = null
        private set

    // ── Start ──

    /** Reads the registry and starts the enabled extensions one after the other, in the background. */
    fun init() {
        registry = Registry.read(File(dir, "registry.json")).let { r ->
            if (r.extensions.none { e -> e.rawGrants.any { it.tooBroad } }) r
            else r.extensions.fold(r) { acc, e -> if (e.rawGrants.any { it.tooBroad }) acc.put(e.copy(rawGrants = e.rawGrants.filterNot { it.tooBroad })) else acc }
        }
        // Entries written before the PC kept hooks get them, so the helper can read who gives audio.
        if (registry.extensions.any { it.hooks.isEmpty() }) update { r -> r.copy(extensions = r.extensions.map { e -> if (e.hooks.isEmpty()) manifest(e.id)?.let { e.copy(hooks = hooksOf(it)) } ?: e else e }) }
        updates.readCachedBlocks()
        scope.launch {
            registry.extensions.filter { it.enabled }.forEach { start(it, probe = false) }
            started.complete(Unit)
            refreshProvided()
            probeAll()
            if (autoUpdate) launch { updates.daily() }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                runtimes.forEach { (id, rt) -> if (health.stale(id, STATUS_EVERY_MS)) scope.launch { probe(id, rt) } }
            }
        }
    }

    /** The extensions on at start have all been started (or failed to). */
    val ready: Boolean get() = started.isCompleted

    override fun close() {
        runtimes.keys.toList().forEach { runtimes.remove(it)?.close() }
        scope.cancel()
    }

    // ── Installed extensions ──

    fun manifest(id: String): Manifest? = pkg(id)?.manifest

    fun pkg(id: String): ExtensionPackage? = packages[id] ?: runCatching {
        ExtensionPackage.read(File(dir, "$id/package.zip").readBytes())
    }.getOrNull()?.let { packages.putIfAbsent(id, it) ?: it }

    fun isRunning(id: String) = runtimes.containsKey(id)

    /** What installing [p] would do, weighed against what the user accepted. */
    fun plan(p: ExtensionPackage): InstallPlan {
        val installed = registry.find(p.manifest.id)
        return InstallPlan.of(p.manifest, installed, installed?.let { it.accepted?.toManifest() ?: manifest(it.id) })
    }

    /** What [m] would collide with among the enabled extensions. */
    fun clashes(m: Manifest): List<Clash> = registry.let { r ->
        val enabled = r.byPriority(Area.PLAYBACK.key, r.extensions.filter { it.enabled && it.id != m.id }) { it.id }.mapNotNull { manifest(it.id) }
        Conflicts.clashes(m, enabled, r.active)
    }

    /** Installs (or updates to) [p] with [granted]; its sandbox starts at once. The version it replaces is kept as `previous.zip`. */
    suspend fun install(
        zip: ByteArray, p: ExtensionPackage, granted: Set<String>, source: String,
        clashes: List<Clash> = emptyList(), keepNew: (Clash) -> Boolean = { true }, noticeAccepted: Boolean = true,
        origin: Origin? = null, accepted: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val id = p.manifest.id
        updates.blocked(id, p.sha256)?.let { throw ExtensionException("blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
        val (e, before) = synchronized(startLocks.getOrPut(id) { Any() }) {
            val before = registry.find(id)
            val beforeManifest = before?.let { manifest(id) }
            stop(id)
            val folder = File(dir, id).apply { mkdirs() }
            val current = File(folder, "package.zip")
            if (before != null && current.isFile && before.sha256 != p.sha256) writeAtomically(File(folder, "previous.zip"), current.readBytes())
            writeAtomically(current, zip)
            File(folder, "staging.zip").delete()
            packages[id] = p
            var startOff = false
            update { r ->
                val (settled, off) = Clashes.apply(r, id, clashes, keepNew)
                startOff = off
                settled.copy(noticeAccepted = if (noticeAccepted) settled.noticeAccepted + id else settled.noticeAccepted)
            }
            clashes.filter { keepNew(it) && it.kind != Clash.Kind.EXCLUSIVE }.forEach { stop(it.otherId); log(it.otherId, "turned off: replaced by $id") }
            val e = InstalledExtension(
                id = id, name = p.manifest.name, version = p.manifest.version, versionCode = p.manifest.versionCode,
                author = p.manifest.author, sha256 = p.sha256, granted = granted, enabled = !startOff,
                disabledReason = if (startOff) "kept the extension it conflicts with" else null,
                installedAt = before?.installedAt ?: System.currentTimeMillis(), source = source,
                origin = origin ?: before?.origin,
                accepted = if (accepted) Snapshot.of(p.manifest, (origin ?: before?.origin)?.repo, p.script, origin ?: before?.origin)
                    else before?.accepted ?: beforeManifest?.let { Snapshot.of(it, before?.origin?.repo) },
                previous = if (before != null && before.sha256 != p.sha256) KeptVersion(before.version, before.versionCode, before.sha256, before.origin) else before?.previous,
                declinedVersionCode = before?.declinedVersionCode ?: 0,
                lastCheck = before?.lastCheck ?: 0,
                rawGrants = (before?.rawGrants.orEmpty() +
                    if (Action.SPOTIFY_RAW.key in granted) p.manifest.declaredWrites() else emptyList()).distinct(),
                hooks = hooksOf(p.manifest),
            )
            update { it.put(e) }
            e to before
        }
        failures.reset(id)
        errors.remove(id)
        log(id, if (before == null) "installed ${p.manifest.version} from $source" else "updated ${before.version} → ${p.manifest.version}")
        start(e)
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        stop(id)
        update { it.remove(id) }
        packages.remove(id); stores.remove(id); settings.remove(id); logs.remove(id)
        errors.remove(id)
        runningSha.remove(id)
        known.clear()
        File(dir, id).deleteRecursively()
        RunnerLog.line("ext $id: uninstalled")
    }

    suspend fun setEnabled(id: String, on: Boolean, clashes: List<Clash> = emptyList(), keepNew: (Clash) -> Boolean = { true }) = withContext(Dispatchers.IO) {
        val e = registry.find(id) ?: return@withContext
        if (on) {
            updates.blocked(id, e.sha256)?.let { return@withContext disable(id, "blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
            if (Clashes.apply(registry, id, clashes, keepNew).second) return@withContext
            update { Clashes.apply(it, id, clashes, keepNew).first }
            clashes.filter { keepNew(it) && it.kind != Clash.Kind.EXCLUSIVE }.forEach { stop(it.otherId); log(it.otherId, "turned off: replaced by $id") }
            val now = e.copy(enabled = true, disabledReason = null)
            update { it.put(now) }
            failures.reset(id)
            errors.remove(id)
            start(now)
        } else {
            stop(id)
            update { it.put(e.copy(enabled = false)) }
            log(id, "turned off")
        }
    }

    fun settingValues(id: String): Map<String, String> = settingsOf(id).all()

    /** [json]: the value as JSON text, null to go back to the manifest's default; the extension hears of it once the user pauses. */
    fun setSetting(id: String, key: String, json: String?) {
        val s = settingsOf(id)
        if (json == null) s.remove(key) else s.set(key, json)
        val base = Setting.baseKey(key)
        settingJobs.remove("$id\u0000$base")?.cancel()
        settingJobs["$id\u0000$base"] = scope.launch { delay(SETTING_PAUSE_MS); sendSetting(id, base, pressed = false) }
    }

    /** A button setting was pressed: the extension does what it says. */
    fun pressSetting(id: String, key: String) {
        log(id, "setting $key pressed")
        scope.launch { sendSetting(id, key, pressed = true) }
    }

    private suspend fun sendSetting(id: String, key: String, pressed: Boolean) {
        val rt = runtimes[id]?.takeIf { it.listens(ExtensionRuntime.EVENT_SETTING) } ?: return
        val json = buildJsonObject {
            put("key", JsonPrimitive(key))
            if (pressed) put("pressed", JsonPrimitive(true))
        }.toString()
        runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_SETTING, json, SETTING_MS) } }.onSuccess { recheckAfterEvent(id, rt) }.onFailure { failed(id, it.toString()) }
    }

    fun logLines(id: String): List<String> = logOf(id).lines()

    /** [id]'s own quality setting for playback now ("Quality: FLAC"), or null without one. */
    fun qualityLine(id: String): String? {
        val m = manifest(id) ?: return null
        return runCatching { SectionSettings.qualityLine(m, SettingSection.PLAYBACK, settingsOf(id)::get, false) }.getOrNull()
    }

    fun statusOf(id: String): ExtensionStatus? = health.last(id)

    fun cooldownOf(id: String): String? = cooldown.reason(id)

    // ── The source order ──

    /** The running extensions providing [hook], in the order they are asked (a source: the ones above Spotify only). */
    private fun providing(hook: Hook): List<Pair<String, ExtensionRuntime>> = registry.let { r ->
        val action = hook.action
        val area = (action?.area ?: Area.OTHER).key
        val skip = { id: String -> hook in SOURCE_HOOKS && health.inactive(id) }
        val ordered = if (action?.mode == Mode.ORDERED) line(r, action).takeWhile { it != SPOTIFY_SOURCE }.mapNotNull(r::find)
            else r.byPriority(area, r.extensions) { it.id }
        val all = ordered.mapNotNull { e -> runtimes[e.id]?.takeIf { e.enabled && it.provides(hook) && !skip(e.id) }?.let { e.id to it } }
        if (action == null || action.mode != Mode.EXCLUSIVE) all
        else Conflicts.activeFor(all.map { it.first }, r.active[action.key]).let { one -> all.filter { it.first == one } }
    }

    /** The order the user saved for the audio sources: the extensions giving audio and [SPOTIFY_SOURCE]. */
    fun savedSourceLine(): List<String> = savedLine(registry, Action.AUDIO_SOURCE)

    /** The order the audio sources are asked in now: the saved one, with this session's pick first. */
    fun sourceLine(): List<String> = line(registry, Action.AUDIO_SOURCE)

    private fun savedLine(r: Registry, action: Action): List<String> {
        val ids = r.extensions.filter { action in manifest(it.id)?.actionSet().orEmpty() }.map { it.id }
        return r.bySourceOrder(action.key, Area.AUDIO.key, if (action == Action.AUDIO_SOURCE) ids + SPOTIFY_SOURCE else ids) { it }
    }

    private fun line(r: Registry, action: Action): List<String> {
        val saved = savedLine(r, action)
        if (action != Action.AUDIO_SOURCE) return saved
        return SessionSource.effective(saved, SessionSource.valid(sessionSource, saved) { r.find(it)?.enabled == true })
    }

    /**
     * Saves [order] as the audio sources' order: the ids of [savedSourceLine] in the order given
     * (unknown ids dropped, the ones left out after, in their order before), as moving them one by
     * one in Settings would.
     */
    fun setSourceOrder(order: List<String>) = update { r ->
        val now = savedLine(r, Action.AUDIO_SOURCE)
        val given = order.distinct().filter { it in now }
        r.copy(priority = r.priority + (Action.AUDIO_SOURCE.key to given + now.filter { it !in given }))
    }

    /** The audio sources the source picker offers (Spotify and the extensions on), in the order asked now. */
    fun audioChoices(): List<String> = registry.let { r ->
        SessionSource.choices(savedLine(r, Action.AUDIO_SOURCE), sessionSource) { r.find(it)?.enabled == true }
    }

    /** Puts [id] (an extension or [SPOTIFY_SOURCE]) first for this run; null, or the saved leader: back to the saved order. */
    fun setSessionSource(id: String?) {
        val r = registry
        val lead = id?.let { SessionSource.pick(it, savedLine(r, Action.AUDIO_SOURCE)) { x -> r.find(x)?.enabled == true } }
        if (lead == sessionSource) return
        sessionSource = lead
        RunnerLog.line("audio: ${lead?.let { "$it first for this session" } ?: "back to the order of Settings"}")
        refreshProvided()
    }

    private fun keepSessionSource(r: Registry) {
        val lead = sessionSource ?: return
        if (SessionSource.valid(lead, savedLine(r, Action.AUDIO_SOURCE)) { r.find(it)?.enabled == true } != null) return
        sessionSource = null
        RunnerLog.line("audio: $lead no longer a source, back to the order of Settings")
    }

    /** Who gives audio and downloads changed: the songs none had lately are asked again, the answers kept are dropped. */
    private fun refreshProvided() {
        val now = providing(Hook.AUDIO_SOURCE).map { it.first } to providing(Hook.DOWNLOAD_SOURCE).map { it.first }
        if (now == provided) return
        provided = now
        misses.clear()
        known.clear()
        RunnerLog.line("ext: sources now: audio ${now.first.ifEmpty { "none" }}, downloads ${now.second.ifEmpty { "none" }}")
    }

    // ── Audio ──

    /** What an audio ask gave: the [stream] to play (null: Spotify plays), its [attempt] and, without one, why ([reason], a key). */
    class AudioResult(val stream: AudioStream?, val attempt: Long, val reason: String? = null, val expiresAt: Long? = null)

    private class Known(val stream: AudioStream, val attempt: Long, val at: Long = System.currentTimeMillis()) {
        val expiresAt get() = at + stream.keepMs(KEEP_MS)
        val fresh get() = System.currentTimeMillis() < expiresAt
    }
    private val known = ConcurrentHashMap<String, Known>()
    private val asking = ConcurrentHashMap<String, CompletableDeferred<AudioResult?>>()

    /**
     * [track]'s audio, as the Android player's data source resolves it (ExtensionAudio.resolve): an
     * answer kept for the song (asked ahead a moment ago) is played as given; an ask out for it is
     * waited for; else the sources are asked ([audio]). [again]: its address was refused, the kept
     * answer is dropped and the sources are asked anew.
     */
    suspend fun resolve(track: TrackInfo, accept: Accept, ahead: Boolean = false, again: Boolean = false): AudioResult {
        val key = track.id
        if (again) {
            known.remove(key)
            noteAttempt(key) { it.copy(fallback = "asked the extensions again: its address was refused (expired or gone)") }
        }
        known[key]?.takeIf { it.fresh && accept.refusal(it.stream) == null }?.let { return AudioResult(it.stream, it.attempt, expiresAt = it.expiresAt) }
        if (!again) asking[key]?.let { pending -> withTimeoutOrNull(AUDIO_MS) { pending.await() }?.takeIf { it.stream != null && accept.refusal(it.stream) == null }?.let { return it } }
        val mine = CompletableDeferred<AudioResult?>()
        asking[key] = mine
        try {
            val r = audio(track, accept, ahead = ahead)
            val kept = r.stream?.let { Known(it, r.attempt).also { k -> known[key] = k } }
            return AudioResult(r.stream, r.attempt, r.reason, kept?.expiresAt).also { mine.complete(it) }
        } catch (t: Throwable) {
            mine.complete(null); throw t
        } finally {
            asking.remove(key, mine)
        }
    }

    /**
     * Where [track]'s audio is, from the first extension (in the order) that knows and gives what
     * the PC plays. [ahead]: asked before the song plays (the extension is told). One [PlayAttempt].
     */
    suspend fun audio(track: TrackInfo, accept: Accept, timeoutMs: Long = AUDIO_MS, ahead: Boolean = false): AudioResult {
        val attempt = attempts.start(track.id, track.title, ahead)
        if (misses.skips(track.id, ahead)) {
            RunnerLog.line("audio: ${track.title}${if (ahead) " (ahead)" else ""} not asked again: no extension had it under a minute ago")
            attempts.update(attempt) { it.copy(fallback = "Spotify: no extension had it under a minute ago, not asked again") }
            return AudioResult(null, attempt, "missed-lately")
        }
        if (!started.isCompleted) {
            val t0 = System.currentTimeMillis()
            val loaded = withTimeoutOrNull(START_AUDIO_MS) { started.await() } != null
            RunnerLog.line("audio: extensions not loaded yet, waited ${System.currentTimeMillis() - t0} ms${if (loaded) "" else " (still loading)"}")
        }
        val input = AudioQuery.toJson(withIsrc(track), ahead)
        probeStale(Hook.AUDIO_SOURCE)
        val skipped = mutableListOf<PlayAttempt.Ask>()
        registry.extensions.filter { it.enabled && health.inactive(it.id) && runtimes[it.id]?.provides(Hook.AUDIO_SOURCE) == true }
            .forEach {
                val why = "inactive: ${health.last(it.id)?.detail ?: "no detail"}"
                RunnerLog.line("audio: ${it.id} skipped ($why)")
                skipped += PlayAttempt.Ask(it.id, Outcome.Skipped().key, 0, why)
            }
        val asked = available(Hook.AUDIO_SOURCE, skipped)
        if (asked.isEmpty()) {
            attempts.update(attempt) { it.copy(asked = skipped.toList(), fallback = "Spotify: no source to ask") }
            val spotifyFirst = sourceLine().firstOrNull { it == SPOTIFY_SOURCE || registry.find(it)?.enabled == true } == SPOTIFY_SOURCE &&
                registry.extensions.any { it.enabled && runtimes[it.id]?.provides(Hook.AUDIO_SOURCE) == true }
            return AudioResult(null, attempt, if (spotifyFirst) "spotify-first" else "no-source")
        }
        val label = "${track.title}${if (ahead) " (ahead)" else ""}"
        RunnerLog.line("audio: asked ${asked.joinToString { it.first }} for $label")
        val t0 = System.currentTimeMillis()
        val answers = ConcurrentHashMap<String, PlayAttempt.Ask>()
        val unplayable = ConcurrentHashMap.newKeySet<String>()
        val found = firstInOrder(asked) { (id, rt) ->
            val a = ask(id, rt, Hook.AUDIO_SOURCE, input, timeoutMs, AudioStream::parse)
            val refusal = a.value?.let(accept::refusal)
            if (refusal != null) {
                unplayable += id
                log(id, "audio for ${track.title} passed over: $refusal")
            }
            answers[id] = PlayAttempt.Ask(id, if (refusal != null) Outcome.Skipped().key else a.outcome.key, a.ms, detail = refusal ?: a.outcome.detail)
            a.value?.takeIf { refusal == null }
        }
        val ms = System.currentTimeMillis() - t0
        val all = skipped + asked.map { (id, _) -> answers[id] ?: PlayAttempt.Ask(id, Outcome.Skipped().key, ms, "not needed: a source above answered") }
        if (found == null) {
            RunnerLog.line("audio: no extension had $label ($ms ms)"); misses.record(track.id, ahead)
            attempts.update(attempt) { it.copy(asked = all, fallback = "Spotify: no extension had it") }
            return AudioResult(null, attempt, if (unplayable.isNotEmpty()) "not-playable" else "not-found")
        }
        misses.forget(track.id)
        val (c, a) = found
        log(c.first, "audio for ${track.title} ($ms ms)")
        attempts.update(attempt) { it.copy(asked = all, chosen = c.first, announced = a.qualityLabel(), sourceQuality = qualityLine(c.first)) }
        return AudioResult(a.copy(providerId = c.first), attempt)
    }

    /**
     * [track]'s audio from the audio source [id] alone, whatever the order: the one picked in the
     * source picker, asked while the song goes on where it plays; one [PlayAttempt] of its own.
     */
    suspend fun audioFrom(id: String, track: TrackInfo, accept: Accept): AudioResult {
        val rt = runtimes[id]?.takeIf { registry.find(id)?.enabled == true && it.provides(Hook.AUDIO_SOURCE) && !health.inactive(id) }
        val attempt = attempts.start(track.id, track.title)
        val picked = "picked in the player for this session"
        if (rt == null) {
            attempts.update(attempt) { it.copy(asked = listOf(PlayAttempt.Ask(id, Outcome.Skipped().key, 0, "not a running audio source")), fallback = "the song goes on where it played") }
            return AudioResult(null, attempt, "not-available")
        }
        cooldown.reason(id)?.let { why ->
            attempts.update(attempt) { it.copy(asked = listOf(PlayAttempt.Ask(id, Outcome.Skipped().key, 0, "set aside: $why")), fallback = "the song goes on where it played") }
            return AudioResult(null, attempt, "set-aside")
        }
        val a = ask(id, rt, Hook.AUDIO_SOURCE, AudioQuery.toJson(withIsrc(track), false), AUDIO_MS, AudioStream::parse)
        val refusal = a.value?.let(accept::refusal)
        val asked = listOf(PlayAttempt.Ask(id, if (refusal != null) Outcome.Skipped().key else a.outcome.key, a.ms,
            detail = listOfNotNull(picked, refusal ?: a.outcome.detail).joinToString("; ")))
        val found = a.value?.takeIf { refusal == null } ?: run {
            RunnerLog.line("audio: $id had no ${track.title} (${a.ms} ms), it goes on where it played")
            attempts.update(attempt) { it.copy(asked = asked, fallback = "the song goes on where it played") }
            return AudioResult(null, attempt, if (refusal != null) "not-playable" else "not-found")
        }
        misses.forget(track.id)
        log(id, "audio for ${track.title} (${a.ms} ms, picked in the player)")
        attempts.update(attempt) { it.copy(asked = asked, chosen = id, announced = found.qualityLabel(), sourceQuality = qualityLine(id)) }
        val stream = found.copy(providerId = id)
        val kept = Known(stream, attempt).also { known[track.id] = it }
        return AudioResult(stream, attempt, expiresAt = kept.expiresAt)
    }

    /** Notes what the player did with [trackId]'s latest attempt (its address opened, its format, an error). */
    fun noteAttempt(trackId: String?, change: (PlayAttempt) -> PlayAttempt) {
        if (!trackId.isNullOrBlank()) runCatching { attempts.updateLatest(trackId, change) }
    }

    /**
     * [track] with the ISRC an earlier ask of the same song carried, when it lacks one. The Android
     * app reads it from Spotify's catalogue; the runner has no Spotify account, so it only remembers.
     */
    private fun withIsrc(track: TrackInfo): TrackInfo {
        if (track.id.isBlank()) return track
        if (!track.isrc.isNullOrBlank()) { isrcs[track.id] = track.isrc!!; return track }
        return isrcs[track.id]?.let { track.copy(isrc = it) } ?: track
    }

    /** The first non-null answer in [candidates]' order, all asked at once; the ones behind it are cancelled. */
    private suspend fun <C, R : Any> firstInOrder(candidates: List<C>, ask: suspend (C) -> R?): Pair<C, R>? = coroutineScope {
        val asked = candidates.map { c -> c to async { answer { ask(c) } } }
        for ((c, job) in asked) {
            val r = job.await() ?: continue
            asked.forEach { it.second.cancel() }
            return@coroutineScope c to r
        }
        null
    }

    private suspend fun <R> answer(block: suspend () -> R?): R? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ── How a source call ended ──

    private fun available(hook: Hook, skipped: MutableList<PlayAttempt.Ask>? = null): List<Pair<String, ExtensionRuntime>> =
        providing(hook).filter { (id, _) ->
            val why = cooldown.reason(id) ?: return@filter true
            RunnerLog.line("${hook.key}: $id skipped (set aside: $why)")
            skipped?.add(PlayAttempt.Ask(id, Outcome.Skipped().key, 0, "set aside: $why"))
            false
        }

    private class Answer<T>(val value: T?, val outcome: Outcome, val ms: Long)

    private suspend fun <T : Any> ask(id: String, rt: ExtensionRuntime, hook: Hook, input: String, timeoutMs: Long, parse: (String) -> T?): Answer<T> {
        val t0 = System.currentTimeMillis()
        val (value, outcome) = runInterruptible(Dispatchers.IO) { callNow(id, rt, hook, input, timeoutMs, parse) }
        val ms = System.currentTimeMillis() - t0
        if (hook in Outcome.HOOKS) noted(id, hook, outcome, ms)
        return Answer(value, outcome, ms)
    }

    private fun <T : Any> callNow(id: String, rt: ExtensionRuntime, hook: Hook, input: String, timeoutMs: Long, parse: (String) -> T?): Pair<T?, Outcome> {
        busy.merge(id, 1, Int::plus)
        try {
            val raw = rt.call(hook, input, timeoutMs)
            raw?.let { Outcome.parse(hook, it) }?.let { stated -> failures.succeeded(id); return null to stated }
            val out = try { raw?.let(parse) } catch (e: ExtensionException) { log(id, "failed: ${e.message}"); throw e }
            failures.succeeded(id)
            return out to (if (out != null) Outcome.Found else Outcome.NotFound)
        } catch (e: ExtensionBusy) {
            log(id, "${hook.key} skipped: ${e.message}")
            return null to Outcome.of(e)
        } catch (e: ExtensionFault) {
            if (!rt.closed) disable(id, e.message ?: "crashed the sandbox")
            return null to Outcome.Bug(e.message)
        } catch (e: ExtensionException) {
            val o = Outcome.of(e)
            if (!rt.closed) {
                if (o.counts) failed(id, e.message ?: "failed") else log(id, "${hook.key}: ${o.key}, not counted as a failure")
            }
            return null to o
        } catch (e: InterruptedException) {
            throw e
        } catch (t: Throwable) {
            disable(id, t.toString())
            return null to Outcome.Bug(t.toString())
        } finally {
            busy.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } }
        }
    }

    private fun noted(id: String, hook: Hook, o: Outcome, ms: Long) {
        log(id, "${hook.key}: ${o.describe()} ($ms ms)")
        cooldown.note(id, o)?.let { log(id, "set aside for ${it / 1000} s (${o.key}): not asked meanwhile") }
        when (o) {
            is Outcome.SignIn -> signIns[id] = o.detail.orEmpty()
            Outcome.Found -> signIns.remove(id)
            else -> {}
        }
    }

    // ── Whether each source answers: the `status` hook ──

    private suspend fun probe(id: String, rt: ExtensionRuntime) {
        if ((busy[id] ?: 0) > 0) return
        val mine = scope.async(start = CoroutineStart.LAZY) { probeNow(id, rt) }
        val running = probing.putIfAbsent(id, mine)
        if (running != null) { mine.cancel(); running.join(); return }
        mine.invokeOnCompletion { probing.remove(id, mine) }
        mine.await()
    }

    private suspend fun probeNow(id: String, rt: ExtensionRuntime) {
        val t0 = System.currentTimeMillis()
        val s = runInterruptible(Dispatchers.IO) { ExtensionStatus.probe(rt, STATUS_MS) }
        if (runtimes[id] !== rt) return
        val ms = System.currentTimeMillis() - t0
        val changed = health.record(id, s)
        if (changed && s.live) misses.clear()
        if (changed) refreshProvided()
        if (changed) log(id, when (s.state) {
            "live" -> "status: live${s.detail?.let { " ($it)" } ?: ""} in $ms ms"
            "inactive" -> "status: inactive: ${s.detail ?: "no detail"} in $ms ms"
            else -> "status: no answer (${s.detail ?: "no detail"}) in $ms ms, still used"
        })
    }

    private fun probeAll() = runtimes.forEach { (id, rt) -> scope.launch { probe(id, rt) } }

    private suspend fun probeStale(hook: Hook) {
        val due = registry.extensions.mapNotNull { e ->
            runtimes[e.id]?.takeIf { e.enabled && it.provides(hook) && health.last(e.id) != null && health.stale(e.id, STATUS_EVERY_MS) }?.let { e.id to it }
        }
        if (due.isEmpty()) return
        withTimeoutOrNull(STATUS_MS + 500) { coroutineScope { due.map { (id, rt) -> async { probe(id, rt) } }.forEach { it.await() } } }
    }

    private suspend fun recheckAfterEvent(id: String, rt: ExtensionRuntime) {
        probing[id]?.join()
        if (runtimes[id] !== rt) return
        health.forget(id)
        probe(id, rt)
    }

    // ── Work that waits for the network ──

    /** The marker of an `online` event asked and not yet delivered: it survives a restart. */
    private fun onlineMarker(id: String) = File(dir, "$id/online")

    /** The PC is taken as online (the runner runs beside Spotify): the event goes out at once. */
    private fun askOnline(id: String) {
        runCatching { onlineMarker(id).apply { parentFile?.mkdirs() }.createNewFile() }
        deliverOnline(id)
    }

    private fun deliverOnline(id: String) {
        val rt = runtimes[id] ?: return
        if (!onlineMarker(id).delete()) return
        scope.launch { runCatching { runInterruptible { rt.emit(ExtensionRuntime.EVENT_ONLINE, "{}", ONLINE_MS) } }.onSuccess { recheckAfterEvent(id, rt) }.onFailure { failed(id, it.toString()) } }
    }

    // ── Sandboxes ──

    /** Starts [asked] unless that very package already runs; one start of an extension at a time. */
    internal fun start(asked: InstalledExtension, probe: Boolean = true): Unit = synchronized(startLocks.getOrPut(asked.id) { Any() }) {
        val e = registry.find(asked.id) ?: return
        if (!e.enabled) return
        if (runtimes.containsKey(e.id)) { if (runningSha[e.id] == e.sha256) return else stop(e.id) }
        val p = pkg(e.id) ?: return disable(e.id, "its package is missing")
        if (p.sha256 != e.sha256) return disable(e.id, "its package changed on the phone")
        updates.blocked(e.id, e.sha256)?.let { return disable(e.id, "blocked by Myspoti: ${it.reason.ifBlank { "no reason given" }}") }
        if (Grants.refused(e, p.manifest).isNotEmpty()) return disable(e.id, "it waits for your OK on its page")
        var rt = ExtensionRuntime(p.manifest, p.script, host, e.granted)
        try {
            try {
                rt.start()
            } catch (slow: ExtensionTimeout) {
                rt.close()
                log(e.id, "slow to load: trying once more")
                rt = ExtensionRuntime(p.manifest, p.script, host, e.granted, ExtensionRuntime.Limits(loadMs = SLOW_LOAD_MS))
                rt.start()
            }
            val now = registry.find(e.id)
            if (now == null || !now.enabled || now.sha256 != e.sha256) { rt.close(); return }
            runtimes[e.id] = rt
            runningSha[e.id] = e.sha256
            misses.clear()
            refreshProvided()
            log(e.id, "loaded ${p.manifest.version} (granted: ${e.granted.sorted().joinToString().ifBlank { "nothing" }})")
            if (onlineMarker(e.id).exists()) deliverOnline(e.id)
            if (probe) scope.launch { probe(e.id, rt) }
        } catch (t: Throwable) {
            rt.close()
            disable(e.id, "did not load: ${t.message ?: t}")
        }
    }

    internal fun stop(id: String) {
        runningSha.remove(id)
        runtimes.remove(id)?.close()
        health.forget(id)
        cooldown.forget(id); signIns.remove(id)
        misses.clear()
        refreshProvided()
    }

    private fun failed(id: String, message: String) {
        errors[id] = message
        if (failures.failed(id)) disable(id, "failed 3 times in a row: $message")
    }

    /** Turns [id] off with [reason], shown on its page. */
    fun disable(id: String, reason: String) {
        stop(id)
        val e = registry.find(id) ?: return
        update { it.put(e.copy(enabled = false, disabledReason = reason)) }
        errors[id] = reason
        log(id, "turned off: $reason")
    }

    /** Changes the registry and writes it whole (temp file and rename), then brings the sources in step. */
    internal fun update(change: (Registry) -> Registry) {
        synchronized(lock) {
            val r = change(registry)
            registry = r
            runCatching { Registry.write(File(dir, "registry.json"), r) }.onFailure { RunnerLog.line("ext: registry not saved: ${it.message}") }
            keepSessionSource(r)
        }
        refreshProvided()
    }

    fun log(id: String, message: String) {
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logOf(id).add("$stamp $message")
        RunnerLog.line("ext $id: $message")
    }

    private fun logOf(id: String) = logs.getOrPut(id) { ExtensionLog(File(dir, "$id/log.txt")) }
    private fun settingsOf(id: String) = settings.getOrPut(id) { FileStore(File(dir, "$id/settings.json")) }
    private fun storeOf(id: String) = stores.getOrPut(id) { FileStore(File(dir, "$id/storage.json")) }

    // ── What the sandbox reaches of the PC ──

    /**
     * The PC's [Host]. Every call arrives checked by the sandbox (actions granted, hosts listed,
     * request limits). The runner has no screen, no Spotify account and no player of its own: what
     * needs one answers as a host without it does (a refusal, an error or nothing), so an extension
     * meets on the PC exactly the limits it would meet on a phone without that feature.
     */
    private val host = object : Host {
        override fun http(extensionId: String, request: HttpRequest): HttpResponse = send(request, userAgent(extensionId))

        override fun log(extensionId: String, message: String) = this@Runner.log(extensionId, message)

        override fun storage(extensionId: String): Storage = storeOf(extensionId)

        override fun setting(extensionId: String, key: String): String? = settingsOf(extensionId).get(key)

        /** Headless: the message goes to the extension's log. */
        override fun notify(extensionId: String, text: String) = this@Runner.log(extensionId, "notify: $text")

        /** No screen to ask on: a destructive change is refused, as a host without a screen answers. */
        override suspend fun confirm(extensionId: String, text: String): Boolean {
            this@Runner.log(extensionId, "refused (no screen to ask on): ${text.lineSequence().first()}")
            return false
        }

        override fun rawGrants(extensionId: String) = registry.find(extensionId)?.rawGrants.orEmpty()

        /** Sign-in pages need a web view of their own; the runner has none, so `auth.open` fails with this reason. */
        override fun openAuth(extensionId: String, request: app.myspoti.extensions.AuthRequest): String =
            throw UnsupportedOperationException("sign-in pages are not available on the PC")

        override fun whenOnline(extensionId: String) = askOnline(extensionId)
    }
}

/**
 * What the PC can play, from a request's `accept`: `{ codecs: ["flac", "opus", "mp3", "vorbis"], manifest: false }`
 * (a DASH manifest only with `manifest: true`). Audio that needs decrypting (`transform`) is never taken.
 */
class Accept(val codecs: Set<String>, val manifest: Boolean) {
    /** Why [a] cannot be played here, for the attempt's detail; null: it can (or its format is not said). */
    fun refusal(a: AudioStream): String? = when {
        a.transform != null -> "not playable here: its audio is encrypted (transform), which the PC does not decrypt"
        a.manifest != null && !manifest -> "not playable here: a DASH manifest, which the PC does not play"
        else -> formatOf(a)?.takeIf { it !in codecs }?.let { "not playable here: $it is not one of ${codecs.sorted().joinToString()}" }
    }

    companion object {
        val DEFAULT = Accept(setOf("flac", "opus", "mp3", "vorbis"), manifest = false)

        /** The format [a] is in, from the codec it announced, else its MIME type, else its address; null when nothing says. */
        fun formatOf(a: AudioStream): String? = a.codec?.let(::codec) ?: a.mimeType?.let(::mime) ?: if (a.url.isNotEmpty()) extension(a.url) else null

        private fun codec(c: String): String = c.trim().lowercase().let { v ->
            when {
                v == "mp3" || v == "mpeg" || v == "mpga" || v == "mp3float" -> "mp3"
                v.startsWith("mp4a") || v == "aac" || v == "he-aac" || v == "aac-lc" -> "aac"
                v == "ogg" || v == "vorbis" -> "vorbis"
                v == "fla" || v == "flac" -> "flac"
                else -> v
            }
        }

        private fun mime(type: String): String? {
            val t = type.lowercase()
            Regex("codecs\\s*=\\s*\"?([^\";,]+)").find(t)?.let { return codec(it.groupValues[1]) }
            return when (t.substringBefore(';').trim()) {
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/mpeg", "audio/mp3", "audio/mpeg3" -> "mp3"
                "audio/opus" -> "opus"
                "audio/ogg", "audio/vorbis", "application/ogg" -> "vorbis"
                "audio/aac", "audio/aacp", "audio/x-aac", "audio/x-m4a", "audio/m4a" -> "aac"
                // audio/mp4 holds AAC or FLAC: not told by the type alone.
                else -> null
            }
        }

        private fun extension(url: String): String? =
            when (url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
                "flac" -> "flac"
                "mp3" -> "mp3"
                "opus" -> "opus"
                "ogg", "oga" -> "vorbis"
                "m4a", "aac", "m4b" -> "aac"
                else -> null
            }
    }
}
