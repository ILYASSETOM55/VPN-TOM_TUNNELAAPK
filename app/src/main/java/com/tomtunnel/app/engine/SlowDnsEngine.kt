package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import com.jcraft.jsch.JSchException
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SSH over DNSTT/SlowDNS.
 *
 *  dnstt-client (UDP DNS vers le résolveur) --> TCP local 127.0.0.1:LOCAL --> JSch (SSH) --> SOCKS5 local 127.0.0.1:SOCKS_PORT
 *
 * DNSTT expose un simple forward TCP (pas un proxy SOCKS) : on fait donc la session SSH par-dessus,
 * puis on ouvre un petit serveur SOCKS5 qui crée des canaux "direct-tcpip" dans cette session.
 */
class SlowDnsEngine : SshSocksEngine() {
    private var dns: Process? = null

    override fun openTransport(): Socket =
        Socket().also { it.connect(InetSocketAddress("127.0.0.1", LOCAL), 15000) }

    override fun start(ctx: Context, s: JSONObject) {
        val abi = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "arm64-v8a"
        val bin = EngineAssets.materialize(ctx, "dnstt", abi, "dnstt-client")

        // ---- Paramètres saisis dans l'application (tous nettoyés) ----
        val resolverRaw = s.optString("resolver", "").trim().ifEmpty { "8.8.8.8" }
        val resolverPort = s.optString("dnsPort", "53").trim().toIntOrNull()?.coerceIn(1, 65535) ?: 53
        val dnsServer = when {
            resolverRaw.startsWith("[") -> resolverRaw                                  // [ipv6]:port
            resolverRaw.count { it == ':' } == 1 -> resolverRaw                         // ip:port
            resolverRaw.contains(":") -> "[$resolverRaw]:$resolverPort"                 // ipv6 nu
            else -> "$resolverRaw:$resolverPort"
        }
        val ns = s.optString("ns").trim().trimEnd('.')
        val pub = s.optString("pubkey").filter { !it.isWhitespace() }
        require(ns.isNotEmpty()) { "Nameserver DNSTT (NS) manquant" }
        require(pub.isNotEmpty()) { "Clé publique DNSTT manquante" }
        require(Regex("^[0-9a-fA-F]{64}$").matches(pub)) {
            "Clé publique invalide : elle doit contenir exactement 64 caractères hexadécimaux (0-9, a-f). " +
            "Vous avez ${pub.length} caractères."
        }
        val sshUser = s.optString("user").trim()
        val sshPass = s.optString("pass")
        val sshPort = s.optString("sshPort", "22").trim().toIntOrNull() ?: 22
        val sshHost = s.optString("host").trim().ifEmpty { "127.0.0.1" }
        require(sshUser.isNotEmpty()) { "Utilisateur SSH manquant" }

        // ---- 1) DNSTT ----
        val log = ctx.getFileStreamPath("dnstt.log").apply { writeText("") }
        val p = ProcessBuilder(bin.path, "-udp", dnsServer, "-pubkey", pub, ns, "127.0.0.1:$LOCAL")
            .directory(ctx.filesDir)
            .redirectErrorStream(true).start()
        dns = p
        p.drainTo(log)

        val ready = waitForPort("127.0.0.1", LOCAL, 15000) { p.alive() }
        check(ready) {
            if (!p.alive()) "DNSTT s'est arrêté : ${tail(log, 700).ifEmpty { "aucun message (binaire incompatible ?)" }}"
            else "DNSTT n'a pas ouvert le port local $LOCAL en 15 s. ${tail(log, 700)}"
        }

        // ---- 2) SSH par-dessus DNSTT (tunnel DNS lent : plusieurs essais, longs délais) ----
        connectSsh(sshUser, sshHost, sshPort, sshPass, SSH_ATTEMPTS, { p.alive() }) { explainSshError(it, log) }

        // ---- 3) SOCKS5 local ----
        startSocks()
    }

    private fun explainSshError(e: Throwable, log: File): String {
        val m = e.message.orEmpty()
        val tl = tail(log, 400)
        val base = when {
            m.contains("Auth fail", true) || m.contains("auth cancel", true) ->
                "Identifiant ou mot de passe SSH incorrect (le tunnel DNS fonctionne, c'est le compte SSH qui est refusé)."
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "Délai dépassé : le serveur SlowDNS ne répond pas. Vérifiez le NS, la clé publique et essayez un autre résolveur DNS."
            m.contains("Connection reset", true) || m.contains("EOF", true) || m.contains("socket is not established", true) || m.contains("Connection closed", true) ->
                "Le tunnel DNSTT a été coupé pendant la connexion SSH. Vérifiez NS / clé publique, le port SSH et le résolveur."
            e is JSchException -> "Erreur SSH : $m"
            else -> m.ifEmpty { e.javaClass.simpleName }
        }
        return if (tl.isNotEmpty()) "$base\nDNSTT : $tl" else base
    }

    override fun isAlive(): Boolean = super.isAlive() && (dns?.alive() == true)

    override fun stop() {
        super.stop()
        try { dns?.destroy() } catch (_: Throwable) {}
        dns = null
    }

    companion object {
        const val LOCAL = 2222
        private const val SSH_ATTEMPTS = 3
    }
}
