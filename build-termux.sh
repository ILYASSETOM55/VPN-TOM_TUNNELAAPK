#!/data/data/com.termux/files/usr/bin/bash
# Compile TOM_TUNNEL dans Termux et copie l'APK dans Téléchargements.
set -euo pipefail
cd "$(dirname "$0")"

# Version lue dans app/build.gradle.kts (une seule source de vérité)
VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n1)"
VERSION="${VERSION:-dev}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ ! -d "$ANDROID_HOME" ]; then
  echo "ERREUR: SDK Android introuvable: $ANDROID_HOME"
  echo "Vérifie que ~/android-sdk existe (ou exporte ANDROID_HOME)."
  exit 1
fi
command -v java >/dev/null 2>&1 || { echo "ERREUR: Java introuvable (pkg install openjdk-17)."; exit 1; }
command -v gradle >/dev/null 2>&1 || { echo "ERREUR: gradle n'est pas installé (pkg install gradle)."; exit 1; }

# Ce projet utilise AGP 8.5.2 + Kotlin 1.9.24 : incompatibles avec Gradle 9.x (celui de Termux).
# Si le Gradle du système est >= 9, on télécharge Gradle 8.7 une seule fois et on l'utilise.
GRADLE_BIN=gradle
GMAJ="$(gradle --version 2>/dev/null | sed -n 's/^Gradle \([0-9][0-9]*\)\..*/\1/p' | head -n1 || true)"
if [ "${GMAJ:-0}" -ge 9 ]; then
  GDIR="$HOME/.tomtunnel-gradle/gradle-8.7"
  if [ ! -x "$GDIR/bin/gradle" ]; then
    echo "Gradle $GMAJ détecté (incompatible) : téléchargement de Gradle 8.7..."
    mkdir -p "$HOME/.tomtunnel-gradle"
    curl -fL --retry 3 -o "$HOME/.tomtunnel-gradle/gradle-8.7-bin.zip" \
      https://services.gradle.org/distributions/gradle-8.7-bin.zip
    unzip -q -o "$HOME/.tomtunnel-gradle/gradle-8.7-bin.zip" -d "$HOME/.tomtunnel-gradle"
    rm -f "$HOME/.tomtunnel-gradle/gradle-8.7-bin.zip"
  fi
  GRADLE_BIN="$GDIR/bin/gradle"
fi
echo "Gradle utilisé : $GRADLE_BIN"

# Avertissement si la plateforme Android 34 manque dans le SDK
[ -d "$ANDROID_HOME/platforms/android-34" ] || echo "ATTENTION: $ANDROID_HOME/platforms/android-34 introuvable (installe-le avec sdkmanager \"platforms;android-34\" \"build-tools;34.0.0\")."

# Chemin du SDK pour Gradle (une vraie fin de ligne, pas un « \n » littéral)
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties

# Moteurs natifs : si l'AAR Hev ou les binaires manquent, on les télécharge d'abord
need_engines=0
ls app/libs/*.aar >/dev/null 2>&1 || need_engines=1
for f in app/src/main/jniLibs/arm64-v8a/libxray.so app/src/main/jniLibs/arm64-v8a/libdnstt.so; do
  [ -s "$f" ] || need_engines=1
done
if [ "$need_engines" = 1 ]; then
  echo "Moteurs manquants : exécution de scripts/bootstrap-engines.sh (curl, unzip, git, go requis)..."
  bash scripts/bootstrap-engines.sh
fi

termux-wake-lock 2>/dev/null || true

AAPT2_ARGS=()
if [ -x /data/data/com.termux/files/usr/bin/aapt2 ]; then
  AAPT2_ARGS=(-Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2)
fi
"$GRADLE_BIN" --no-daemon :app:assembleDebug "${AAPT2_ARGS[@]}"

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -s "$APK" ] || { echo "ERREUR: APK introuvable après la compilation."; exit 1; }

DEST=""
for d in "$HOME/storage/downloads" "$HOME/storage/shared/Download" "/sdcard/Download"; do
  if [ -d "$d" ] && [ -w "$d" ]; then DEST="$d"; break; fi
done
if [ -z "$DEST" ]; then
  echo "Stockage non autorisé : lance « termux-setup-storage » puis relance ce script."
  echo "APK disponible ici : $PWD/$APK"
  exit 0
fi
cp "$APK" "$DEST/TOM_TUNNEL-$VERSION.apk"
echo "APK copié dans Téléchargements: TOM_TUNNEL-$VERSION.apk"
