package com.tomtunnel.app.engine

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Un moteur ouvre un proxy SOCKS5 sur 127.0.0.1:SOCKS_PORT puis le service y branche l'interface TUN. */
interface Engine {
    fun start(ctx: Context, s: JSONObject)
    fun stop()
    /** false si le tunnel est tombé (processus mort, session SSH fermée...). */
    fun isAlive(): Boolean = true
    companion object { const val SOCKS_PORT = 10808 }
}

internal fun Process.drainTo(file: File): Thread = Thread {
    try {
        file.parentFile?.mkdirs()
        file.outputStream().bufferedWriter().use { out ->
            inputStream.bufferedReader().forEachLine { line -> out.appendLine(line); out.flush() }
        }
    } catch (_: Throwable) {}
}.apply { isDaemon = true; start() }

internal fun Process.alive(): Boolean = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

/**
 * Attend qu'un port local accepte les connexions.
 * Si [alive] devient faux (le processus a planté), on arrête tout de suite au lieu d'attendre le timeout.
 */
internal fun waitForPort(host: String, port: Int, timeoutMs: Long = 6000, alive: () -> Boolean = { true }): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (!alive()) return false
        try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 300) }
            return true
        } catch (_: Throwable) { Thread.sleep(150) }
    }
    return false
}

internal fun tail(file: File, max: Int = 1200): String = try {
    val s = if (file.exists()) file.readText() else ""
    s.takeLast(max).trim()
} catch (_: Throwable) { "" }
