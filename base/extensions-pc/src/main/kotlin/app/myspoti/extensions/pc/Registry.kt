// @file Registry.kt
// @brief What is installed and granted, the install plan, the extensions' stores and logs, written atomically.
package app.myspoti.extensions.pc

import app.myspoti.extensions.Action
import app.myspoti.extensions.Ask
import app.myspoti.extensions.Change
import app.myspoti.extensions.Clash
import app.myspoti.extensions.Hook
import app.myspoti.extensions.Level
import app.myspoti.extensions.Manifest
import app.myspoti.extensions.Origin
import app.myspoti.extensions.Snapshot
import app.myspoti.extensions.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Ported from the Android app's ExtensionRegistry.kt: the same fields and rules, so a registry means
// the same on both. The one addition is [InstalledExtension.hooks], read by the PC helper to decide
// whether the runner is needed without starting it.

internal val RegistryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One installed extension, as the registry keeps it. */
@Serializable
data class InstalledExtension(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    /** Of the package kept on disk: a package changed behind the runner's back does not run. */
    val sha256: String,
    /** The [Ask.key]s the user accepted: what the sandbox lets this extension do. */
    val granted: Set<String>,
    val enabled: Boolean = true,
    /** Why the runner turned it off (it crashed, it is blocked), shown on its page. */
    val disabledReason: String? = null,
    val installedAt: Long = 0,
    /** Where it came from: the https address of the package, or its repository's page. */
    val source: String = "file",
    /** Installed from a code host: the repository, tag or branch and commit. Updates come from there. */
    val origin: Origin? = null,
    /** What the user accepted (at install, or on an update's OK): every update is weighed against it. */
    val accepted: Snapshot? = null,
    /** The version before the running one, kept on disk (`previous.zip`). */
    val previous: KeptVersion? = null,
    /** A newer version waiting for the user's OK (`staging.zip`). */
    val pendingUpdate: PendingUpdate? = null,
    /** The version the user declined: not offered again, a newer one is. */
    val declinedVersionCode: Int = 0,
    val declinedSha: String? = null,
    /** The package of a harmless update first seen at [seenAt]: applied once a day has passed. */
    val seenSha: String? = null,
    val seenAt: Long = 0,
    /** Why updates from its repository are refused (the host's ids changed). */
    val updateBlocked: String? = null,
    /** When its source was last checked for an update. */
    val lastCheck: Long = 0,
    /** Its repository answered 404: it keeps running from the copy on disk. */
    val sourceGone: Boolean = false,
    val rawGrants: List<app.myspoti.extensions.RawGrant> = emptyList(),
    /**
     * The hooks its manifest's actions let it provide ([Hook.key]: `audio-source`, `download-source`,
     * `lyrics`, ...). Read by the PC helper from `registry.json` to start the runner only when needed.
     */
    val hooks: List<String> = emptyList(),
)

/** A version kept on disk to revert to, or waiting in staging. */
@Serializable
data class KeptVersion(val version: String, val versionCode: Int, val sha256: String, val origin: Origin? = null)

/** Spotify's place in the audio sources' order: the extensions after it are not asked. */
const val SPOTIFY_SOURCE = "spotify"

/** A newer version that asks something the user has not accepted; the old one keeps running. */
@Serializable
data class PendingUpdate(val kept: KeptVersion, val changes: List<Change>, val changelog: String = "")

/** The hooks [m]'s actions unlock, as [InstalledExtension.hooks] keeps them. */
fun hooksOf(m: Manifest): List<String> = m.actionSet().let { actions -> Hook.entries.filter { it.action != null && it.action in actions }.map { it.key } }

