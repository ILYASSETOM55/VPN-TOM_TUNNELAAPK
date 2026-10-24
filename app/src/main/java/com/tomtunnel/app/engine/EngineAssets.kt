package com.tomtunnel.app.engine

import android.content.Context
import java.io.File

object EngineAssets {
    /** Les binaires sont embarqués comme libxray.so / libdnstt.so : Android les extrait dans nativeLibraryDir (exécutable). */
    fun materialize(ctx: Context, family: String, abi: String, name: String): File {
        val nativeName = when {
            family == "xray" && name == "xray" -> "libxray.so"
            family == "dnstt" && name == "dnstt-client" -> "libdnstt.so"
            else -> error("Moteur inconnu: $family/$name")
        }
        val nativeFile = File(ctx.applicationInfo.nativeLibraryDir, nativeName)
        check(nativeFile.exists() && nativeFile.length() > 0) {
            "Moteur $nativeName absent de l'APK (ABI $abi). Recompilez l'APK avec scripts/bootstrap-engines.sh " +
            "(ou le workflow GitHub corrigé) puis réinstallez."
        }
        nativeFile.setExecutable(true, false)
        return nativeFile
    }
}
