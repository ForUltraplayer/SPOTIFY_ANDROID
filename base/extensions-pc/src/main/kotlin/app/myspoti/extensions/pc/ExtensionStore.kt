// @file ExtensionStore.kt
// @brief The store as the Android app reads it: the official index, the user's repositories and the code hosts' topic search, with ratings and download counts.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Catalog
import app.myspoti.extensions.CodeHost
import app.myspoti.extensions.Discovery
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.GitHubRepo
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Rating
import app.myspoti.extensions.Ranking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Ported from the Android app's ExtensionStore.kt (and `Extensions.catalog` / `fetch`): what the
 * store lists is what Myspoti lists, by the same rules. The official repository is always read and
 * shown once it lists an extension; the user's repositories (kept in `registry.json`, as on
 * Android) follow; then every public repository with the topic [Discovery.TOPIC] on GitHub, GitLab
 * and Codeberg, ordered and hidden by [Ranking]. Nothing is downloaded here but indexes, manifests
 * and counts: a package only when the user asks to install one ([fetch]).
 *
 * Indexes are read once per run (Refresh: again, past the CDN's cache); the searches, manifests,
 * ratings and download counts are kept 6 hours in `cache/` ([Discovery.CACHE_MS]).
 */
class ExtensionStore(private val ext: Runner) {
    companion object {
        /** The official repository: reviewed (one pull request per extension), never removable. */
        const val OFFICIAL_REPO = "https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/index.json"
        private const val MAX_INDEX = 1024 * 1024
        private const val MAX_SEARCH = 2 * 1024 * 1024
        private const val MAX_TEXT = 512 * 1024
    }

    /** One repository's index: [catalog] once read, [error] if it could not be. */
    class Repo(val url: String, val catalog: Catalog? = null, val error: String? = null)

    private val repos = ConcurrentHashMap<String, Repo>()
    @Volatile private var discovered: Repo? = null
    private val loading = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Per repository (`owner/name`), its 👍 / 👎 (read without an account). */
    @Volatile var ratings: Map<String, Rating> = emptyMap()
        private set

    /** The repositories read: the official one first, then the user's. */
    fun sources(): List<String> = (listOf(OFFICIAL_REPO) + ext.registry.repositories).distinct()

    fun isOfficial(url: String) = url == OFFICIAL_REPO

    /** The repositories in [sources] order, the official one left out until it lists an extension (as on Android). */
    fun shown(): List<Repo> = sources().mapNotNull { url ->
        val r = repos[url] ?: Repo(url)
        if (isOfficial(url) && r.catalog?.extensions.isNullOrEmpty()) null else r
    }

    fun community(): Repo? = discovered

    /** The discovered extensions ranked, every one of them (the hidden ones are told by [Ranking.hidden]). */
    fun ranked(): List<Ranking.Ranked<Catalog.Entry>> {
        val r = ratings
        return Ranking.order(discovered?.catalog?.extensions.orEmpty().map { Ranking.Ranked(it, it.id, null, it.repo?.let(r::get), Ranking.time(it.updatedAt)) })
    }

    // ── Repositories ──

    fun addRepository(url: String) = ext.update { r -> if (url in r.repositories) r else r.copy(repositories = r.repositories + url) }

    fun removeRepository(url: String) {
        ext.update { r -> r.copy(repositories = r.repositories - url) }
        repos.remove(url)
    }

    /**
     * Reads the indexes not read yet (all of them when [force]) and the topic search. [force] is the
     * store's Refresh: as on Android it also re-reads the kill switch and, in the background, looks
     * for newer versions of the extensions installed from a repository.
     */
    suspend fun load(force: Boolean) = loading.withLock {
        withContext(Dispatchers.IO) {
            val urls = sources()
            repos.keys.retainAll(urls.toSet())
            if (force) {
                runCatching { ext.updates.refreshBlocks(force = true) }.onFailure { RunnerLog.line("store: blocked.json not read: ${it.message}") }
                background.launch {
                    ext.registry.extensions.filter { it.origin != null }.forEach { runCatching { ext.updates.check(it.id, manual = false) } }
                }
            }
            coroutineScope {
                val indexes = urls.filter { force || repos[it]?.catalog == null }.map { url ->
                    async {
                        val r = runCatching { catalog(url, fresh = force) }
                        repos[url] = Repo(url, r.getOrNull() ?: repos[url]?.catalog, r.exceptionOrNull()?.let { it.message ?: it.toString() })
                    }
                }
                val community = if (force || discovered?.catalog == null) async { discovered = discover(force) } else null
                indexes.awaitAll()
                community?.await()
            }
        }
    }

    /** Reads a repository's index, and turns off what it blocks. */
    private fun catalog(url: String, fresh: Boolean): Catalog {
        // Refresh: a throwaway query makes the CDN in front of raw.githubusercontent.com (cached for minutes) answer anew.
        val at = if (fresh) url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis() else url
        val a = ext.updates.get(at, MAX_INDEX)
        if (a.status !in 200..299) throw ExtensionException("the server answered ${a.status}")
        val c = Catalog.parse((a.body ?: throw ExtensionException("the server sent nothing")).decodeToString())
        ext.registry.extensions.forEach { e ->
            c.blocks(e.id, e.sha256)?.let { b -> if (e.enabled) ext.disable(e.id, "blocked by ${c.name}: ${b.reason.ifBlank { "no reason given" }}") }
        }
        return c
    }

    /**
     * The repositories with the topic: one search per host (one failing leaves the others'
     * results), their manifests from the hosts' file addresses (8 at a time), one search for the
     * ratings issues. A repository whose manifest is missing or refused is left out.
     */
    private suspend fun discover(force: Boolean): Repo = coroutineScope {
        val before = discovered?.catalog
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val r = runCatching {
            val found = CodeHost.entries.map { h ->
                async {
                    runCatching {
                        Discovery.parse(h, cached(Discovery.searchUrl(h), MAX_SEARCH, force) ?: throw ExtensionException("${h.label} search answered 404"))
                    }.getOrElse { failed += "${h.label}: ${it.message ?: it}"; emptyList() }
                }
            }.awaitAll().flatten()
            if (failed.size == CodeHost.entries.size) throw ExtensionException(failed.joinToString("; "))
            val gate = Semaphore(8)
            val entries = found.map { f ->
                async {
                    gate.withPermit {
                        runCatching {
                            val text = f.manifestUrl()?.let { cached(it, MAX_TEXT, force) } ?: return@runCatching null
                            Manifest.parse(text).takeIf { it.problems().isEmpty() }?.let { Discovery.entry(f, it) }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            // Ratings are GitHub issues' reactions: GitHub's repositories only.
            runCatching { cached(Discovery.RATINGS_URL, MAX_SEARCH, force) }.getOrNull()
                ?.let { ratings = Discovery.parseRatings(it, found.filter { f -> f.host == CodeHost.GITHUB }.map { f -> f.fullName }.toSet()) }
            Catalog(name = "Community", extensions = entries.distinctBy { it.id })
        }
        Repo(Discovery.SOURCE, r.getOrNull() ?: before,
            r.exceptionOrNull()?.let { it.message ?: it.toString() } ?: failed.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** The download count of [repo]'s release package (`owner/name` on GitHub), cached 6 hours; null without one. */
    fun downloads(repo: String): Long? = runCatching { cached(Discovery.releaseUrl(repo), MAX_TEXT)?.let(Discovery::parseDownloads) }.getOrNull()

    // ── Installing ──

    /** The entry [id] of the index [index] ([Discovery.SOURCE]: the discovered ones), once read. */
    fun entry(index: String, id: String): Catalog.Entry? {
        val c = if (index == Discovery.SOURCE) discovered?.catalog else repos[index]?.takeIf { index in sources() }?.catalog
        return c?.extensions?.firstOrNull { it.id == id }
    }

    /**
     * The package of an index entry: from its code host when the entry names a repository (the
     * index's sha256 checked when it gives one), else from its address, sha256 checked before
     * anything of it is read. Either way it must be the extension the index lists.
     */
    suspend fun fetch(indexUrl: String, entry: Catalog.Entry): Updates.Download = withContext(Dispatchers.IO) {
        val repo = entry.repo?.let { GitHubRepo.parse(it) }
        if (repo == null) {
            val a = ext.updates.get(Catalog.resolve(indexUrl, entry.url), ExtensionPackage.MAX_ZIP)
            if (a.status !in 200..299) throw ExtensionException("the server answered ${a.status}")
            val bytes = a.body ?: throw ExtensionException("the server sent nothing")
            if (ExtensionPackage.sha256(bytes) != entry.sha256.lowercase()) throw ExtensionException("the package is not the one the repository lists (sha256 differs)")
            val p = ExtensionPackage.read(bytes)
            if (p.manifest.id != entry.id) throw ExtensionException("the package is ${p.manifest.id}, the repository lists ${entry.id}")
            return@withContext Updates.Download(bytes, p, null)
        }
        val f = ext.updates.fromGitHub(repo, expectSha = entry.sha256.ifBlank { null })
        if (f.pkg.manifest.id != entry.id) throw ExtensionException("the package is ${f.pkg.manifest.id}, the store lists ${entry.id}")
        f
    }

    // ── A small cache of the hosts' answers (the searches, manifests, ratings, counts) ──

    /**
     * [url]'s body, from `cache/` when younger than 6 hours, else fetched and kept; null on 404.
     * [force] (Refresh) skips the cache and reports a failure instead of falling back to the old copy,
     * except a rate limit (403 / 429), which keeps the old copy: hosts allow few searches a minute without an account.
     */
    private fun cached(url: String, max: Int, force: Boolean = false): String? {
        val f = File(ext.dir, "cache/" + ExtensionPackage.sha256(url.toByteArray()).take(24))
        if (!force && f.isFile && System.currentTimeMillis() - f.lastModified() < Discovery.CACHE_MS) return f.readText().ifEmpty { null }
        val a = runCatching { ext.updates.get(url, max, accept = if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else null) }
            .getOrElse { if (f.isFile && !force) return f.readText().ifEmpty { null } else throw it }
        if (a.status == 404) { writeAtomically(f, ""); return null }
        if (a.status !in 200..299) {
            val limited = a.status == 403 || a.status == 429
            if (f.isFile && (!force || limited)) return f.readText().ifEmpty { null }
            val host = runCatching { URI(url).host }.getOrNull() ?: "the host"
            throw ExtensionException("$host answered ${a.status}" + if (limited) " (too many requests: try again in a few minutes)" else "")
        }
        val text = a.body?.decodeToString().orEmpty()
        writeAtomically(f, text)
        return text
    }
}
