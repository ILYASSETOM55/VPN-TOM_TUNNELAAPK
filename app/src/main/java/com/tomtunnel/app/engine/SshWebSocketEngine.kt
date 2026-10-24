package com.tomtunnel.app.engine

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * SSH over WebSocket (style HTTP Injector) :
 *   TCP/TLS vers host:port -> requête HTTP "Upgrade: websocket" (ou payload perso) -> réponse 101 -> flux SSH brut.
 * Après le "101", le serveur se comporte comme un tunnel TCP vers sshd (pas de trames WebSocket).
 */
class SshWebSocketEngine : SshSocksEngine() {
    private lateinit var host: String
    private var port = 443
    private var tls = true
    private var sni = ""
    private var wsHost = ""
    private var path = "/"
    private var payload = ""

    override fun start(ctx: Context, s: JSONObject) {
        host = s.optString("host").trim()
        port = s.optString("port", "443").trim().toIntOrNull() ?: 443
        tls = s.optBoolean("tls", true)
        sni = s.optString("sni").trim()
        wsHost = s.optString("wsHost").trim().ifEmpty { sni.ifEmpty { host } }
        path = s.optString("path", "/").trim().ifEmpty { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        payload = s.optString("payload")
        val user = s.optString("user").trim()
        require(host.isNotEmpty()) { "Hôte du serveur manquant" }
        require(user.isNotEmpty()) { "Utilisateur SSH manquant" }

        // Test rapide du transport pour donner une erreur précise avant de lancer SSH.
        try { openTransport().close() } catch (e: Throwable) {
            throw IllegalStateException(explain(e), e)
        }
        // "host" JSch n'est qu'une étiquette : la vraie connexion passe par openTransport().
        connectSsh(user, host, 22, s.optString("pass"), 2) { explain(it) }
        startSocks()
    }

    override fun openTransport(): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 15000)
        raw.soTimeout = 20000
        val sock: Socket = if (tls) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, sni.ifEmpty { host }, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                val name = sni.ifEmpty { host }
                if (!Regex("^[0-9.:]+$").matches(name)) serverNames = listOf(SNIHostName(name))
            }
            ssl.startHandshake()
            ssl
        } else raw

        sock.getOutputStream().apply { write(buildRequest().toByteArray(Charsets.ISO_8859_1)); flush() }
        val head = readHead(sock)
        val status = head.lineSequence().firstOrNull().orEmpty()
        check(Regex("^HTTP/\\S+\\s+(101|200)\\b").containsMatchIn(status)) {
            "Le serveur WebSocket a refusé l'upgrade : « ${status.take(80).ifEmpty { "aucune réponse" }} »"
        }
        return sock
    }

    private fun buildRequest(): String {
        val h = wsHost
        if (payload.isNotBlank()) {
            return payload
                .replace("[crlf]", "\r\n").replace("[lf]", "\n").replace("[cr]", "\r")
                .replace("[host_port]", "$host:$port").replace("[host]", h)
                .replace("[port]", port.toString()).replace("[protocol]", "HTTP/1.1")
                .replace("\\r", "\r").replace("\\n", "\n")
        }
        return "GET $path HTTP/1.1\r\nHost: $h\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n" +
            "User-Agent: Mozilla/5.0 (Linux; Android 13)\r\n\r\n"
    }

    /** Lit l'en-tête HTTP octet par octet (sans consommer un seul octet du flux SSH qui suit). */
    private fun readHead(sock: Socket): String {
        val inp = sock.getInputStream()
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 16384) {
            val b = inp.read()
            if (b < 0) break
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        return buf.toString("ISO-8859-1")
    }

    private fun explain(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Auth fail", true) -> "Identifiant ou mot de passe SSH incorrect (le tunnel WebSocket fonctionne)."
            m.contains("refusé l'upgrade") -> m
            e is javax.net.ssl.SSLException -> "Échec TLS : vérifiez le SNI, le port (443) ou désactivez TLS. ($m)"
            e is java.net.UnknownHostException -> "Hôte introuvable : $host"
            m.contains("timed out", true) || m.contains("timeout", true) -> "Délai dépassé vers $host:$port."
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
    }
}
