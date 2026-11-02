// @file Api.kt
// @brief The runner's loopback JSON API for the PC helper: the store, install, settings, source order, audio asks and play attempts.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Catalog
import app.myspoti.extensions.Change
import app.myspoti.extensions.Discovery
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.GitHubRepo
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Origin
import app.myspoti.extensions.PlayAttempt
import app.myspoti.extensions.Ranking
import app.myspoti.extensions.Rating
import app.myspoti.extensions.Setting
import app.myspoti.extensions.Store
import app.myspoti.extensions.TrackInfo
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The runner's API (docs/EXTENSIONS.md of the PC product, "실행기 API"): JSON over HTTP on
 * 127.0.0.1 only, every request with the exact `X-Hangul-Token`, bodies of at most [MAX_BODY].
 * The texts it gives are keys (`error`, `reason`, `result`, outcome keys) and the extensions' own
 * English details: the PC's screens word them.
 */
class Api(private val runner: Runner, private val token: String, private val appVersion: String) : AutoCloseable {
    companion object {
        const val PROTOCOL = 1
        const val MAX_BODY = 1024 * 1024
        /** A preview not confirmed by then is forgotten. */
        private const val PENDING_MS = 30 * 60_000L
        private const val MAX_PENDING = 16
        private val TRACK_ID = Regex("^[A-Za-z0-9]{1,64}$")
        /** A GitHub repository, `owner/name`: the store's download counts exist there only. */
        private val GITHUB_SLUG = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
        private val EXTENSION_PATH = Regex("^/v1/extensions/([A-Za-z0-9._-]{1,200})/(enabled|settings|remove|update)$")
    }

    private class Failure(val status: Int, val key: String, val detail: String? = null, val extra: Map<String, JsonElement> = emptyMap()) : Exception(key)

    /** A package downloaded by a preview, or an update staged, waiting for the user's OK. */
    private class Pending(val update: Boolean, val zip: ByteArray, val pkg: ExtensionPackage, val source: String, val origin: Origin?, val at: Long = System.currentTimeMillis())

    private val pending = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()
    private var server: HttpServer? = null
    private val pool = Executors.newFixedThreadPool(16) { r -> Thread(r, "api").apply { isDaemon = true } }

    /** Listens on 127.0.0.1:[port] (0: any free port); the port taken. */
    fun start(port: Int): Int {
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        s.executor = pool
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
        return s.address.port
    }

