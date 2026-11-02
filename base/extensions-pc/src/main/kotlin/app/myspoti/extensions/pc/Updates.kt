// @file Updates.kt
// @brief Downloads from code hosts and package addresses, the update check and its gate, and Myspoti's blocked list.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Archive
import app.myspoti.extensions.BlockList
import app.myspoti.extensions.Catalog
import app.myspoti.extensions.CodeDiff
import app.myspoti.extensions.CodeHost
import app.myspoti.extensions.Discovery
import app.myspoti.extensions.ExtensionException
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.GitHub
import app.myspoti.extensions.GitHubRepo
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Origin
import app.myspoti.extensions.RepoIds
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.UpdateGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** One answer of a download: [status], the [body] (at most the limit asked), the redirect [location] when not followed, the [finalUrl]. */
class Fetched(val status: Int, val body: ByteArray?, val location: String?, val finalUrl: String)

/**
 * Where packages, repository files and the blocked list are downloaded from. [Https] in use; a test
 * gives its own, so nothing reaches the network.
 */
fun interface Fetcher {
    fun get(url: String, max: Int, follow: Boolean, accept: String?): Fetched

    /** https only, no cookies, no cache (as the Android app's downloads). */
    object Https : Fetcher {
        override fun get(url: String, max: Int, follow: Boolean, accept: String?): Fetched {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000; c.useCaches = false
            c.instanceFollowRedirects = follow
            c.setRequestProperty("User-Agent", "Myspoti-Extensions/1")
            c.setRequestProperty("Cache-Control", "no-cache"); c.setRequestProperty("Pragma", "no-cache")
            accept?.let { c.setRequestProperty("Accept", it) }
            try {
                val status = c.responseCode
                val bytes = (if (status in 200..299) c.inputStream else null)?.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) throw ExtensionException("larger than ${max / 1024} KB")
                    }
                    out.toByteArray()
                }
                return Fetched(status, bytes, c.getHeaderField("Location"), c.url.toString())
            } finally {
                c.disconnect()
            }
        }
    }
}

/**
 * Ported from the Android app's ExtensionUpdates.kt: the download from a code host (the release's
 * package, else the archive of the tag or the default branch, cut down to a package), the update
 * check and its gate ([UpdateGate]: an update that asks nothing new is applied, anything else waits
 * for the user's OK while the old version runs), and the kill switch (the official repository's
 * `blocked.json`). The copy on disk is complete: a repository gone changes nothing to what runs.
 */
class Updates(private val ext: Runner, private val fetcher: Fetcher) {
    private val dayMs = 24 * 3600 * 1000L
    private val maxText = 512 * 1024

    /** A package downloaded and read, with where it came from on a code host (null: an address). */
    class Download(val zip: ByteArray, val pkg: ExtensionPackage, val origin: Origin?)

    /** What a check found. */
    enum class Result(val key: String) {
        UP_TO_DATE("up-to-date"), APPLIED("applied"), WAITING("waiting"), REPO_CHANGED("repo-changed"), NEEDS_OK("needs-ok"),
        DECLINED("declined"), SOURCE_GONE("source-gone"), INCOMPATIBLE("incompatible"), BLOCKED("blocked"),
        NOT_FROM_GITHUB("not-from-a-repository"), FAILED("failed"),
    }

    @Volatile private var blocks = BlockList.EMPTY

    fun blocked(id: String, sha256: String?): Catalog.Block? = blocks.blocks(id, sha256)

    /** The kill switch as last read: what the store hides as blocked or flagged ([app.myspoti.extensions.Ranking.hidden]). */
    val blockList: BlockList get() = blocks

    /** https only. [follow]: follow redirects (else [Fetched.location] is the target). */
    internal fun get(url: String, max: Int, follow: Boolean = true, accept: String? = null): Fetched {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw ExtensionException("an https address is needed")
        return fetcher.get(url, max, follow, accept)
    }

    /** A package at an https address. */
    fun downloadPackage(url: String): Download {
        val a = get(url, ExtensionPackage.MAX_ZIP)
        if (a.status !in 200..299) throw ExtensionException("the server answered ${a.status}")
        val bytes = a.body ?: throw ExtensionException("the server sent nothing")
        return Download(bytes, ExtensionPackage.read(bytes), null)
    }

    private fun latestTag(repo: GitHubRepo): Pair<String?, String?> {
        val a = get(repo.latestRelease, maxText, follow = false)
        return GitHub.tagFromLocation(a.location) to GitHub.repoOf(a.location)
    }

