package com.v2ray.ang.service

/** Compatibility wrapper around the official hev-socks5-tunnel Android AAR. */
object TProxyService {
    @JvmStatic fun TProxyStartService(configPath: String, fd: Int): Boolean =
        hev.htproxy.TProxyService.TProxyStartService(configPath, fd)
    @JvmStatic fun TProxyStopService(): Boolean =
        hev.htproxy.TProxyService.TProxyStopService()
    @JvmStatic fun TProxyIsRunning(): Boolean =
        hev.htproxy.TProxyService.TProxyIsRunning()
    @JvmStatic fun TProxyGetStats(): LongArray =
        hev.htproxy.TProxyService.TProxyGetStats()
}
