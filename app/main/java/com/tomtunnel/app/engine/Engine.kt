package com.tomtunnel.app.engine

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Un moteur ouvre un proxy SOCKS5 sur 127.0.0.1:SOCKS_PORT puis le service y branche l'interface TUN. */
interface Engine {
    fun start(ctx: Context, s: JSONObject)
    fun stop()
    companion object { const val SOCKS_PORT = 10808 }
}

internal fun Process.drainTo(file: File): Thread = Thread {
    try {
        file.parentFile?.mkdirs()
        inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> file.appendText(line + "\n") }
        }
    } catch (_: Throwable) {}
}.apply { isDaemon = true; start() }

internal fun waitForPort(host: String, port: Int, timeoutMs: Long = 6000): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 300) }
            return true
        } catch (_: Throwable) { Thread.sleep(100) }
    }
    return false
}

internal fun tail(file: File, max: Int = 1200): String = try {
    val s = if (file.exists()) file.readText() else ""
    s.takeLast(max)
} catch (_: Throwable) { "" }