@Serializable
data class Registry(
    /** In installation order (the order hooks ask them in until the user sets a [priority]). */
    val extensions: List<InstalledExtension> = emptyList(),
    /** The repositories the user added to the store (index addresses), as on Android; the official one is not listed here. */
    val repositories: List<String> = emptyList(),
    /** Per area key or ordered action key, the ids in the order the user set. */
    val priority: Map<String, List<String>> = emptyMap(),
    /** Per exclusive action key, the extension the user made active. */
    val active: Map<String, String> = emptyMap(),
    val noticeAccepted: Set<String> = emptySet(),
) {
    fun find(id: String) = extensions.firstOrNull { it.id == id }

    fun <T> byPriority(area: String, items: List<T>, id: (T) -> String): List<T> {
        val order = priority[area].orEmpty()
        return items.sortedBy { order.indexOf(id(it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** [items] in the order the source [action] asks them: the user's order for it, else the active one first, then [area]'s order. */
    fun <T> bySourceOrder(action: String, area: String, items: List<T>, id: (T) -> String): List<T> {
        val first = active[action]
        val seeded = byPriority(area, items, id).sortedBy { if (id(it) == first) 0 else 1 }
        return byPriority(action, seeded, id)
    }

    fun move(area: String, providers: List<String>, id: String, delta: Int): Registry {
        val now = byPriority(area, providers) { it }.toMutableList()
        val from = now.indexOf(id)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, now.lastIndex)
        if (to == from) return this
        now.add(to, now.removeAt(from))
        return copy(priority = priority + (area to now))
    }

    fun migrated(): Registry {
        val byArea = LinkedHashMap<String, List<String>>()
        priority.forEach { (key, ids) ->
            val area = Hook.ofLegacy(key)?.action?.area?.key ?: key
            byArea[area] = (byArea[area].orEmpty() + ids).distinct()
        }
        return copy(extensions = extensions.map { it.copy(granted = Action.migrateGranted(it.granted)) }, priority = byArea)
    }

    fun put(e: InstalledExtension): Registry {
        val i = extensions.indexOfFirst { it.id == e.id }
        return copy(extensions = if (i < 0) extensions + e else extensions.toMutableList().also { it[i] = e })
    }

    fun remove(id: String) = copy(
        extensions = extensions.filterNot { it.id == id },
        priority = priority.mapValues { (_, ids) -> ids - id }.filterValues { it.isNotEmpty() },
        active = active.filterValues { it != id },
    )

    companion object {
        fun read(file: File): Registry = runCatching { RegistryJson.decodeFromString(serializer(), file.readText()).migrated() }.getOrDefault(Registry())

        fun write(file: File, r: Registry) = writeAtomically(file, RegistryJson.encodeToString(serializer(), r))
    }
}

/** How the user settled a [Clash] when installing or turning on an extension. */
object Clashes {
    fun apply(r: Registry, id: String, clashes: List<Clash>, keepNew: (Clash) -> Boolean): Pair<Registry, Boolean> {
        var out = r
        var newOff = false
        clashes.forEach { c ->
            val keep = keepNew(c)
            when (c.kind) {
                Clash.Kind.EXCLUSIVE -> out = out.copy(active = out.active + (c.action!!.key to if (keep) id else c.otherId))
                Clash.Kind.CONFLICT, Clash.Kind.REPLACES ->
                    if (keep) out.find(c.otherId)?.let { o -> out = out.put(o.copy(enabled = false, disabledReason = "replaced by $id")) }
                    else newOff = true
            }
        }
        return out to newOff
    }
}

/** What installing grants: every action the extension lists (to refuse one, the user does not install it). */
object Grants {
    fun keys(m: Manifest): Set<String> = m.asks().map { it.key }.toSet()

    fun refused(e: InstalledExtension, m: Manifest): Set<String> = keys(m) - e.granted

    fun replacing(m: Manifest, refused: Set<String>): Set<String> = keys(m) - refused
}

/** What happens when a package is offered for an extension that may already be installed. */
sealed interface InstallPlan {
    val manifest: Manifest

    data class Fresh(override val manifest: Manifest) : InstallPlan

    data class Update(
        override val manifest: Manifest,
        val from: InstalledExtension,
        val newAsks: List<Ask>,
        val refused: Set<String>,
    ) : InstallPlan {
        val reAsk: Boolean get() = newAsks.isNotEmpty() || refused.isNotEmpty()
    }

    data class NotNewer(override val manifest: Manifest, val installed: InstalledExtension) : InstallPlan

    companion object {
        fun of(manifest: Manifest, installed: InstalledExtension?, installedManifest: Manifest?): InstallPlan {
            if (installed == null) return Fresh(manifest)
            if (manifest.versionCode <= installed.versionCode) return NotNewer(manifest, installed)
            val old = installedManifest?.asks().orEmpty()
            val newAsks = manifest.asks().filter { a -> widens(a, old.firstOrNull { it.key == a.key }) }
            val refused = (old.map { it.key }.toSet() - installed.granted) intersect Grants.keys(manifest)
            return Update(manifest, installed, newAsks, refused)
        }

        internal fun widens(now: Ask, before: Ask?): Boolean = before == null ||
            (now.level == Level.CRITICAL && before.level != Level.CRITICAL) ||
            !before.hosts.containsAll(now.hosts) || now.why.trim() != before.why.trim()
    }
}

/** A small JSON map in a file (key → JSON text): an extension's `myspoti.storage`, and its settings. */
class FileStore(private val file: File) : Storage {
    private val values: MutableMap<String, String> by lazy {
        runCatching { RegistryJson.decodeFromString(MAP, file.readText()).toMutableMap() }.getOrElse { LinkedHashMap() }
    }

    @Synchronized override fun get(key: String): String? = values[key]
    @Synchronized override fun set(key: String, json: String) { values[key] = json; save() }
    @Synchronized override fun remove(key: String) { if (values.remove(key) != null) save() }
    @Synchronized override fun size(): Int = values.entries.sumOf { it.key.length + it.value.length }
    @Synchronized fun all(): Map<String, String> = values.toMap()

    private fun save() = writeAtomically(file, RegistryJson.encodeToString(MAP, values))

    companion object {
        private val MAP = MapSerializer(String.serializer(), String.serializer())
    }
}

/** An extension's log: the last lines in memory, and on disk (cut to [maxChars]). */
class ExtensionLog(private val file: File, private val maxLines: Int = 300, private val maxChars: Int = 64 * 1024) {
    private val lines: ArrayDeque<String> by lazy {
        ArrayDeque(runCatching { file.readLines() }.getOrDefault(emptyList()).takeLast(maxLines))
    }

    @Synchronized fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxChars) writeAtomically(file, lines.joinToString("\n", postfix = "\n"))
            else file.appendText(line + "\n")
        }
    }

    @Synchronized fun lines(): List<String> = lines.toList()
}

/** Counts each extension's failures in a row; at [limit] it is turned off. */
class FailureCounter(private val limit: Int = 3) {
    private val inARow = HashMap<String, Int>()

    @Synchronized fun failed(id: String): Boolean {
        val n = (inARow[id] ?: 0) + 1
        inARow[id] = n
        return n >= limit
    }

    @Synchronized fun succeeded(id: String) { inARow.remove(id) }

    @Synchronized fun reset(id: String) { inARow.remove(id) }
}

internal fun writeAtomically(file: File, text: String) = writeAtomically(file, text.toByteArray(Charsets.UTF_8))

/**
 * Replaces [file] whole: written to a temporary file next to it, then moved over it, so a reader
 * (the PC helper reads `registry.json`) never sees half a file. Windows refuses the move while a
 * reader has the file open: it is tried again a few times. A write that fails throws; the old file
 * is kept.
 */
internal fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
    try {
        tmp.writeBytes(bytes)
        var tries = 0
        while (true) {
            try {
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: AccessDeniedException) {
                if (++tries >= 10) throw e
                Thread.sleep(25)
            }
        }
    } finally {
        tmp.delete()
    }
}
