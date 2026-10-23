package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** SSH over DNSTT/SlowDNS. DNSTT exposes a raw local TCP forward, not a SOCKS proxy. */
class SlowDnsEngine : Engine {
    private var dns: Process? = null
    private var ssh: Session? = null
    private var socks: ServerSocket? = null
    private var dnsResolver = "1.1.1.1"
    private var dnsResolverPort = 53

    override fun start(ctx: Context, s: JSONObject) {
        val abi = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "x86_64" else "arm64-v8a"
        val bin = EngineAssets.materialize(ctx, "dnstt", abi, "dnstt-client")
        val resolver = s.optString("resolver", "1.1.1.1").trim().ifEmpty { "1.1.1.1" }
        dnsResolver = resolver
        dnsResolverPort = s.optInt("dnsPort", 53).coerceIn(1, 65535)
        val dnsServer = if (resolver.contains(":")) resolver else "$resolver:$dnsResolverPort"
        val ns = s.optString("ns").trim()
        val pub = s.optString("pubkey").trim()
        require(ns.isNotEmpty()) { "Nameserver DNSTT manquant" }
        require(pub.isNotEmpty()) { "Clé publique DNSTT manquante" }

        val log = ctx.getFileStreamPath("dnstt.log").apply { writeText("") }
        dns = ProcessBuilder(bin.path, "-udp", dnsServer, "-pubkey", pub, ns, "127.0.0.1:$LOCAL")
            .redirectErrorStream(true).start().also { it.drainTo(log) }
        check(waitForPort("127.0.0.1", LOCAL, 12000)) {
            "DNSTT n'a pas ouvert le tunnel TCP $LOCAL. ${tail(log, 2200)}"
        }

        val proxy = object : Proxy {
            private var socket: Socket? = null
            private var input: InputStream? = null
            private var output: OutputStream? = null

            override fun connect(sf: com.jcraft.jsch.SocketFactory?, host: String, port: Int, timeout: Int) {
                socket = if (sf != null) sf.createSocket("127.0.0.1", LOCAL) else Socket("127.0.0.1", LOCAL)
                socket!!.soTimeout = timeout.coerceAtLeast(1000)
                input = socket!!.getInputStream()
                output = socket!!.getOutputStream()
            }
            override fun getInputStream(): InputStream = input ?: error("DNSTT proxy input indisponible")
            override fun getOutputStream(): OutputStream = output ?: error("DNSTT proxy output indisponible")
            override fun getSocket(): Socket = socket ?: error("DNSTT proxy socket indisponible")
            override fun close() { try { socket?.close() } catch (_: Throwable) {} }
        }

        ssh = JSch().getSession(s.optString("user"), s.optString("host"), s.optInt("sshPort", 22)).apply {
            setPassword(s.optString("pass"))
            setProxy(proxy)
            setConfig("StrictHostKeyChecking", "no")
            setServerAliveInterval(15000)
            setServerAliveCountMax(3)
            connect(30000)
        }

        socks = ServerSocket(Engine.SOCKS_PORT, 64, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "JephSlowDnsSocks") {
            while (socks?.isClosed == false) {
                val c = try { socks!!.accept() } catch (_: Throwable) { break }
                thread(isDaemon = true) { handle(c, ssh!!) }
            }
        }
        check(waitForPort("127.0.0.1", Engine.SOCKS_PORT, 3000)) { "SOCKS SlowDNS indisponible" }
    }

    override fun stop() {
        try { socks?.close() } catch (_: Throwable) {}
        try { ssh?.disconnect() } catch (_: Throwable) {}
        try { dns?.destroy() } catch (_: Throwable) {}
        socks = null; ssh = null; dns = null
    }

    private fun InputStream.full(n: Int) = ByteArray(n).also {
        var o = 0
        while (o < n) { val r = read(it, o, n - o); if (r < 0) error("EOF SOCKS"); o += r }
    }