    override fun close() {
        server?.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val (status, body) = try {
            if (!ex.remoteAddress.address.isLoopbackAddress) throw Failure(403, "loopback-only")
            val given = ex.requestHeaders.getFirst("X-Hangul-Token").orEmpty()
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) throw Failure(401, "unauthorized")
            val input = readBody(ex)
            200 to runBlocking(Dispatchers.IO) { route(ex.requestMethod.uppercase(), ex.requestURI.rawPath, ex.requestURI.rawQuery, input) }
        } catch (f: Failure) {
            f.status to JsonObject(mapOf("error" to JsonPrimitive(f.key)) + (f.detail?.let { mapOf("detail" to JsonPrimitive(it)) } ?: emptyMap()) + f.extra)
        } catch (e: ExtensionException) {
            400 to buildJsonObject { put("error", "refused"); put("detail", e.message ?: "refused") }
        } catch (e: Exception) {
            RunnerLog.line("api: ${ex.requestURI.rawPath} failed: ${e.javaClass.simpleName}: ${e.message}")
            500 to buildJsonObject { put("error", "internal"); put("detail", e.javaClass.simpleName) }
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.set("Cache-Control", "no-store")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun readBody(ex: HttpExchange): JsonObject? {
        val out = ByteArrayOutputStream()
        var drained = 0L
        ex.requestBody.use { s ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (out.size() <= MAX_BODY) out.write(buf, 0, n)
                // Over the limit: the rest is read and dropped (up to a bound), so the client gets the answer, not a reset.
                else if ((drained + n).also { drained = it } > 8L * MAX_BODY) break
            }
        }
        if (out.size() > MAX_BODY || drained > 0) throw Failure(413, "too-large")
        if (out.size() == 0) return null
        return runCatching { Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())) as JsonObject }.getOrElse { throw Failure(400, "bad-request", "the body is not a JSON object") }
    }

    private suspend fun route(method: String, path: String, query: String?, body: JsonObject?): JsonObject {
        fun get() { if (method != "GET") throw Failure(405, "method") }
        fun post() { if (method != "POST") throw Failure(405, "method") }
        EXTENSION_PATH.matchEntire(path)?.let { m ->
            post()
            val id = m.groupValues[1]
            val e = runner.registry.find(id) ?: throw Failure(404, "unknown-extension")
            return when (m.groupValues[2]) {
                "enabled" -> {
                    runner.setEnabled(id, bool(body, "enabled") ?: throw Failure(400, "bad-request", "enabled: true or false"), runner.manifest(id)?.let(runner::clashes).orEmpty())
                    buildJsonObject { put("extension", extension(runner.registry.find(id) ?: e)) }
                }
                "settings" -> { settings(id, body); buildJsonObject { put("extension", extension(runner.registry.find(id) ?: e)) } }
                "remove" -> { runner.uninstall(id); buildJsonObject { put("removed", true) } }
                else -> update(id)
            }
        }
        return when (path) {
            "/v1/health" -> { get(); buildJsonObject { put("protocol", PROTOCOL); put("version", runner.engineVersion); put("appVersion", appVersion); put("ready", runner.ready) } }
            "/v1/extensions" -> { get(); buildJsonObject { put("extensions", JsonArray(runner.registry.extensions.map(::extension))) } }
            "/v1/store" -> { get(); runner.store.load(force = param(query, "force") == "1"); store() }
            "/v1/store/repos" -> { post(); repositories(body) }
            "/v1/store/install" -> { post(); storeInstall(body) }
            "/v1/store/downloads" -> {
                get()
                val repo = param(query, "repo")?.takeIf { GITHUB_SLUG.matches(it) && GitHubRepo.parse(it)?.slug == it } ?: throw Failure(400, "bad-request", "repo: owner/name")
                buildJsonObject { put("repo", repo); put("downloads", runner.store.downloads(repo)) }
            }
            "/v1/install/preview" -> { post(); preview(text(body, "url") ?: throw Failure(400, "bad-request", "url")) }
            "/v1/install/confirm" -> { post(); confirm(body) }
            "/v1/order" -> {
                if (method == "POST") runner.setSourceOrder((body?.get("order") as? JsonArray ?: throw Failure(400, "bad-request", "order: a list of ids")).mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                else get()
                order()
            }
            "/v1/session" -> {
                // `{ source: null }`: back to the saved order (the player's "back to my order"); nothing is asked.
                if (method == "POST") {
                    if (body?.get("source") !is JsonNull) throw Failure(400, "bad-request", "source: null")
                    runner.setSessionSource(null)
                } else get()
                session()
            }
            "/v1/audio" -> { post(); audio(runner.resolve(track(body), accept(body), ahead = bool(body, "ahead") == true)) }
            "/v1/audio/pick" -> { post(); pick(body) }
            "/v1/audio/again" -> { post(); audio(runner.resolve(track(body), accept(body), again = true, refresh = bool(body, "refresh") == true)) }
            "/v1/attempts/note" -> { post(); note(body) }
            "/v1/attempts/latest" -> {
                get()
                val id = param(query, "track")?.takeIf { TRACK_ID.matches(it) } ?: throw Failure(400, "bad-request", "track")
                buildJsonObject { put("attempt", runner.attempts.latest(id)?.toJson() ?: JsonNull) }
            }
            else -> throw Failure(404, "not-found")
        }
    }

    // ── The store ──

    /**
     * What the store lists, as the Android app's Store tab: the repositories (the official one once
     * it lists an extension, then the user's) in their index order, then the community's extensions
     * in [Ranking]'s order, every one of them with why it is hidden behind "Show all" (null: shown).
     */
    private fun store(): JsonObject {
        val s = runner.store
        val now = System.currentTimeMillis()
        val blocks = runner.updates.blockList
        val ranked = s.ranked()
        val community = s.community()
        return buildJsonObject {
            put("repos", JsonArray(s.shown().map { r ->
                buildJsonObject {
                    put("url", r.url)
                    put("name", r.catalog?.name ?: runCatching { URI(r.url).host }.getOrNull() ?: r.url)
                    put("official", s.isOfficial(r.url))
                    put("loaded", r.catalog != null)
                    put("error", r.error)
                    put("extensions", JsonArray(r.catalog?.extensions.orEmpty().map { storeEntry(r.url, it, null, null) }))
                }
            }))
            put("community", buildJsonObject {
                put("index", Discovery.SOURCE)
                put("loaded", community?.catalog != null)
                put("error", community?.error)
                val hidden = ranked.map { Ranking.hidden(it, blocks, now) }
                put("hidden", hidden.count { it != null })
                put("extensions", JsonArray(ranked.mapIndexed { i, r -> storeEntry(Discovery.SOURCE, r.item, r.rating, hidden[i]) }))
            })
        }
    }

    /** One store row: the index's entry, the button it gets here ([Store.action]), its rating and what it clashes with among the installed. */
    private fun storeEntry(index: String, e: Catalog.Entry, rating: Rating?, hidden: Ranking.Hidden?): JsonObject {
        val installed = runner.registry.find(e.id)
        val m = e.preview()
        val other = runner.registry.extensions.firstOrNull { i ->
            i.id != e.id && (i.id in e.conflictsWith || i.id in e.replaces || runner.manifest(i.id)?.conflictsWith?.contains(e.id) == true)
        }
        return buildJsonObject {
            put("index", index); put("id", e.id); put("name", e.name); put("version", e.version); put("versionCode", e.versionCode)
            put("apiVersion", e.apiVersion); put("needsApi", e.apiVersion > app.myspoti.extensions.API_VERSION)
            put("author", e.author); put("description", e.description)
            put("repo", e.repo); put("owner", e.repo?.let { GitHubRepo.parse(it)?.owner }); put("repoPage", e.repo?.let { GitHubRepo.parse(it)?.page })
            put("homepage", e.homepage?.takeIf { it.startsWith("https://") })
            put("icon", e.iconAddress()?.let { runCatching { Catalog.resolve(index, it) }.getOrNull() }?.takeIf { it.startsWith("https://") })
            put("updatedAt", e.updatedAt); put("category", e.category().key); put("tags", JsonArray(e.tags.map(::JsonPrimitive)))
            put("changelog", e.changelog); put("minAppVersion", e.minAppVersion); put("size", e.size); put("sha256", e.sha256.ifBlank { null })
            put("action", Store.action(e, installed?.versionCode, runner.engineVersion).name.lowercase())
            put("installedVersion", installed?.version)
            put("asks", JsonArray(m.asks().map { a ->
                buildJsonObject {
                    put("key", a.key); put("label", a.action?.label ?: a.key); put("level", a.level.name.lowercase()); put("critical", a.critical)
                    put("why", a.why); put("hosts", JsonArray(a.hosts.map(::JsonPrimitive)))
                }
            }))
            put("critical", m.isCritical())
            // Ratings are read without an account; voting (GitHub's device sign-in) is not on the PC.
            put("rated", e.repo != null)
            put("rating", (rating ?: e.repo?.let { runner.store.ratings[it] })?.takeIf { e.repo != null }?.let { r ->
                buildJsonObject { put("up", r.up); put("down", r.down); put("votes", r.votes); put("stars", Ranking.stars(r)); put("issue", r.issue) }
            } ?: JsonNull)
            put("hidden", hidden?.name?.lowercase()?.replace('_', '-'))
            put("clash", other?.let { o -> buildJsonObject { put("kind", if (o.id in e.replaces) "replaces" else "conflict"); put("other", o.id); put("otherName", o.name) } } ?: JsonNull)
        }
    }

    /** `{ add: url }` or `{ remove: url }`: the user's repositories, kept in `registry.json` (the official one is always read). */
    private fun repositories(body: JsonObject?): JsonObject {
        val add = text(body, "add")?.trim()
        val remove = text(body, "remove")?.trim()
        when {
            add != null -> {
                val uri = runCatching { URI(add) }.getOrNull()
                if (add.length > 2_000 || uri?.scheme != "https" || uri.host.isNullOrBlank()) throw Failure(400, "bad-request", "add: an https address")
                if (!runner.store.isOfficial(add)) runner.store.addRepository(add)
            }
            remove != null -> runner.store.removeRepository(remove)
            else -> throw Failure(400, "bad-request", "add or remove")
        }
        return buildJsonObject { put("repositories", JsonArray(runner.registry.repositories.map(::JsonPrimitive))) }
    }

    /** A store entry downloaded as the Android app does (`Extensions.fetch`): the same offer as a preview, confirmed by `/v1/install/confirm`. */
    private suspend fun storeInstall(body: JsonObject?): JsonObject {
        val index = text(body, "index") ?: throw Failure(400, "bad-request", "index")
        val id = text(body, "id") ?: throw Failure(400, "bad-request", "id")
        runner.store.load(force = false)
        val entry = runner.store.entry(index, id) ?: throw Failure(404, "not-listed")
        val d = runner.store.fetch(index, entry)
        val p = d.pkg
        runner.updates.blocked(p.manifest.id, p.sha256)?.let { throw Failure(409, "blocked", it.reason.ifBlank { "no reason given" }) }
        val source = d.origin?.page() ?: index
        val key = keep(Pending(false, d.zip, p, source, d.origin))
        return offer(key, p, d.origin, source, null)
    }

    // ── Installing ──

    private suspend fun preview(url: String): JsonObject {
        // A repository's address (GitHub, GitLab, Codeberg): its newest release, else its default branch. Anything else: a package's https address.
        val gitHub = GitHubRepo.parse(url)?.takeIf { !url.endsWith(".zip") }
        val d = if (gitHub != null) runner.updates.fromGitHub(gitHub) else runner.updates.downloadPackage(url)
        val p = d.pkg
        runner.updates.blocked(p.manifest.id, p.sha256)?.let { throw Failure(409, "blocked", it.reason.ifBlank { "no reason given" }) }
        val key = keep(Pending(false, d.zip, p, d.origin?.page() ?: url, d.origin))
        return offer(key, p, d.origin, d.origin?.page() ?: url, null)
    }

    /** What the prompt shows for [p]: the manifest, what it asks (an update: what it asks anew marked `new`), the version it replaces, the clashes. */
    private fun offer(key: String, p: ExtensionPackage, origin: Origin?, source: String, changes: List<Change>?): JsonObject {
        val m = p.manifest
        val plan = runner.plan(p)
        val newKeys = (plan as? InstallPlan.Update)?.let { u -> (u.newAsks.map { it.key } + u.refused).toSet() }
        val installed = runner.registry.find(m.id)
        return buildJsonObject {
            put("pending", key)
            put("plan", when (plan) { is InstallPlan.Fresh -> "install"; is InstallPlan.Update -> "update"; is InstallPlan.NotNewer -> "not-newer" })
            put("manifest", buildJsonObject {
                put("id", m.id); put("name", m.name); put("version", m.version); put("versionCode", m.versionCode)
                put("author", m.author); put("description", m.description); m.homepage?.let { put("homepage", it) }
                put("changelog", m.changelog); m.minAppVersion?.let { put("minAppVersion", it) }
                put("icon", icon(p.icon))
            })
            put("compatible", app.myspoti.extensions.AppVersion.atLeast(runner.engineVersion, m.minAppVersion))
            put("sha256", p.sha256)
            put("source", source)
            put("origin", origin?.let(::origin) ?: JsonNull)
            put("asks", JsonArray(m.asks().map { a ->
                buildJsonObject {
                    put("key", a.key); put("label", a.action?.label ?: a.key); put("level", a.level.name.lowercase()); put("critical", a.critical)
                    put("why", a.why); put("hosts", JsonArray(a.hosts.map(::JsonPrimitive)))
                    put("new", newKeys == null || a.key in newKeys)
                }
            }))
            put("critical", m.isCritical())
            put("replaces", installed?.let { buildJsonObject { put("version", it.version); put("versionCode", it.versionCode) } } ?: JsonNull)
            put("clashes", JsonArray(runner.clashes(m).map { c ->
                buildJsonObject {
                    put("kind", c.kind.name.lowercase()); put("other", c.otherId); put("otherName", runner.registry.find(c.otherId)?.name ?: c.otherId)
                    c.action?.let { put("action", it.key) }
                }
            }))
            changes?.let { put("changes", JsonArray(it.map { c -> Json.encodeToJsonElement(Change.serializer(), c) })) }
        }
    }

    private fun keep(p: Pending): String {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.at > PENDING_MS }
        while (pending.size >= MAX_PENDING) pending.entries.minByOrNull { it.value.at }?.let { pending.remove(it.key) }
        val key = ByteArray(18).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        pending[key] = p
        return key
    }

    /**
     * Installs the package a preview downloaded (or applies the update [update] staged) with the
     * actions the user accepted. As on Android, installing grants every action it lists: the user
     * who refuses one does not install it, so [granted] must hold them all. `keep`: per clashing
     * extension id, false keeps that one and leaves the new one off (default: the new one acts).
     */
    private suspend fun confirm(body: JsonObject?): JsonObject {
        val key = text(body, "pending") ?: throw Failure(400, "bad-request", "pending")
        val p = pending[key]?.takeIf { System.currentTimeMillis() - it.at <= PENDING_MS } ?: throw Failure(404, "unknown-pending")
        val granted = (body?.get("granted") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: throw Failure(400, "bad-request", "granted: a list of action keys")
        val m = p.pkg.manifest
        val missing = Grants.keys(m) - granted
        if (missing.isNotEmpty()) throw Failure(400, "not-granted", "every action it lists must be accepted", mapOf("missing" to JsonArray(missing.sorted().map(::JsonPrimitive))))
        val keepNew = (body["keep"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.booleanOrNull != false }
        pending.remove(key)
        if (p.update) {
            runner.updates.accept(m.id)
        } else {
            if (runner.plan(p.pkg) is InstallPlan.NotNewer) throw Failure(409, "not-newer")
            runner.install(p.zip, p.pkg, Grants.keys(m), p.source, runner.clashes(m), { keepNew[it.otherId] != false }, noticeAccepted = true, origin = p.origin)
        }
        val e = runner.registry.find(m.id) ?: throw Failure(500, "internal", "not installed")
        return buildJsonObject { put("extension", extension(e)) }
    }

    /** Checks [id]'s repository for an update and applies it when it asks nothing new; one that asks more answers a `pending` to confirm. */
    private suspend fun update(id: String): JsonObject {
        val r = runner.updates.check(id, manual = true)
        val e = runner.registry.find(id) ?: throw Failure(404, "unknown-extension")
        return buildJsonObject {
            put("result", r.key)
            val staged = if (r == Updates.Result.NEEDS_OK) runner.updates.staged(id) else null
            if (staged != null) {
                val key = keep(Pending(true, ByteArray(0), staged, e.source, e.pendingUpdate?.kept?.origin))
                put("pending", offer(key, staged, e.pendingUpdate?.kept?.origin, e.source, e.pendingUpdate?.changes))
            }
            put("extension", extension(e))
        }
    }

    // ── Settings ──

    /**
     * `{ values: { key: value | null } }` (null: back to the default; `key@cell`: a per-network
     * setting's mobile data value), `{ press: key }` for a button. Each value is checked against the
     * setting's type: the page that sends them is not trusted.
     */
    private fun settings(id: String, body: JsonObject?) {
        val m = runner.manifest(id) ?: throw Failure(404, "unknown-extension")
        (body?.get("values") as? JsonObject)?.forEach { (key, value) ->
            val s = m.settings.firstOrNull { it.key == Setting.baseKey(key) } ?: throw Failure(400, "unknown-setting", key)
            if (key != s.key && !s.splitsByNetwork) throw Failure(400, "unknown-setting", key)
            if (s.type == "button") throw Failure(400, "bad-value", "$key is a button: press it")
            if (value !is JsonNull && !fits(s, value)) throw Failure(400, "bad-value", key)
        }
        (body?.get("values") as? JsonObject)?.forEach { (key, value) -> runner.setSetting(id, key, if (value is JsonNull) null else value.toString()) }
        text(body, "press")?.let { key ->
            if (m.settings.none { it.key == key && it.type == "button" }) throw Failure(400, "unknown-setting", key)
            runner.pressSetting(id, key)
        }
    }

    private fun fits(s: Setting, v: JsonElement): Boolean {
        val p = v as? JsonPrimitive ?: return false
        return when (s.type) {
            "toggle" -> p.booleanOrNull != null && !p.isString
            "number" -> p.doubleOrNull != null && !p.isString
            "choice" -> p.isString && p.content in s.options
            else -> p.isString && p.content.length <= 4_096
        }
    }

    // ── The audio sources ──

    private fun order() = buildJsonObject {
        put("order", JsonArray(runner.savedSourceLine().map(::JsonPrimitive)))
        put("asked", JsonArray(runner.sourceLine().map(::JsonPrimitive)))
        put("session", runner.sessionSource?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun session() = buildJsonObject {
        put("source", runner.sessionSource?.let(::JsonPrimitive) ?: JsonNull)
        put("choices", JsonArray(runner.audioChoices().map(::JsonPrimitive)))
    }

    /** The source picked in the source picker: first for this run (the next songs too), and asked alone for this one. */
    private suspend fun pick(body: JsonObject?): JsonObject {
        val source = text(body, "source") ?: throw Failure(400, "bad-request", "source")
        val t = track(body)
        if (source !in runner.savedSourceLine()) throw Failure(404, "unknown-source")
        runner.setSessionSource(source)
        // Spotify picked: nothing is asked (as on Android, Spotify plays the song; no attempt is made).
        if (source == SPOTIFY_SOURCE) return audio(Runner.AudioResult(null, -1, "spotify-picked"))
        return audio(runner.audioFrom(source, t, accept(body)))
    }

    private fun audio(r: Runner.AudioResult): JsonObject = buildJsonObject {
        val s = r.stream
        put("found", s != null)
        put("attempt", runner.attempts.last().firstOrNull { it.id == r.attempt }?.toJson() ?: JsonNull)
        if (s == null) { put("reason", r.reason ?: "not-found"); return@buildJsonObject }
        put("extension", s.providerId)
        put("name", s.providerId?.let { runner.registry.find(it)?.name })
        put("stream", buildJsonObject {
            put("url", s.url)
            put("headers", JsonObject(s.headers.mapValues { JsonPrimitive(it.value) }))
            put("mimeType", s.mimeType)
            put("codec", s.codec ?: Accept.formatOf(s))
            put("bitDepth", s.bitDepth)
            put("sampleRate", s.sampleRate)
            put("quality", s.qualityLabel())
            put("durationMs", s.durationMs)
            put("expiresAt", r.expiresAt)
            if (s.manifest != null) put("manifest", s.manifest)
        })
    }

    private fun note(body: JsonObject?): JsonObject {
        val trackId = text(body, "trackId")?.takeIf { TRACK_ID.matches(it) } ?: throw Failure(400, "bad-request", "trackId")
        val open = (body?.get("open") as? JsonObject)?.let { o ->
            PlayAttempt.Open(
                ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                ms = (o["ms"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0,
                httpStatus = (o["httpStatus"] as? JsonPrimitive)?.intOrNull,
                error = (o["error"] as? JsonPrimitive)?.contentOrNull?.take(300),
                url = (o["url"] as? JsonPrimitive)?.contentOrNull?.take(2_000),
            )
        }
        val detected = text(body, "detected")?.take(120)
        val fallback = text(body, "fallback")?.take(200)
        val error = text(body, "error")?.take(300)
        runner.noteAttempt(trackId) { a ->
            a.copy(open = open ?: a.open, detected = detected ?: a.detected, fallback = fallback ?: a.fallback, error = error ?: a.error)
        }
        return buildJsonObject { put("attempt", runner.attempts.latest(trackId)?.toJson() ?: JsonNull) }
    }

    // ── What the screens read of an extension ──

    private fun extension(e: InstalledExtension): JsonObject {
        val p = runner.pkg(e.id)
        val m: Manifest? = p?.manifest
        val stored = runner.settingValues(e.id)
        return buildJsonObject {
            put("id", e.id); put("name", e.name); put("version", e.version); put("versionCode", e.versionCode); put("author", e.author)
            put("description", m?.description.orEmpty()); m?.homepage?.let { put("homepage", it) }
            put("icon", icon(p?.icon))
            put("enabled", e.enabled)
            put("running", runner.isRunning(e.id))
            put("disabledReason", e.disabledReason)
            put("error", runner.errors[e.id])
            put("status", runner.statusOf(e.id)?.let { s -> buildJsonObject { put("state", s.state); put("live", s.live); put("detail", s.detail) } } ?: JsonNull)
            put("cooldown", runner.cooldownOf(e.id))
            put("signIn", runner.signIns[e.id])
            put("hooks", JsonArray(e.hooks.map(::JsonPrimitive)))
            put("permissions", JsonArray(m?.asks().orEmpty().map { a ->
                buildJsonObject {
                    put("key", a.key); put("label", a.action?.label ?: a.key); put("level", a.level.name.lowercase()); put("critical", a.critical)
                    put("why", a.why); put("hosts", JsonArray(a.hosts.map(::JsonPrimitive))); put("granted", a.key in e.granted)
                }
            }))
            put("settings", JsonArray(m?.settings.orEmpty().map { s ->
                buildJsonObject {
                    put("key", s.key); put("type", s.type); put("label", s.label); put("description", s.description)
                    put("default", s.default ?: JsonNull); put("options", JsonArray(s.options.map(::JsonPrimitive)))
                    put("section", s.section); put("role", s.role); put("perNetwork", s.perNetwork)
                    put("set", stored[s.key] != null)
                    // A secret's value never leaves the runner: the screen only learns whether one is set.
                    if (s.type != "secret") {
                        put("value", stored[s.key]?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
                        if (s.splitsByNetwork) put("cellValue", stored[Setting.cellKey(s.key)]?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
                    }
                }
            }))
            put("source", e.source)
            put("origin", e.origin?.let(::origin) ?: JsonNull)
            put("sha256", e.sha256)
            put("installedAt", e.installedAt)
            put("previous", e.previous?.let { buildJsonObject { put("version", it.version); put("versionCode", it.versionCode) } } ?: JsonNull)
            put("pendingUpdate", e.pendingUpdate?.let { u ->
                buildJsonObject {
                    put("version", u.kept.version); put("versionCode", u.kept.versionCode); put("changelog", u.changelog)
                    put("changes", JsonArray(u.changes.map { Json.encodeToJsonElement(Change.serializer(), it) }))
                }
            } ?: JsonNull)
            put("sourceGone", e.sourceGone)
            put("updateBlocked", e.updateBlocked)
        }
    }

    private fun origin(o: Origin) = buildJsonObject {
        put("repo", o.repo); put("ref", o.ref); put("tag", o.tag); put("commit", o.commit); put("page", o.page())
    }

    private fun icon(bytes: ByteArray?): JsonElement = bytes?.let { JsonPrimitive("data:image/png;base64," + Base64.getEncoder().encodeToString(it)) } ?: JsonNull

    // ── Reading a request ──

    /** The query parameter [name], decoded; null when absent. */
    private fun param(query: String?, name: String): String? =
        query.orEmpty().split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }

    private fun text(body: JsonObject?, key: String): String? = (body?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(body: JsonObject?, key: String): Boolean? = (body?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** The song of an audio request, as the hooks receive a track (the Android app's trackInfo). */
    private fun track(body: JsonObject?): TrackInfo {
        val t = body?.get("track") as? JsonObject ?: throw Failure(400, "bad-request", "track")
        fun s(k: String) = (t[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = s("id")?.takeIf { TRACK_ID.matches(it) } ?: throw Failure(400, "bad-request", "track.id: a Spotify track id")
        val artists = (t["artists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
            ?: s("artist").orEmpty().split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        return TrackInfo(
            id = id, uri = "spotify:track:$id", title = s("title").orEmpty().take(500),
            artist = artists.firstOrNull().orEmpty(), artists = artists.take(20), album = s("album").orEmpty().take(500),
            durationMs = (t["durationMs"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0,
            isrc = s("isrc")?.trim()?.uppercase()?.takeIf { it.matches(Regex("[A-Z0-9]{12}")) },
            cover = s("cover")?.takeIf { it.startsWith("http") },
        )
    }

    /** `accept: { codecs, manifest }`; absent: what the PC plays today ([Accept.DEFAULT]). */
    private fun accept(body: JsonObject?): Accept {
        val a = body?.get("accept") as? JsonObject ?: return Accept.DEFAULT
        val codecs = (a["codecs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() }?.toSet() ?: Accept.DEFAULT.codecs
        return Accept(codecs, (a["manifest"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }
}
