package com.tomtunnel.app.engine

import android.content.Context
import java.io.File

object EngineAssets {
    fun materialize(ctx: Context, family: String, abi: String, name: String): File {
        if ((family == "xray" && name == "xray") ||
            (family == "dnstt" && name == "dnstt-client")) {
            val nativeName = if (family == "xray") "libxray.so" else "libdnstt.so"
            val nativeFile = File(ctx.applicationInfo.nativeLibraryDir, nativeName)
            check(nativeFile.exists() && nativeFile.length() > 0) {
                "Binaire natif introuvable: ${nativeFile.absolutePath}"
            }
            return nativeFile
        }

        val out = File(ctx.filesDir, "engines/$family/$abi/$name")
        if (out.exists() && out.length() > 0) return out
        out.parentFile!!.mkdirs()
        val asset = "engines/$family/$abi/$name"
        ctx.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
        out.setExecutable(true, true)
        return out
    }
}
