package com.tomtunnel.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tomtunnel.app.engine.Engine
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Détection des IP du téléphone : IP locale (Wi-Fi / mobile) et IP publique (réelle ou via le VPN). */
object NetInfo {
    private val PROVIDERS = listOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip",
        "https://checkip.amazonaws.com"
    )

    /** Réseau physique (hors VPN) : type + IP locale. L'appli est exclue du VPN, donc on voit le vrai réseau. */
    @Suppress("DEPRECATION")
    fun local(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var type = "none"; var ip = ""
        try {
            val nets = cm.allNetworks.mapNotNull { n ->
                val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                Triple(n, c, cm.getLinkProperties(n))
            }
            // priorité au réseau par défaut, puis Wi-Fi, Ethernet, mobile
            val active = cm.activeNetwork
            val ordered = nets.sortedBy { (n, c, _) ->
                when {
                    n == active -> 0
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    else -> 3
                }
            }
            for ((_, c, lp) in ordered) {
                val addrs = lp?.linkAddresses?.map { it.address }?.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress } ?: continue
                val a = addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull() ?: continue
                ip = a.hostAddress?.substringBefore('%') ?: continue
                type = when {
                    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    else -> "other"
                }
                break
            }
        } catch (_: Throwable) {}
        return JSONObject().put("net", type).put("local", ip)
    }

    /** IP publique. Si le VPN est connecté, la requête passe par le proxy SOCKS local (donc par le tunnel). */
    fun publicIp(viaVpn: Boolean): String {
        val proxy = if (viaVpn) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", Engine.SOCKS_PORT)) else Proxy.NO_PROXY
        for (u in PROVIDERS) {
            try {
                val c = URL(u).openConnection(proxy) as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000
                c.useCaches = false
                c.setRequestProperty("User-Agent", "curl/8")
                val t = c.inputStream.bufferedReader().use { it.readText() }.trim()
                c.disconnect()
                if (t.isNotEmpty() && t.length <= 45 && t.all { it.isLetterOrDigit() || it == '.' || it == ':' }) return t
            } catch (_: Throwable) {}
        }
        return ""
    }
}