package com.tomtunnel.app.engine

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Xray/V2Ray core: VLESS, VMess, Trojan and Shadowsocks. */
class XrayEngine : Engine {
    private var proc: Process? = null

    override fun start(ctx: Context, s: JSONObject) {
        val bin = EngineAssets.materialize(ctx, "xray", abi(), "xray")
        bin.setExecutable(true, true)
        val cfg = File(ctx.filesDir, "xray.json").apply { writeText(buildConfig(s).toString()) }

        val test = ProcessBuilder(bin.path, "run", "-test", "-c", cfg.path)
            .redirectErrorStream(true).directory(ctx.filesDir).start()
        val testOut = test.inputStream.bufferedReader().readText()
        check(test.waitFor() == 0) {
            "Configuration Xray invalide: ${testOut.takeLast(1200)}"
        }

        proc = ProcessBuilder(bin.path, "run", "-c", cfg.path)
            .redirectErrorStream(true).directory(ctx.filesDir).apply {
                environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.path
            }.start().also { p ->
                Thread {
                    try {
                        File(ctx.filesDir, "xray-runtime.log").outputStream().bufferedWriter().use { out ->
                            p.inputStream.bufferedReader().forEachLine {
                                out.appendLine(it)
                                out.flush()
                            }
                        }
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true; start() }
            }

        check(waitForPort("127.0.0.1", Engine.SOCKS_PORT, 5000)) {
            val log = File(ctx.filesDir, "xray-runtime.log")
            "Xray n'a pas ouvert le SOCKS 127.0.0.1:${Engine.SOCKS_PORT}. ${tail(log, 1600)}"
        }
    }

    private fun abi(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "x86_64" -> "x86_64"
        else -> "arm64-v8a"
    }

    override fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }

    companion object {
        fun buildConfig(s: JSONObject): JSONObject {
            val type = s.optString("type").uppercase()
            val addr = s.optString("addr").trim()
            val port = s.optString("port").toIntOrNull() ?: error("Port Xray invalide")
            require(addr.isNotEmpty()) { "Adresse Xray vide" }

            val protocol = when (type) {
                "VLESS" -> "vless"
                "VMESS" -> "vmess"
                "TROJAN" -> "trojan"
                "SHADOWSOCKS" -> "shadowsocks"
                else -> error("Protocole Xray non pris en charge: $type")
            }

            val out = JSONObject().put("tag", "proxy").put("protocol", protocol)
            val settings = when (type) {
                "VLESS" -> JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", addr).put("port", port)
                    .put("users", JSONArray().put(JSONObject()
                        .put("id", s.optString("uuid"))
                        .put("encryption", "none")
                        .apply { if (s.optString("flow").isNotEmpty()) put("flow", s.optString("flow")) }))))
                "VMESS" -> JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", addr).put("port", port)
                    .put("users", JSONArray().put(JSONObject()
                        .put("id", s.optString("uuid"))
                        .put("alterId", s.optInt("aid", 0))
                        .put("security", "auto")))))
                "TROJAN" -> JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", addr).put("port", port).put("password", s.optString("pass"))))
                else -> JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", addr).put("port", port)
                    .put("method", s.optString("method", "aes-256-gcm"))
                    .put("password", s.optString("pass"))))
            }
            out.put("settings", settings)
            if (type != "SHADOWSOCKS") out.put("streamSettings", stream(s, type == "TROJAN"))

            return JSONObject()
                .put("log", JSONObject()
                    .put("loglevel", "info")
                    .put("access", "xray-access.log")
                    .put("error", "xray-error.log"))
                .put("inbounds", JSONArray().put(JSONObject()
                    .put("tag", "socks")
                    .put("listen", "127.0.0.1")
                    .put("port", Engine.SOCKS_PORT)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
                    .put("sniffing", JSONObject()
                        .put("enabled", true)
                        .put("destOverride", JSONArray().put("http").put("tls")))))
                .put("outbounds", JSONArray()
                    .put(out)
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        }

        private fun stream(s: JSONObject, forceTls: Boolean): JSONObject {
            val net = s.optString("network", "tcp").ifEmpty { "tcp" }.lowercase()
            val requested = s.optString("security").trim().lowercase()
            val sec = when {
                requested == "reality" -> "reality"
                forceTls -> "tls"
                requested.isNotEmpty() -> requested
                s.optBoolean("tls", false) -> "tls"
                else -> "none"
            }
            val st = JSONObject().put("network", net).put("security", sec)

            if (sec == "tls") {
                st.put("tlsSettings", JSONObject()
                    .put("serverName", s.optString("sni").ifEmpty { s.optString("addr") })
                    .put("fingerprint", s.optString("fp").ifEmpty { "chrome" })
                    .apply {
                        if (s.optString("alpn").isNotEmpty()) {
                            put("alpn", JSONArray(s.optString("alpn").split(",").map { it.trim() }))
                        }
                    })
            }
            if (sec == "reality") {
                st.put("realitySettings", JSONObject()
                    .put("serverName", s.optString("sni").ifEmpty { s.optString("addr") })
                    .put("fingerprint", s.optString("fp").ifEmpty { "chrome" })
                    .put("publicKey", s.optString("pbk"))
                    .put("shortId", s.optString("sid"))
                    .put("spiderX", s.optString("spx", "/").ifEmpty { "/" }))
            }

            when (net) {
                "ws" -> st.put("wsSettings", JSONObject()
                    .put("path", s.optString("path", "/").ifEmpty { "/" })
                    .put("headers", JSONObject().put("Host", s.optString("host").ifEmpty { s.optString("sni") })))
                "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", s.optString("path")))
                "h2" -> st.put("httpSettings", JSONObject()
                    .put("path", s.optString("path", "/"))
                    .put("host", JSONArray().put(s.optString("host").ifEmpty { s.optString("sni") })))
            }
            return st
        }
    }
}