    private fun handle(c: Socket, ss: Session) = try {
        val i = c.getInputStream(); val o = c.getOutputStream()
        val hello = i.full(2); i.full(hello[1].toInt() and 255)
        o.write(byteArrayOf(5, 0)); o.flush()
        val h = i.full(4)
        when (h[1].toInt() and 255) {
            1 -> {
                val host = readSocksHost(i, h[3].toInt() and 255)
                val p = i.full(2); val port = ((p[0].toInt() and 255) shl 8) or (p[1].toInt() and 255)
                val ch = ss.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
                ch.setHost(host); ch.setPort(port); ch.connect(20000)
                o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                val t = thread(isDaemon = true) { pipe(ch.inputStream, o) }
                pipe(i, ch.outputStream); t.join(); ch.disconnect()
            }
            3 -> {
                // SlowDNS carries DNS reliably over TCP through SSH. Other UDP is rejected.
                val udp = java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
                val localPort = udp.localPort
                o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (localPort ushr 8).toByte(), localPort.toByte())); o.flush()
                try {
                    while (!udp.isClosed && !c.isClosed) {
                        val b = ByteArray(65535); val p = java.net.DatagramPacket(b, b.size); udp.receive(p)
                        if (p.length < 10) continue
                        val atyp = p.data[p.offset + 3].toInt() and 255
                        val (host, port, headerLen) = readUdpDestination(p.data, p.offset, p.length, atyp)
                        if (port != 53) continue
                        val data = p.data.copyOfRange(p.offset + headerLen, p.offset + p.length)
                        val answer = dnsOverSsh(ss, data)
                        val out = ByteArray(10 + answer.size)
                        out[3] = 1; out[4] = 127; out[5] = 0; out[6] = 0; out[7] = 1
                        out[8] = (port ushr 8).toByte(); out[9] = port.toByte()
                        System.arraycopy(answer, 0, out, 10, answer.size)
                        udp.send(java.net.DatagramPacket(out, out.size, p.address, p.port))
                    }
                } finally { udp.close() }
            }
            else -> error("SOCKS commande non supportée")
        }
    } catch (_: Throwable) { } finally { try { c.close() } catch (_: Throwable) {} }

    private fun readSocksHost(i: InputStream, atyp: Int): String = when (atyp) {
        1 -> InetAddress.getByAddress(i.full(4)).hostAddress
        3 -> { val n = i.read(); require(n >= 0); String(i.full(n)) }
        4 -> InetAddress.getByAddress(i.full(16)).hostAddress
        else -> error("SOCKS atyp invalide")
    }

    private fun readUdpDestination(b: ByteArray, off: Int, len: Int, atyp: Int): Triple<String, Int, Int> {
        var p = off + 4
        val host = when (atyp) {
            1 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 4)).hostAddress; p += 4; h }
            3 -> { val n = b[p].toInt() and 255; p++; val h = String(b.copyOfRange(p, p + n)); p += n; h }
            4 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 16)).hostAddress; p += 16; h }
            else -> error("SOCKS UDP atyp invalide")
        }
        val port = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
        return Triple(host, port, p + 2 - off)
    }

    private fun dnsOverSsh(ss: Session, query: ByteArray): ByteArray {
        val ch = ss.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
        ch.setHost(dnsResolver); ch.setPort(dnsResolverPort); ch.connect(15000)
        return try {
            val out = ch.outputStream
            out.write((query.size ushr 8) and 255); out.write(query.size and 255); out.write(query); out.flush()
            val input = DataInputStream(ch.inputStream)
            val n = input.readUnsignedShort()
            ByteArray(n).also { input.readFully(it) }
        } finally { ch.disconnect() }
    }

    private fun pipe(a: InputStream, b: OutputStream) = try {
        val buf = ByteArray(16384)
        while (true) { val n = a.read(buf); if (n < 0) break; b.write(buf, 0, n); b.flush() }
    } catch (_: Throwable) {}

    companion object { const val LOCAL = 2222 }
}