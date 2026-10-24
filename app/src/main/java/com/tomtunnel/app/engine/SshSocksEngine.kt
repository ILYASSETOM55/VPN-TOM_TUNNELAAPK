package com.tomtunnel.app.engine

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Base commune SlowDNS / SSH WebSocket : une session SSH au-dessus d'un transport, exposée en SOCKS5 local. */
abstract class SshSocksEngine : Engine {
    @Volatile protected var ssh: Session? = null
    private var socks: ServerSocket? = null

    /** Ouvre la connexion brute (DNSTT local, ou WebSocket/TLS) sur laquelle JSch fera passer le SSH. */
    protected abstract fun openTransport(): Socket

    /** Plusieurs essais : [transportAlive] permet d'abandonner si le transport (ex. DNSTT) est mort. */
    protected fun connectSsh(user: String, host: String, port: Int, pass: String, attempts: Int,
                             transportAlive: () -> Boolean = { true }, explain: (Throwable) -> String) {
        var lastError: Throwable? = null
        for (attempt in 1..attempts) {
            if (!transportAlive()) break
            try {
                ssh = openSession(user, host, port, pass)
                lastError = null
                break
            } catch (e: Throwable) {
                lastError = e
                val m = e.message.orEmpty()
                if (m.contains("Auth fail", true) || m.contains("auth cancel", true)) break
                Thread.sleep(1500)
            }
        }
        lastError?.let { throw IllegalStateException(explain(it), it) }
    }

    private fun openSession(user: String, host: String, port: Int, pass: String): Session {
        val proxy = object : Proxy {
            private var socket: Socket? = null
            private var input: InputStream? = null
            private var output: OutputStream? = null
            override fun connect(sf: com.jcraft.jsch.SocketFactory?, host: String?, port: Int, timeout: Int) {
                val sock = openTransport()
                sock.tcpNoDelay = true
                sock.soTimeout = SSH_HANDSHAKE_MS
                socket = sock; input = sock.getInputStream(); output = sock.getOutputStream()
            }
            override fun getInputStream(): InputStream = input ?: error("proxy input indisponible")
            override fun getOutputStream(): OutputStream = output ?: error("proxy output indisponible")
            override fun getSocket(): Socket = socket ?: error("proxy socket indisponible")
            override fun close() { try { socket?.close() } catch (_: Throwable) {} }
        }
        val session = JSch().getSession(user, host, port)
        session.setPassword(pass)
        session.setProxy(proxy)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setServerAliveInterval(20000)
        session.setServerAliveCountMax(4)
        try { session.connect(SSH_HANDSHAKE_MS) } catch (e: Throwable) {
            try { session.disconnect() } catch (_: Throwable) {}
            throw e
        }
        try { session.setTimeout(0) } catch (_: Throwable) {}
        return session
    }

