// @file Main.kt
// @brief Starts the runner: reads the arguments, opens the API on 127.0.0.1, prints `READY <port>` and keeps the log on stdout.
package app.myspoti.extensions.pc

import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

private const val USAGE = "usage: java -jar extensions-pc.jar --port 0 --token <token> --data <folder> --app-version <version>\n" +
    "(the folder may be given in HANGUL_EXT_DATA instead of --data)"

/**
 * The runner's log: one line per event on stdout, after the `READY` line (the helper reads that
 * line first, then drains the rest). What is said before it waits, and goes out right after it.
 */
object RunnerLog {
    private val waiting = ArrayDeque<String>()
    private var ready = false

    @Synchronized fun line(text: String) {
        if (ready) { println(text); return }
        waiting.addLast(text)
        while (waiting.size > 500) waiting.removeFirst()
    }

    /** Prints [first] (the `READY` line), then what waited. */
    @Synchronized fun ready(first: String) {
        println(first)
        waiting.forEach(::println)
        waiting.clear()
        ready = true
        System.out.flush()
    }
}

/** This build's Myspoti version (written at build time), or "unknown". */
internal fun engineVersion(): String = runCatching {
    Properties().apply { RunnerLog::class.java.getResourceAsStream("runner.properties")!!.use(::load) }.getProperty("version")
}.getOrNull() ?: "unknown"

fun main(args: Array<String>) {
    val opts = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val k = args[i]
        if (!k.startsWith("--") || i + 1 >= args.size) { System.err.println(USAGE); exitProcess(2) }
        opts[k.removePrefix("--")] = args[i + 1]
        i += 2
    }
    val port = opts["port"]?.toIntOrNull()?.takeIf { it in 0..65535 }
    val token = opts["token"]?.takeIf { it.length >= 16 }
    // The folder may also come in HANGUL_EXT_DATA: Java reads its command line in the Windows code page, so a
    // path with letters outside it (a Thai user name on Korean Windows) arrives as "?"; the environment does not.
    val data = (System.getenv("HANGUL_EXT_DATA")?.takeIf { it.isNotBlank() } ?: opts["data"])?.let(::File)
    if (port == null || token == null || data == null) { System.err.println(USAGE + "\n(the token is 16 characters or more)"); exitProcess(2) }
    data.mkdirs()
    if (!data.isDirectory) { System.err.println("not a folder: $data"); exitProcess(2) }

    val runner = Runner(data, engineVersion())
    runner.init()
    val api = Api(runner, token, opts["app-version"].orEmpty())
    val bound = try {
        api.start(port)
    } catch (e: java.io.IOException) {
        System.err.println("cannot listen on 127.0.0.1:$port: ${e.message}")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { api.close(); runner.close() })
    RunnerLog.ready("READY $bound")
    // The runner lives as long as the process that started it (the helper): it ends with it, even
    // when that one could not stop it (killed, crashed).
    ProcessHandle.current().parent().ifPresent { parent ->
        parent.onExit().thenRun { RunnerLog.line("runner: the process that started it ended, stopping"); exitProcess(0) }
    }
}