    private fun concrete(repo: GitHubRepo, ref: String): String {
        if (ref != GitHubRepo.HEAD) return ref
        val api = repo.defaultBranchApi ?: return ref
        return runCatching { get(api, maxText).body?.decodeToString()?.let(Discovery::parseDefaultBranch) }.getOrNull() ?: "main"
    }

    internal fun identity(repo: GitHubRepo): RepoIds? = runCatching {
        val a = get(Discovery.identityUrl(repo), maxText, accept = if (repo.host == CodeHost.GITHUB) "application/vnd.github+json" else null)
        if (a.status in 200..299) a.body?.decodeToString()?.let { Discovery.parseIdentity(repo.host, it) } else null
    }.getOrNull()

    private class SourceGone : ExtensionException("its GitHub repository is gone")

    /** The complete extension from [repo]: at [ref] when given, else the newest release, else the default branch. */
    suspend fun fromGitHub(repo: GitHubRepo, ref: String? = null, tag: Boolean = false, expectSha: String? = null): Download = withContext(Dispatchers.IO) {
        var at = ref
        var isTag = tag
        var movedTo: String? = null
        if (at == null) latestTag(repo).let { (t, r) -> at = t; isTag = t != null; movedTo = r }
        var asset = false
        var answer: Fetched? = null
        if (isTag && at != null) {
            answer = repo.releaseAsset(at!!)?.let { get(it, GitHub.MAX_ARCHIVE) }?.takeIf { it.status in 200..299 }
            asset = answer != null
        }
        if (answer == null) {
            answer = get(repo.archive(at ?: concrete(repo, GitHubRepo.HEAD), isTag), GitHub.MAX_ARCHIVE)
            if (answer.status == 404) throw SourceGone()
            if (answer.status !in 200..299) throw ExtensionException("${repo.host.label} answered ${answer.status}")
        }
        val raw = answer.body ?: throw ExtensionException("${repo.host.label} sent nothing")
        val zip = Archive.toPackage(raw)
        if (expectSha != null && ExtensionPackage.sha256(raw) != expectSha.lowercase() && ExtensionPackage.sha256(zip) != expectSha.lowercase())
            throw ExtensionException("the package is not the one the store lists (sha256 differs)")
        val pkg = ExtensionPackage.read(zip)
        val from = movedTo ?: GitHub.repoOf(answer.finalUrl) ?: repo.slug
        val ids = identity(GitHubRepo.parse(from) ?: repo)
        Download(zip, pkg, Origin(from, at ?: GitHubRepo.HEAD, isTag, commit = if (asset) null else Archive.commit(raw), asset = asset, repoId = ids?.repo, ownerId = ids?.owner))
    }

    /** Once at start, then every 6 hours while the runner runs: the kill switch and the updates that are due. */
    suspend fun daily() {
        while (true) {
            runCatching { refreshBlocks() }.onFailure { RunnerLog.line("ext: blocked.json not read: ${it.message}") }
            runCatching { checkAll(force = false) }
            delay(6 * 3600 * 1000L)
        }
    }

    suspend fun checkAll(force: Boolean) {
        val now = System.currentTimeMillis()
        ext.registry.extensions.filter { it.origin != null && (force || now - it.lastCheck > dayMs) }.forEach { check(it.id, manual = force) }
    }

    /** Looks for a newer version of [id] on its code host, as the Android app does. */
    suspend fun check(id: String, manual: Boolean): Result = withContext(Dispatchers.IO) {
        val e = ext.registry.find(id) ?: return@withContext Result.FAILED
        val origin = e.origin ?: return@withContext Result.NOT_FROM_GITHUB
        val repo = origin.gitHub() ?: return@withContext Result.NOT_FROM_GITHUB
        val result = runCatching { checkOnce(e, origin, repo, manual) }.getOrElse { t ->
            if (t is SourceGone) {
                ext.update { r -> r.find(id)?.let { r.put(it.copy(sourceGone = true)) } ?: r }
                if (!e.sourceGone) ext.log(id, "its GitHub repository ${origin.repo} is gone: it keeps running from the copy on the phone")
                Result.SOURCE_GONE
            } else {
                ext.log(id, "update check failed: ${t.message ?: t}")
                Result.FAILED
            }
        }
        if (result != Result.FAILED) ext.update { r -> r.find(id)?.let { r.put(it.copy(lastCheck = System.currentTimeMillis())) } ?: r }
        result
    }

