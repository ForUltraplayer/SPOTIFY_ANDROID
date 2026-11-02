// @file ApiTest.kt
// @brief The runner's HTTP API end to end on 127.0.0.1, with test extensions and a fake network (packages, indexes, the hosts' searches).
package app.myspoti.extensions.pc

import app.myspoti.extensions.Discovery
import app.myspoti.extensions.ExtensionPackage
import app.myspoti.extensions.GitHub
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ApiTest {
    @get:Rule val tmp = TemporaryFolder()

    private val token = "test-token-0123456789"
    /**
     * What the fake network serves (package addresses, indexes, the hosts' answers); anything else is
     * a 404, and nothing leaves the machine. A Refresh's throwaway `t=` query is ignored, as a CDN would.
     */
    private val served = ConcurrentHashMap<String, ByteArray>()
    private val fetcher = Fetcher { url, _, _, _ ->
        val at = url.replace(Regex("[?&]t=\\d+$"), "")
        served[at]?.let { Fetched(200, it, null, url) } ?: Fetched(404, null, null, url)
    }
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private lateinit var data: File
    private lateinit var runner: Runner
    private lateinit var api: Api
    private var port = 0

    @Before fun setUp() {
        data = tmp.newFolder("extensions")
        open()
    }

    @After fun tearDown() {
        api.close()
        runner.close()
    }

    private fun open() {
        runner = Runner(data, "2.3.0", fetcher, send = { _, _ -> throw IllegalStateException("no network in tests") }, autoUpdate = false)
        runner.init()
        api = Api(runner, token, "0.7.11")
        port = api.start(0)
    }

    private fun reopen() {
        tearDown()
        open()
    }

    private fun call(method: String, path: String, body: String? = null, key: String? = token): Pair<Int, JsonObject> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
        key?.let { b.header("X-Hangul-Token", it) }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return r.statusCode() to Json.parseToJsonElement(r.body()).jsonObject
    }

    private fun ok(method: String, path: String, body: String? = null): JsonObject =
        call(method, path, body).let { (status, json) -> assertEquals("$path: $json", 200, status); json }

    private fun pack(name: String): ByteArray = ExtensionPackage.pack(File(javaClass.getResource("/ext/$name")!!.toURI()))

    /** Previews and confirms the test extension [name] with every action it lists; its id. */
    private fun install(name: String): String {
        served["https://example.test/$name.zip"] = pack(name)
        val preview = ok("POST", "/v1/install/preview", """{"url":"https://example.test/$name.zip"}""")
        val granted = preview["asks"]!!.jsonArray.map { it.jsonObject["key"]!! }
        val done = ok("POST", "/v1/install/confirm", JsonObject(mapOf("pending" to preview["pending"]!!, "granted" to JsonArray(granted))).toString())
        return done["extension"]!!.jsonObject.str("id")
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun track(id: String, title: String = "Song $id") = """{"id":"$id","title":"$title","artist":"Someone, Other","album":"Album","durationMs":200000}"""
    private fun audio(id: String, extra: String = "") = ok("POST", "/v1/audio", """{"track":${track(id)}$extra}""")
    private fun asked(r: JsonObject) = r["attempt"]!!.jsonObject["asked"]!!.jsonArray.map { it.jsonObject }
    private fun order(vararg ids: String) = ok("POST", "/v1/order", """{"order":[${ids.joinToString { "\"$it\"" }}]}""")

    @Test fun aRequestWithoutTheExactTokenIsRefused() {
        assertEquals(401, call("GET", "/v1/health", key = null).first)
        assertEquals(401, call("GET", "/v1/health", key = token + "x").first)
        assertEquals("unauthorized", call("GET", "/v1/extensions", key = "wrong").second.str("error"))
        val health = ok("GET", "/v1/health")
        assertEquals(1, health["protocol"]!!.jsonPrimitive.content.toInt())
        assertEquals("2.3.0", health.str("version"))
        assertEquals(413, call("POST", "/v1/install/preview", """{"url":"${"x".repeat(Api.MAX_BODY)}"}""").first)
        assertEquals(404, call("GET", "/v1/nothing").first)
    }

    @Test fun aPackageIsPreviewedThenInstalledWithEveryActionItLists() {
        served["https://example.test/flac.zip"] = pack("flac")
        val preview = ok("POST", "/v1/install/preview", """{"url":"https://example.test/flac.zip"}""")
        assertEquals("install", preview.str("plan"))
        assertEquals("test.pc.flac", preview["manifest"]!!.jsonObject.str("id"))
        assertTrue(preview["critical"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("audio.source", "storage", "settings.page"), preview["asks"]!!.jsonArray.map { it.jsonObject.str("key") })
        assertTrue(File(data, "registry.json").let { !it.exists() || "test.pc.flac" !in it.readText() })

        // Installing grants every action it lists: one refused is no install.
        val partial = call("POST", "/v1/install/confirm", """{"pending":"${preview.str("pending")}","granted":["audio.source"]}""")
        assertEquals(400, partial.first)
        assertEquals("not-granted", partial.second.str("error"))
        val done = ok("POST", "/v1/install/confirm", """{"pending":"${preview.str("pending")}","granted":["audio.source","storage","settings.page"]}""")
        val e = done["extension"]!!.jsonObject
        assertTrue(e["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(e["running"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(File(data, "test.pc.flac/package.zip").isFile)
        // A pending is used once.
        assertEquals(404, call("POST", "/v1/install/confirm", """{"pending":"${preview.str("pending")}","granted":[]}""").first)

        // The shape the PC helper reads to decide whether to start the runner.
        val registry = Json.parseToJsonElement(File(data, "registry.json").readText()).jsonObject["extensions"]!!.jsonArray.single().jsonObject
        assertEquals("test.pc.flac", registry.str("id"))
        assertEquals("FLAC source", registry.str("name"))
        assertEquals(true, registry["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("audio-source"), registry["hooks"]!!.jsonArray.map { it.jsonPrimitive.content })

        val again = ok("POST", "/v1/install/preview", """{"url":"https://example.test/flac.zip"}""")
        assertEquals("not-newer", again.str("plan"))
        assertEquals(409, call("POST", "/v1/install/confirm", """{"pending":"${again.str("pending")}","granted":["audio.source","storage","settings.page"]}""").first)
        assertEquals(400, call("POST", "/v1/install/preview", """{"url":"https://example.test/missing.zip"}""").first)
        assertEquals(400, call("POST", "/v1/install/preview", """{"url":"http://example.test/flac.zip"}""").first)

        ok("POST", "/v1/extensions/test.pc.flac/remove")
        assertFalse(File(data, "test.pc.flac").exists())
        assertTrue(Json.parseToJsonElement(File(data, "registry.json").readText()).jsonObject["extensions"]!!.jsonArray.isEmpty())
    }

    @Test fun onOffSettingsAndOrderAreKeptAcrossARestart() {
        val flac = install("flac")
        val none = install("nohave")
        ok("POST", "/v1/extensions/$none/enabled", """{"enabled":false}""")
        assertFalse(Json.parseToJsonElement(File(data, "registry.json").readText()).jsonObject["extensions"]!!.jsonArray
            .first { it.jsonObject.str("id") == none }.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())

        ok("POST", "/v1/extensions/$flac/settings", """{"values":{"quality":"cd","loud":true,"token":"s3cret"}}""")
        assertEquals("bad-value", call("POST", "/v1/extensions/$flac/settings", """{"values":{"quality":"lossy"}}""").second.str("error"))
        assertEquals("unknown-setting", call("POST", "/v1/extensions/$flac/settings", """{"values":{"other":1}}""").second.str("error"))
        assertEquals("bad-value", call("POST", "/v1/extensions/$flac/settings", """{"values":{"pair":true}}""").second.str("error"))
        ok("POST", "/v1/extensions/$flac/settings", """{"press":"pair"}""")

        assertEquals(listOf(flac, none, "spotify"), ok("GET", "/v1/order")["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        order("spotify", none)
        assertEquals(listOf("spotify", none, flac), ok("GET", "/v1/order")["order"]!!.jsonArray.map { it.jsonPrimitive.content })

        reopen()
        val list = ok("GET", "/v1/extensions")["extensions"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("id") }
        assertFalse(list.getValue(none)["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(list.getValue(flac)["enabled"]!!.jsonPrimitive.content.toBoolean())
        val settings = list.getValue(flac)["settings"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
        assertEquals("cd", settings.getValue("quality").str("value"))
        assertEquals("true", settings.getValue("loud")["value"]!!.jsonPrimitive.content)
        // A secret's value is never given back, only that it is set.
        assertFalse("value" in settings.getValue("token"))
        assertTrue(settings.getValue("token")["set"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("spotify", none, flac), ok("GET", "/v1/order")["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        val perms = list.getValue(flac)["permissions"]!!.jsonArray.map { it.jsonObject }
        assertTrue(perms.first { it.str("key") == "audio.source" }.let { it["critical"]!!.jsonPrimitive.content.toBoolean() && it["granted"]!!.jsonPrimitive.content.toBoolean() })
    }

    @Test fun theSourcesAreAskedInOrderAndWhatThePcCannotPlayIsPassedOver() {
        val none = install("nohave")
        val aac = install("aac")
        val enc = install("transform")
        val flac = install("flac")
        order(none, aac, enc, flac, "spotify")

        val r = audio("track0000000000000000a")
        assertTrue(r["found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(flac, r.str("extension"))
        assertEquals("FLAC source", r.str("name"))
        val stream = r["stream"]!!.jsonObject
        assertEquals("flac", stream.str("codec"))
        assertEquals("24", stream["bitDepth"]!!.jsonPrimitive.content)
        assertEquals("FLAC · 24-bit · 96 kHz", stream.str("quality"))
        assertEquals("yes", stream["headers"]!!.jsonObject.str("X-Test"))
        assertTrue(stream.str("url").startsWith("https://media.example.com/track0000000000000000a.flac?n=1"))

        val a = asked(r).associateBy { it.str("extension") }
        assertEquals(listOf(none, aac, enc, flac), asked(r).map { it.str("extension") })
        assertEquals("not-found", a.getValue(none).str("outcome"))
        assertEquals("skipped", a.getValue(aac).str("outcome"))
        assertTrue(a.getValue(aac).str("detail"), a.getValue(aac).str("detail").contains("aac is not one of"))
        assertEquals("skipped", a.getValue(enc).str("outcome"))
        assertTrue(a.getValue(enc).str("detail"), a.getValue(enc).str("detail").contains("encrypted (transform)"))
        assertEquals("found", a.getValue(flac).str("outcome"))
        assertEquals(flac, r["attempt"]!!.jsonObject.str("chosen"))
        assertEquals("FLAC · 24-bit · 96 kHz", r["attempt"]!!.jsonObject.str("announced"))
        assertEquals("Quality: hi-res", r["attempt"]!!.jsonObject.str("sourceQuality"))

        // Asked again with AAC accepted: the AAC source above wins.
        val withAac = audio("track0000000000000000b", ""","accept":{"codecs":["flac","aac"],"manifest":false}""")
        assertEquals(aac, withAac.str("extension"))
        assertEquals(flac, asked(withAac).last().str("extension"))
    }

    @Test fun spotifysPlaceInTheOrderStopsTheAsking() {
        val none = install("nohave")
        val flac = install("flac")
        order(none, "spotify", flac)
        val r = audio("track0000000000000000c")
        assertFalse(r["found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("not-found", r.str("reason"))
        assertEquals(listOf(none), asked(r).map { it.str("extension") })
        assertEquals("Spotify: no extension had it", r["attempt"]!!.jsonObject.str("fallback"))
        // A song none had a moment ago is not asked again (until the sources change).
        val missed = audio("track0000000000000000c")
        assertEquals("missed-lately", missed.str("reason"))
        assertEquals("Spotify: no extension had it under a minute ago, not asked again", missed["attempt"]!!.jsonObject.str("fallback"))

        order("spotify", none, flac)
        val first = audio("track0000000000000000d")
        assertEquals("spotify-first", first.str("reason"))
        assertTrue(asked(first).isEmpty())
        assertEquals("Spotify: no source to ask", first["attempt"]!!.jsonObject.str("fallback"))
    }

    @Test fun aSourcePickedInThePlayerLeadsForTheSession() {
        val none = install("nohave")
        val flac = install("flac")
        order(none, "spotify", flac)
        assertEquals(JsonNull, ok("GET", "/v1/session")["source"])

        val picked = ok("POST", "/v1/audio/pick", """{"track":${track("track0000000000000000e")},"source":"$flac"}""")
        assertTrue(picked["found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(flac, picked.str("extension"))
        assertTrue(asked(picked).single().str("detail").startsWith("picked in the player for this session"))
        val session = ok("GET", "/v1/session")
        assertEquals(flac, session.str("source"))
        assertEquals(listOf(flac, none, "spotify"), session["choices"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(none, "spotify", flac), ok("GET", "/v1/order")["order"]!!.jsonArray.map { it.jsonPrimitive.content })

        // The next song asks the picked source first; the saved order is unchanged.
        val next = audio("track0000000000000000f")
        assertEquals(flac, next.str("extension"))
        assertEquals(flac, asked(next).first().str("extension"))

        // Spotify picked: nothing is asked for the next songs.
        val spotify = ok("POST", "/v1/audio/pick", """{"track":${track("track0000000000000000g")},"source":"spotify"}""")
        assertFalse(spotify["found"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("spotify", ok("GET", "/v1/session").str("source"))
        assertEquals("spotify-first", audio("track0000000000000000h").str("reason"))
        assertEquals(404, call("POST", "/v1/audio/pick", """{"track":${track("track0000000000000000h")},"source":"nobody"}""").first)

        // Back to the saved order: the next song asks [none] first again.
        assertEquals(JsonNull, ok("POST", "/v1/session", """{"source":null}""")["source"])
        assertEquals(none, asked(audio("track0000000000000000j")).first().str("extension"))
        assertEquals(400, call("POST", "/v1/session", """{"source":"$flac"}""").first)
    }

    @Test fun anAnswerAskedAheadIsPlayedAsGivenAndAskedAgainWhenItsAddressIsRefused() {
        val flac = install("flac")
        val ahead = audio("track0000000000000000i", ""","ahead":true""")
        assertTrue(ahead["attempt"]!!.jsonObject["ahead"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(ahead["stream"]!!.jsonObject.str("url").endsWith("n=1&ahead=true"))

        // When the song starts: the answer asked ahead, not a second ask.
        val now = audio("track0000000000000000i")
        assertEquals(ahead["attempt"]!!.jsonObject["id"]!!.jsonPrimitive.long, now["attempt"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        assertEquals(ahead["stream"]!!.jsonObject.str("url"), now["stream"]!!.jsonObject.str("url"))

        val first = now["attempt"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val again = ok("POST", "/v1/audio/again", """{"track":${track("track0000000000000000i")}}""")
        assertEquals(flac, again.str("extension"))
        assertTrue(again["stream"]!!.jsonObject.str("url").contains("n=2&ahead=false"))
        assertNotEquals(first, again["attempt"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        assertEquals("asked the extensions again: its address was refused (expired or gone)",
            runner.attempts.last().first { it.id == first }.fallback)
    }

    @Test fun whatThePlayerDidIsNotedOnTheLatestAttempt() {
        install("flac")
        val r = audio("track0000000000000000j")
        val noted = ok("POST", "/v1/attempts/note", """{"trackId":"track0000000000000000j","open":{"ok":true,"ms":120,"httpStatus":206,"url":"https://media.example.com/a.flac?token=secret"},"detected":"audio/flac · 96 kHz · 2 ch"}""")
        val latest = ok("GET", "/v1/attempts/latest?track=track0000000000000000j")["attempt"]!!.jsonObject
        assertEquals(noted["attempt"], latest)
        assertEquals(r["attempt"]!!.jsonObject.str("id"), latest.str("id"))
        val open = latest["open"]!!.jsonObject
        assertEquals("206", open["httpStatus"]!!.jsonPrimitive.content)
        // The address is kept without its query: it may carry a token.
        assertEquals("https://media.example.com/a.flac", open.str("url"))
        assertEquals("audio/flac · 96 kHz · 2 ch", latest.str("detected"))

        ok("POST", "/v1/attempts/note", """{"trackId":"track0000000000000000j","error":"MEDIA_ERR_DECODE","fallback":"Spotify"}""")
        val after = ok("GET", "/v1/attempts/latest?track=track0000000000000000j")["attempt"]!!.jsonObject
        assertEquals("MEDIA_ERR_DECODE", after.str("error"))
        assertEquals("206", after["open"]!!.jsonObject["httpStatus"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, ok("GET", "/v1/attempts/latest?track=nothingasked")["attempt"])
        assertEquals(400, call("GET", "/v1/attempts/latest").first)
        assertTrue((latest["asked"] as JsonArray).isNotEmpty())
        assertEquals(JsonPrimitive("track0000000000000000j"), latest["track"])
    }

    // ── The store ──

    private val official = ExtensionStore.OFFICIAL_REPO
    private val mine = "https://example.test/index.json"

    private fun entry(id: String, name: String, version: String, versionCode: Int, url: String, sha256: String, extra: String = "") =
        """{"id":"$id","name":"$name","version":"$version","versionCode":$versionCode,"author":"test","description":"Listed $name","url":"$url","sha256":"$sha256"$extra}"""

    private fun index(name: String, vararg entries: String) = """{"format":1,"name":"$name","extensions":[${entries.joinToString(",")}]}""".toByteArray()

    private fun found(repo: String, pushed: String) =
        """{"full_name":"$repo","owner":{"login":"${repo.substringBefore('/')}","id":7},"id":70,"html_url":"https://github.com/$repo","default_branch":"main","pushed_at":"$pushed","archived":false}"""

    private fun topicManifest(id: String) =
        """{"id":"$id","name":"Topic $id","version":"1.0.0","versionCode":1,"author":"someone","description":"Found by its topic","actions":[{"name":"audio.source","why":"Test"}]}"""

    /** The code hosts' searches: five repositories with the topic on GitHub (one without a manifest), none elsewhere; their ratings issues. */
    private fun serveCommunity() {
        val day = Instant.now().minusSeconds(86_400).toString()
        val days = Instant.now().minusSeconds(3 * 86_400).toString()
        served[Discovery.SEARCH_URL] = """{"items":[${listOf(found("alice/good", days), found("bob/low", day), found("carol/old", "2020-01-01T00:00:00Z"),
            found("dave/blocked", day), found("fay/flagged", days), found("erin/broken", day)).joinToString(",")}]}""".toByteArray()
        served[Discovery.GITLAB_SEARCH_URL] = "[]".toByteArray()
        served[Discovery.CODEBERG_SEARCH_URL] = """{"data":[]}""".toByteArray()
        listOf("alice/good", "bob/low", "carol/old", "dave/blocked", "fay/flagged").forEach { repo ->
            served["https://raw.githubusercontent.com/$repo/main/manifest.json"] = topicManifest("test.store." + repo.substringAfter('/')).toByteArray()
        }
        // Only an issue opened by the repository's owner counts: mallory's 100 👍 on bob/low do not.
        served[Discovery.RATINGS_URL] = """{"items":[
            {"number":3,"user":{"login":"alice","id":1},"repository_url":"https://api.github.com/repos/alice/good","reactions":{"+1":20,"-1":0}},
            {"number":4,"user":{"login":"mallory","id":2},"repository_url":"https://api.github.com/repos/bob/low","reactions":{"+1":100,"-1":0}},
            {"number":5,"user":{"login":"bob","id":3},"repository_url":"https://api.github.com/repos/bob/low","reactions":{"+1":1,"-1":15}}]}""".toByteArray()
        served[GitHub.BLOCKED_URL] = """{"blocked":[{"id":"test.store.blocked","reason":"malware"}],"flagged":[{"id":"test.store.flagged","reason":"reported"}]}""".toByteArray()
    }

    private fun community(store: JsonObject) = store["community"]!!.jsonObject["extensions"]!!.jsonArray.map { it.jsonObject }
    private fun repos(store: JsonObject) = store["repos"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.opt(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    @Test fun theStoreListsTheOfficialIndexTheUsersRepositoriesAndTheTopicSearchByMyspotisRules() {
        serveCommunity()
        val none = install("nohave")
        // The official repository is left out until it lists an extension.
        assertTrue(repos(ok("GET", "/v1/store")).isEmpty())
        served[official] = index("Myspoti", entry("test.pc.flac", "FLAC source", "1.0.0", 1, "packages/flac.zip", ExtensionPackage.sha256(pack("flac")),
            ""","icon_url":"icons/flac.png","updated_at":"2026-10-23","tags":["hi-res"]"""))
        val first = ok("GET", "/v1/store")
        val shown = repos(first).single()
        assertEquals(official, shown.str("url"))
        assertEquals("Myspoti", shown.str("name"))
        assertTrue(shown["official"]!!.jsonPrimitive.content.toBoolean())
        val flac = shown["extensions"]!!.jsonArray.single().jsonObject
        assertEquals("install", flac.str("action"))
        assertEquals("https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/icons/flac.png", flac.str("icon"))
        // The index entry has no actions listed: the risk is shown from what the index says, before anything is downloaded.
        assertTrue(flac["asks"]!!.jsonArray.isEmpty())
        assertEquals(listOf("hi-res"), flac["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(flac["rated"]!!.jsonPrimitive.content.toBoolean())

        // The community: by score, then the most recently updated; the stale and low-rated hidden behind "Show all"; erin/broken has no manifest.
        val c = community(first)
        assertEquals(listOf("good", "blocked", "flagged", "old", "low").map { "test.store.$it" }, c.map { it.str("id") })
        assertEquals(listOf(null, null, null, "stale", "low-score"), c.map { it.opt("hidden") })
        assertEquals(2, first["community"]!!.jsonObject.str("hidden").toInt())
        val good = c.first().jsonObject["rating"]!!.jsonObject
        assertEquals(listOf("20", "0", "20", "4.8"), listOf("up", "down", "votes", "stars").map { good.str(it) })
        assertEquals("15", c.last()["rating"]!!.jsonObject.str("down"))
        assertEquals("alice/good", c.first().str("repo"))
        assertEquals("alice", c.first().str("owner"))
        assertEquals(Discovery.SOURCE, c.first().str("index"))
        assertEquals("https://github.com/alice/good", c.first().str("homepage"))
        assertEquals("https://github.com/alice/good", c.first().str("repoPage"))
        assertFalse(c.first()["needsApi"]!!.jsonPrimitive.content.toBoolean())

        // The user's repository: kept in registry.json; an update for what is installed is told.
        assertEquals(400, call("POST", "/v1/store/repos", """{"add":"http://example.test/index.json"}""").first)
        served[mine] = index("Mine", entry(none, "No songs", "1.1.0", 2, "nohave.zip", "0".repeat(64)))
        assertEquals(listOf(mine), ok("POST", "/v1/store/repos", """{"add":"$mine"}""")["repositories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(mine in File(data, "registry.json").readText())
        val withMine = repos(ok("GET", "/v1/store"))
        assertEquals(listOf(official, mine), withMine.map { it.str("url") })
        assertFalse(withMine[1]["official"]!!.jsonPrimitive.content.toBoolean())
        val update = withMine[1]["extensions"]!!.jsonArray.single().jsonObject
        assertEquals("update", update.str("action"))
        assertEquals("1.0.0", update.str("installedVersion"))

        // Refresh re-reads the kill switch: the blocked and the flagged are hidden too.
        val fresh = ok("GET", "/v1/store?force=1")
        assertEquals(listOf(null, "blocked", "flagged", "stale", "low-score"), community(fresh).map { it.opt("hidden") })
        assertEquals(4, fresh["community"]!!.jsonObject.str("hidden").toInt())

        ok("POST", "/v1/store/repos", """{"remove":"$mine"}""")
        assertEquals(listOf(official), repos(ok("GET", "/v1/store")).map { it.str("url") })

        // The searches and manifests are kept 6 hours on disk: a new run lists the community without asking the hosts again.
        reopen()
        served.remove(Discovery.SEARCH_URL)
        assertEquals(5, community(ok("GET", "/v1/store")).size)
    }

    @Test fun aStoreEntryGoesThroughTheSamePromptAndItsSha256IsChecked() {
        served["https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/packages/flac.zip"] = pack("flac")
        served["https://raw.githubusercontent.com/kota-zo/myspoti-extensions/main/packages/aac.zip"] = pack("aac")
        served[official] = index("Myspoti",
            entry("test.pc.flac", "FLAC source", "1.0.0", 1, "packages/flac.zip", ExtensionPackage.sha256(pack("flac"))),
            entry("test.pc.aac", "AAC source", "1.0.0", 1, "packages/aac.zip", "ab".repeat(32)))
        // One repository with the topic, its package built by GitHub from the default branch (no sha256 to check).
        served[Discovery.SEARCH_URL] = """{"items":[${found("alice/good", Instant.now().toString())}]}""".toByteArray()
        served["https://raw.githubusercontent.com/alice/good/main/manifest.json"] = File(javaClass.getResource("/ext/nohave/manifest.json")!!.toURI()).readBytes()
        served["https://codeload.github.com/alice/good/zip/HEAD"] = pack("nohave")

        val offer = ok("POST", "/v1/store/install", """{"index":"$official","id":"test.pc.flac"}""")
        assertEquals("install", offer.str("plan"))
        assertEquals(official, offer.str("source"))
        assertEquals(listOf("audio.source", "storage", "settings.page"), offer["asks"]!!.jsonArray.map { it.jsonObject.str("key") })
        assertEquals(400, call("POST", "/v1/install/confirm", """{"pending":"${offer.str("pending")}","granted":["audio.source"]}""").first)
        ok("POST", "/v1/install/confirm", """{"pending":"${offer.str("pending")}","granted":["audio.source","storage","settings.page"]}""")
        assertEquals("installed", repos(ok("GET", "/v1/store")).single()["extensions"]!!.jsonArray.first().jsonObject.str("action"))

        val wrong = call("POST", "/v1/store/install", """{"index":"$official","id":"test.pc.aac"}""")
        assertEquals(400, wrong.first)
        assertEquals("refused", wrong.second.str("error"))
        assertTrue(wrong.second.str("detail"), wrong.second.str("detail").contains("sha256 differs"))
        assertTrue(runner.registry.find("test.pc.aac") == null)
        assertEquals("not-listed", call("POST", "/v1/store/install", """{"index":"$official","id":"test.pc.none"}""").second.str("error"))
        assertEquals(404, call("POST", "/v1/store/install", """{"index":"https://elsewhere.test/index.json","id":"test.pc.flac"}""").first)

        val topic = ok("POST", "/v1/store/install", """{"index":"${Discovery.SOURCE}","id":"test.pc.nohave"}""")
        assertEquals("install", topic.str("plan"))
        assertEquals("https://github.com/alice/good", topic.str("source"))
        assertEquals("alice/good", topic["origin"]!!.jsonObject.str("repo"))
        ok("POST", "/v1/install/confirm", """{"pending":"${topic.str("pending")}","granted":["audio.source"]}""")
        assertEquals("alice/good", runner.registry.find("test.pc.nohave")!!.origin!!.repo)

        served[Discovery.releaseUrl("alice/good")] = """{"assets":[{"name":"extension.zip","download_count":42},{"name":"other.zip","download_count":5}]}""".toByteArray()
        assertEquals("42", ok("GET", "/v1/store/downloads?repo=alice/good").str("downloads"))
        assertEquals(JsonNull, ok("GET", "/v1/store/downloads?repo=bob/none")["downloads"])
        assertEquals(400, call("GET", "/v1/store/downloads?repo=../x").first)
    }
}
