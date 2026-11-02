// @file FakeAuthWindow.kt
// @brief A stand-in for the PC's sign-in window (webview-auth.ps1) in tests: reads the request on stdin, answers as the last part of its address says.
package app.myspoti.extensions.pc

import java.io.File

/**
 * Started by the runner as its sign-in window: `FakeAuthWindow <folder>`. It keeps the request line as `<folder>/<mode>.json`
 * and its process id in `<folder>/<mode>.pid`, then does what the address's last part (`https://login.example.test/<mode>`) says:
 * reach (and visible): the callback with a secret query; error: `webview2-missing`; wrong: an address that is not the callback;
 * crash: ends without a word; hang: waits until stdin closes.
 */
object FakeAuthWindow {
    @JvmStatic fun main(args: Array<String>) {
        val folder = File(args[0])
        val input = System.`in`.bufferedReader(Charsets.UTF_8)
        val line = input.readLine() ?: return
        fun field(name: String) = Regex("\"$name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.replace("\\/", "/")
        val mode = field("url")!!.substringAfterLast('/')
        File(folder, "$mode.json").writeText(line, Charsets.UTF_8)
        File(folder, "$mode.pid").writeText(ProcessHandle.current().pid().toString())
        val answer = when (mode) {
            "reach", "visible" -> """{"url":"${field("callback")}?code=s3cret-code&state=x"}"""
            "error" -> """{"error":"webview2-missing"}"""
            "wrong" -> """{"url":"https://elsewhere.example.test/done?code=s3cret-code"}"""
            "crash" -> return
            else -> { while (input.readLine() != null) Unit; return }
        }
        System.out.write((answer + "\n").toByteArray(Charsets.UTF_8))
        System.out.flush()
    }
}