    private suspend fun checkOnce(e: InstalledExtension, origin: Origin, repo: GitHubRepo, manual: Boolean): Result {
        val id = e.id
        val (tag, movedTo) = latestTag(repo)
        val ref = tag ?: if (origin.tag) GitHubRepo.HEAD else origin.ref
        val manifestAnswer = get(repo.raw(concrete(repo, ref), "manifest.json"), maxText)
        if (manifestAnswer.status == 404 && tag == null) throw SourceGone()
        val listed = manifestAnswer.body?.let { runCatching { Manifest.parse(it.decodeToString()) }.getOrNull() }
        val head = tag == null
        if (e.sourceGone) ext.update { r -> r.find(id)?.let { r.put(it.copy(sourceGone = false)) } ?: r }
        if (listed != null && (listed.versionCode < e.versionCode || listed.versionCode == e.versionCode && !head)) return Result.UP_TO_DATE
        val newer = listed != null && listed.versionCode > e.versionCode
        if (newer && !manual && listed!!.versionCode == e.declinedVersionCode) return Result.DECLINED
        if (newer && e.pendingUpdate?.kept?.versionCode == listed!!.versionCode) return Result.NEEDS_OK
        val f = fromGitHub(repo, tag ?: ref, tag = tag != null).let { f -> movedTo?.let { Download(f.zip, f.pkg, f.origin?.copy(repo = it)) } ?: f }
        val m = f.pkg.manifest
        if (m.versionCode < e.versionCode || m.versionCode == e.versionCode && (!head || f.pkg.sha256 == e.sha256)) return Result.UP_TO_DATE
        if (!manual && m.versionCode == e.declinedVersionCode && (e.declinedSha == null || e.declinedSha == f.pkg.sha256)) return Result.DECLINED
        if (blocked(m.id, f.pkg.sha256) != null) return Result.BLOCKED
        if (!Catalog.Entry(m.id, m.name, m.version, m.versionCode, m.apiVersion, m.author, url = "", sha256 = "", minAppVersion = m.minAppVersion)
                .compatible(ext.engineVersion)) return Result.INCOMPATIBLE
        val running = ext.pkg(id)
        var accepted = e.accepted ?: ext.manifest(id)?.let { Snapshot.of(it, origin.repo) } ?: return Result.FAILED
        if (accepted.scriptSha == null || accepted.repoId == null) {
            val ids = if (accepted.repoId == null) (origin.repoId?.let { RepoIds(it, origin.ownerId.orEmpty()) }?.takeIf { it.owner.isNotEmpty() } ?: identity(repo)) else null
            accepted = accepted.copy(
                scriptSha = accepted.scriptSha ?: running?.script?.let(Snapshot::scriptHash),
                repoId = accepted.repoId ?: ids?.repo, ownerId = accepted.ownerId ?: ids?.owner,
            )
            ext.update { r -> r.find(id)?.let { r.put(it.copy(accepted = accepted)) } ?: r }
        }
        val next = Snapshot.of(m, f.origin?.repo, f.pkg.script, f.origin)
        val code = CodeDiff.of(running?.let { mapOf(it.manifest.main to it.script) }.orEmpty(), mapOf(m.main to f.pkg.script))
        val now = System.currentTimeMillis()
        val firstSeen = if (manual) null else if (e.seenSha == f.pkg.sha256 && e.seenAt > 0) e.seenAt else now
        val kept = KeptVersion(m.version, m.versionCode, f.pkg.sha256, f.origin)
        return when (val d = UpdateGate.decide(accepted, next, code, firstSeen, now)) {
            is UpdateGate.Decision.Block -> {
                ext.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = d.reason, pendingUpdate = null)) } ?: r }
                File(ext.dir, "$id/staging.zip").delete()
                ext.log(id, "update from ${origin.repo} refused: repository changed owner (${d.reason})")
                Result.REPO_CHANGED
            }
            is UpdateGate.Decision.Wait -> {
                ext.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = null, seenSha = f.pkg.sha256, seenAt = firstSeen ?: now)) } ?: r }
                ext.log(id, "${m.version} seen: applied by itself after 24 h")
                Result.WAITING
            }
            UpdateGate.Decision.Apply -> {
                val plan = updatePlan(m, e, accepted.toManifest()) ?: return Result.UP_TO_DATE
                ext.install(f.zip, f.pkg, Grants.replacing(m, plan.refused), e.source, origin = f.origin, accepted = false)
                val pinned = accepted.copy(scriptSha = next.scriptSha ?: accepted.scriptSha)
                ext.update { r -> r.find(id)?.let { r.put(it.copy(accepted = pinned, pendingUpdate = null, updateBlocked = null, seenSha = null, seenAt = 0)) } ?: r }
                ext.log(id, "updated by itself to ${m.version}: it asks nothing new (${code.lines} lines changed)")
                Result.APPLIED
            }
            is UpdateGate.Decision.Ask -> {
                writeAtomically(File(ext.dir, "$id/staging.zip"), f.zip)
                ext.update { r -> r.find(id)?.let { r.put(it.copy(updateBlocked = null, pendingUpdate = PendingUpdate(kept, d.changes, m.changelog))) } ?: r }
                ext.log(id, "${m.version} waits for your OK: ${d.changes.joinToString { "${it.kind.name.lowercase()} ${it.key}" }}")
                Result.NEEDS_OK
            }
        }
    }

    private fun updatePlan(m: Manifest, e: InstalledExtension, installed: Manifest?): InstallPlan.Update? =
        when (val plan = InstallPlan.of(m, e, installed)) {
            is InstallPlan.Update -> plan
            is InstallPlan.NotNewer -> if (m.versionCode == e.versionCode)
                (InstallPlan.of(m, e.copy(versionCode = e.versionCode - 1), installed) as? InstallPlan.Update)?.copy(from = e) else null
            is InstallPlan.Fresh -> null
        }

    /** The staged update of [id], read and checked against what was staged. */
    fun staged(id: String): ExtensionPackage? {
        val e = ext.registry.find(id) ?: return null
        val p = e.pendingUpdate ?: return null
        val bytes = runCatching { File(ext.dir, "$id/staging.zip").readBytes() }.getOrNull() ?: return null
        return runCatching { ExtensionPackage.read(bytes) }.getOrNull()?.takeIf { it.sha256 == p.kept.sha256 }
    }

    private val accepting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The user accepted the staged version: installed with all it lists granted, it becomes the accepted snapshot. */
    suspend fun accept(id: String) = withContext(Dispatchers.IO) {
        if (!accepting.add(id)) throw ExtensionException("already being applied")
        try {
            val e = ext.registry.find(id) ?: throw ExtensionException("not installed")
            val wanted = e.pendingUpdate?.kept?.version
            e.updateBlocked?.let {
                ext.log(id, "update to $wanted not accepted: the repository changed owner ($it)")
                throw ExtensionException("update refused: the repository changed owner")
            }
            val p = staged(id) ?: run {
                ext.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
                ext.log(id, "update to $wanted not accepted: the staged package is missing or changed")
                throw ExtensionException("the update is no longer on the phone: check again")
            }
            val accepted = e.accepted?.toManifest() ?: ext.manifest(id)
            val plan = updatePlan(p.manifest, e, accepted) ?: run {
                ext.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
                File(ext.dir, "$id/staging.zip").delete()
                ext.log(id, "update to ${p.manifest.version} (${p.manifest.versionCode}) not accepted: not newer than ${e.version} (${e.versionCode})")
                throw ExtensionException("this version is not newer than the one installed: check again")
            }
            val grant = Grants.replacing(p.manifest, plan.refused)
            val zip = File(ext.dir, "$id/staging.zip").readBytes()
            runCatching {
                ext.install(zip, p, grant, e.source, clashes = ext.clashes(p.manifest), origin = e.pendingUpdate?.kept?.origin, accepted = true)
            }.onFailure { ext.log(id, "update to ${p.manifest.version} accepted but failed: ${it.message ?: it}") }.getOrThrow()
            ext.update { r -> r.find(id)?.let { r.put(it.copy(pendingUpdate = null)) } ?: r }
            ext.log(id, "update to ${p.manifest.version} accepted and installed")
        } finally {
            accepting.remove(id)
        }
    }

    private val blocksFile get() = File(ext.dir, "cache/blocked.json")

    /** The last list read, at start, before the network answers. */
    fun readCachedBlocks() {
        runCatching { blocks = BlockList.parse(blocksFile.readText()) }
    }

    /** Reads `blocked.json` (once a day; [force]: now) and turns off what it blocks. */
    suspend fun refreshBlocks(force: Boolean = false) = withContext(Dispatchers.IO) {
        val f = blocksFile
        if (force || !f.isFile || System.currentTimeMillis() - f.lastModified() > dayMs) {
            val a = get(GitHub.BLOCKED_URL, maxText)
            if (a.status != 404 && a.status !in 200..299) throw ExtensionException("GitHub answered ${a.status}")
            val text = a.body?.decodeToString().orEmpty()
            blocks = BlockList.of(a.status, text)
            writeAtomically(f, if (a.status == 404) "{}" else text)
        }
        ext.registry.extensions.forEach { e ->
            blocked(e.id, e.sha256)?.let { b -> if (e.enabled) ext.disable(e.id, "blocked by Myspoti: ${b.reason.ifBlank { "no reason given" }}") }
        }
    }
}
