package com.tomtunnel.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tomtunnel.app.engine.*
import com.v2ray.ang.service.TProxyService
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class TomVpnService : VpnService() {
    companion object { const val STOP = "com.tomtunnel.app.STOP"; const val RECONNECT = "com.tomtunnel.app.RECONNECT" }
    private var lastServer: JSONObject? = null
    @Volatile private var gen = 0
    private var tun: ParcelFileDescriptor? = null
    private var engine: Engine? = null
    @Volatile private var running = false

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == STOP) { shutdown("idle", ""); return START_NOT_STICKY }
        if (i?.action == RECONNECT) {
            val s = lastServer ?: return START_NOT_STICKY
            goForeground(s, false)
            Thread { reconnect(s) }.start()
            return START_NOT_STICKY
        }
        val raw = i?.getStringExtra("server") ?: return START_NOT_STICKY
        val srv = JSONObject(raw)
        lastServer = srv
        goForeground(srv, false)
        Thread { launch(srv) }.start()
        return START_NOT_STICKY
    }

    private fun launch(s: JSONObject) {
        try {
            engine = if (s.optString("type") == "SSH SlowDNS") SlowDnsEngine() else XrayEngine()
            engine!!.start(this, s)

            tun = Builder().setSession("TOM_TUNNEL").setMtu(1400)
                .addAddress("10.10.14.1", 30)
                .addAddress("fd00:10:10:14::1", 128)
                .addRoute("0.0.0.0", 0).addRoute("::", 0)
                .addDnsServer(s.optString("dns", "1.1.1.1").ifEmpty { "1.1.1.1" })
                .addDisallowedApplication(packageName)
                .establish() ?: error("Interface VPN refusée")

            val cfg = File(filesDir, "tproxy.yml")
            cfg.writeText("""
                tunnel:
                  mtu: 1400
                  ipv4: 10.10.14.1
                  ipv6: 'fd00:10:10:14::1'
                  icmp: 'off'
                socks5:
                  port: ${Engine.SOCKS_PORT}
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  log-file: ${File(filesDir, "hev-runtime.log").absolutePath}
                  log-level: info
                  connect-timeout: 15000
                  tcp-read-write-timeout: 120000
                """.trimIndent())
            check(TProxyService.TProxyStartService(cfg.absolutePath, tun!!.fd)) {
                "tun2socks n'a pas démarré: ${tail(File(filesDir, "hev-runtime.log"), 1600)}"
            }
            Thread.sleep(300)
            check(TProxyService.TProxyIsRunning()) {
                "tun2socks s'est arrêté: ${tail(File(filesDir, "hev-runtime.log"), 1600)}"
            }
            check(probeSocks("1.1.1.1", 443)) {
                "Le proxy ${Engine.SOCKS_PORT} ne peut pas joindre Internet via le serveur"
            }
            running = true
            VpnBus.state("connected")
            notifyState(s, true)
            pollStats(s)
        } catch (e: Throwable) { shutdown("error", e.message ?: e.javaClass.simpleName) }
    }

    private fun pollStats(s: JSONObject) {
        var lastTx = 0L; var lastRx = 0L; var ping = -1; var n = 0
        val g = ++gen
        while (running && g == gen) {
            if (n++ % 10 == 0) ping = tcpPing(s)
            val st = try { TProxyService.TProxyGetStats() } catch (_: Throwable) { null }
            if (st != null && st.size >= 4) {
                val ul = st[1]; val dl = st[3]
                VpnBus.onStats?.invoke(dl, ul, (dl - lastRx) * 8 / 1000.0, (ul - lastTx) * 8 / 1000.0, ping)
                lastRx = dl; lastTx = ul
            }
            Thread.sleep(1000)
        }
    }

    private fun probeSocks(host: String, port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", Engine.SOCKS_PORT), 3000)
            socket.soTimeout = 5000
            val out = socket.getOutputStream(); val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val hello = ByteArray(2); readFully(input, hello)
            check(hello[0].toInt() == 5 && hello[1].toInt() == 0)
            val hb = host.toByteArray(Charsets.UTF_8)
            require(hb.size <= 255)
            out.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte())); out.write(hb)
            out.write(byteArrayOf((port ushr 8).toByte(), port.toByte())); out.flush()
            val h = ByteArray(4); readFully(input, h)
            if (h[1].toInt() != 0) return false
            val len = when (h[3].toInt() and 255) {
                1 -> 4
                3 -> input.read().also { check(it >= 0) }
                4 -> 16
                else -> return false
            }
            val rest = if ((h[3].toInt() and 255) == 3) len else len
            if ((h[3].toInt() and 255) == 3) readFully(input, ByteArray(rest)) else readFully(input, ByteArray(rest))
            readFully(input, ByteArray(2))
            true
        }
    } catch (_: Throwable) { false }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) error("EOF SOCKS")
            off += n
        }
    }

    private fun tcpPing(s: JSONObject): Int = try {
        val host = s.optString("addr").ifEmpty { s.optString("host") }
        val port = (s.optString("port").ifEmpty { s.optString("sshPort") }).toIntOrNull() ?: 443
        val t = System.currentTimeMillis()
        Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
        (System.currentTimeMillis() - t).toInt()
    } catch (_: Throwable) { -1 }

    private fun shutdown(state: String, msg: String) {
        running = false
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { engine?.stop() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        engine = null; tun = null
        VpnBus.state(state, msg)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onRevoke() { shutdown("idle", "") }
    override fun onDestroy() { running = false; super.onDestroy() }

    private fun reconnect(s: JSONObject) {
        running = false
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { engine?.stop() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}
        engine = null; tun = null
        Thread.sleep(500)
        VpnBus.state("connecting")
        launch(s)
    }

    private fun buildNotif(s: JSONObject?, connected: Boolean): Notification {
        val name = s?.optString("name").orEmpty().ifEmpty { "Serveur VPN" }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun act(a: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, TomVpnService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, "vpn")
            .setContentTitle(if (connected) "TOM_TUNNEL · Connecté" else "TOM_TUNNEL · Connexion…")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(open)
        if (connected) {
            b.addAction(0, "Reconnecter", act(RECONNECT, 1))
            b.addAction(0, "Déconnecter", act(STOP, 2))
        } else {
            b.addAction(0, "Stop", act(STOP, 2))
        }
        return b.build()
    }

    private fun notifyState(s: JSONObject?, connected: Boolean) {
        try { getSystemService(NotificationManager::class.java).notify(1, buildNotif(s, connected)) } catch (_: Throwable) {}
    }

    private fun goForeground(s: JSONObject?, connected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("vpn", "TOM_TUNNEL VPN", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotif(s, connected)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }
}