    protected fun startSocks() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), Engine.SOCKS_PORT), 128)
        }
        socks = server
        thread(isDaemon = true, name = "TomSshSocks") {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: Throwable) { break }
                val session = ssh ?: break
                thread(isDaemon = true) { handle(c, session) }
            }
        }
        check(waitForPort("127.0.0.1", Engine.SOCKS_PORT, 3000)) { "SOCKS SSH indisponible" }
    }

    override fun isAlive(): Boolean = ssh?.isConnected == true

    override fun stop() {
        try { socks?.close() } catch (_: Throwable) {}
        try { ssh?.disconnect() } catch (_: Throwable) {}
        socks = null; ssh = null
    }

    // ----------------------------------------------------------------- SOCKS5

    private fun InputStream.full(n: Int) = ByteArray(n).also {
        var o = 0
        while (o < n) { val r = read(it, o, n - o); if (r < 0) error("EOF SOCKS"); o += r }
    }

    private fun handle(c: Socket, ss: Session) {
        try {
            c.tcpNoDelay = true
            val i = c.getInputStream(); val o = c.getOutputStream()
            val hello = i.full(2); i.full(hello[1].toInt() and 255)
            o.write(byteArrayOf(5, 0)); o.flush()
            val h = i.full(4)
            when (h[1].toInt() and 255) {
                1 -> connectTcp(c, i, o, ss, h)
                3 -> udpAssociate(i, o, ss)
                else -> { o.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush() }
            }
        } catch (_: Throwable) {
        } finally { try { c.close() } catch (_: Throwable) {} }
    }

    private fun connectTcp(c: Socket, i: InputStream, o: OutputStream, ss: Session, h: ByteArray) {
        val host = readSocksHost(i, h[3].toInt() and 255)
        val p = i.full(2); val port = ((p[0].toInt() and 255) shl 8) or (p[1].toInt() and 255)
        val ch = ss.openChannel("direct-tcpip") as ChannelDirectTCPIP
        // IMPORTANT (JSch) : récupérer les flux AVANT connect(), sinon les premiers octets reçus sont perdus.
        val (chIn, chOut) = try {
            ch.setHost(host); ch.setPort(port)
            val streams = Pair<InputStream, OutputStream>(ch.inputStream, ch.outputStream)
            ch.connect(30000)
            streams
        } catch (e: Throwable) {
            // Vraie erreur SOCKS : l'appli cliente échoue vite au lieu d'attendre indéfiniment.
            try { o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush() } catch (_: Throwable) {}
            try { ch.disconnect() } catch (_: Throwable) {}
            return
        }
        try {
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
            val t = thread(isDaemon = true) { pipe(chIn, o); try { c.shutdownOutput() } catch (_: Throwable) {} }
            pipe(i, chOut)
            t.join(2000)
        } finally { try { ch.disconnect() } catch (_: Throwable) {} }
    }

    /** UDP : on ne relaie que le DNS (port 53), via une connexion TCP dans le tunnel SSH. Le reste est ignoré (les navigateurs retombent en TCP). */
    private fun udpAssociate(i: InputStream, o: OutputStream, ss: Session) {
        val udp = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val localPort = udp.localPort
        try {
            o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (localPort ushr 8).toByte(), localPort.toByte())); o.flush()
            // quand la connexion de contrôle se ferme, on ferme le relais UDP
            thread(isDaemon = true) { try { while (i.read() >= 0) { } } catch (_: Throwable) {}; udp.close() }
            val buf = ByteArray(65535)
            while (!udp.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try { udp.receive(pkt) } catch (_: Throwable) { break }
                if (pkt.length < 10) continue
                val data = buf.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                val client = pkt.socketAddress
                thread(isDaemon = true) {
                    try {
                        val atyp = data[3].toInt() and 255
                        val (host, port, headerLen) = readUdpDestination(data, atyp)
                        if (port != 53) return@thread
                        val answer = dnsOverSsh(ss, host, port, data.copyOfRange(headerLen, data.size))
                        // l'en-tête de la réponse reprend la destination d'origine
                        val out = ByteArray(headerLen + answer.size)
                        System.arraycopy(data, 0, out, 0, headerLen)
                        out[0] = 0; out[1] = 0; out[2] = 0
                        System.arraycopy(answer, 0, out, headerLen, answer.size)
                        if (!udp.isClosed) udp.send(DatagramPacket(out, out.size, client))
                    } catch (_: Throwable) {}
                }
            }
        } finally { udp.close() }
    }

    private fun readSocksHost(i: InputStream, atyp: Int): String = when (atyp) {
        1 -> InetAddress.getByAddress(i.full(4)).hostAddress!!
        3 -> { val n = i.read(); require(n >= 0); String(i.full(n)) }
        4 -> InetAddress.getByAddress(i.full(16)).hostAddress!!
        else -> error("SOCKS atyp invalide")
    }

    private fun readUdpDestination(b: ByteArray, atyp: Int): Triple<String, Int, Int> {
        var p = 4
        val host = when (atyp) {
            1 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 4)).hostAddress!!; p += 4; h }
            3 -> { val n = b[p].toInt() and 255; p++; val h = String(b.copyOfRange(p, p + n)); p += n; h }
            4 -> { val h = InetAddress.getByAddress(b.copyOfRange(p, p + 16)).hostAddress!!; p += 16; h }
            else -> error("SOCKS UDP atyp invalide")
        }
        val port = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
        return Triple(host, port, p + 2)
    }

    private fun dnsOverSsh(ss: Session, host: String, port: Int, query: ByteArray): ByteArray {
        val ch = ss.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(host); ch.setPort(port)
        val chIn = ch.inputStream; val out = ch.outputStream   // avant connect() (voir note JSch)
        ch.connect(20000)
        return try {
            out.write((query.size ushr 8) and 255); out.write(query.size and 255); out.write(query); out.flush()
            val input = DataInputStream(chIn)
            val n = input.readUnsignedShort()
            ByteArray(n).also { input.readFully(it) }
        } finally { try { ch.disconnect() } catch (_: Throwable) {} }
    }

    private fun pipe(a: InputStream, b: OutputStream) {
        try {
            val buf = ByteArray(16384)
            while (true) { val n = a.read(buf); if (n < 0) break; b.write(buf, 0, n); b.flush() }
        } catch (_: Throwable) {}
    }

    companion object { const val SSH_HANDSHAKE_MS = 60000 }
}
