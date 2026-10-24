package com.tomtunnel.app

/** Pont simple Service -> Activité (état du VPN et statistiques). */
object VpnBus {
    @Volatile var last = "idle"
    @Volatile var onState: ((String, String) -> Unit)? = null
    @Volatile var onStats: ((Long, Long, Double, Double, Int) -> Unit)? = null
    fun state(s: String, msg: String = "") { last = s; onState?.invoke(s, msg) }
}
