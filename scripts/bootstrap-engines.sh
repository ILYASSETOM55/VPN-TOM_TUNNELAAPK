#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"
TMP="${TMPDIR:-/tmp}/tomtunnel-engines"
mkdir -p "$LIBS" "$JNI/arm64-v8a" "$JNI/x86_64" "$TMP"
HEV=2.18.0
XRAY=26.9.8
DNSTT_REPO=https://github.com/dnstt-xyz/dnstt_xyz_app.git

need(){ command -v "$1" >/dev/null 2>&1 || { echo "Missing: $1" >&2; exit 1; }; }
need curl; need unzip; need git; need go

# Official Hev Android AAR (déjà présent = on ne retélécharge pas).
if [ ! -s "$LIBS/hev-socks5-tunnel.aar" ]; then
  curl -fL --retry 3 "https://github.com/heiher/hev-socks5-tunnel/releases/download/$HEV/hev-socks5-tunnel.aar" \
    -o "$LIBS/hev-socks5-tunnel.aar"
fi
test -s "$LIBS/hev-socks5-tunnel.aar"

fetch_xray(){
  local abi="$1" file="$2"
  local zip="$TMP/$file.zip" out="$JNI/$abi/libxray.so"
  if [ -s "$out" ]; then echo "Xray $abi déjà présent"; return; fi
  curl -fL --retry 3 "https://github.com/XTLS/Xray-core/releases/download/v$XRAY/$file.zip" -o "$zip"
  unzip -p "$zip" xray > "$out"
  chmod 755 "$out"
  test -s "$out"
}
fetch_xray arm64-v8a Xray-android-arm64-v8a
fetch_xray x86_64 Xray-android-amd64

# Official DNSTT client, compiled as an Android executable and packaged as a native .so
# only so Android extracts it into nativeLibraryDir. EngineAssets executes that file directly.
SRC="$TMP/dnstt_xyz_app"
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 "$DNSTT_REPO" "$SRC"
else
  git -C "$SRC" fetch --depth 1 origin >/dev/null 2>&1 || true
  git -C "$SRC" reset --hard FETCH_HEAD >/dev/null 2>&1 || true
fi

CLIENT_DIR="$SRC/go_src/dnstt-client"
[ -d "$CLIENT_DIR" ] || { echo "ERREUR: dossier go_src/dnstt-client introuvable dans $SRC" >&2; exit 1; }

# Le go.mod n'est pas forcément à la racine du dépôt : on le cherche en remontant
# depuis le client, puis ailleurs dans le dépôt si besoin.
MOD_DIR="$CLIENT_DIR"
while [ "$MOD_DIR" != "$SRC" ] && [ "$MOD_DIR" != "/" ] && [ ! -f "$MOD_DIR/go.mod" ]; do
  MOD_DIR="$(dirname "$MOD_DIR")"
done
if [ ! -f "$MOD_DIR/go.mod" ]; then
  FOUND="$(find "$SRC" -name go.mod -not -path '*/node_modules/*' | head -n1)"
  [ -n "$FOUND" ] || { echo "ERREUR: aucun go.mod trouvé dans $SRC" >&2; exit 1; }
  MOD_DIR="$(dirname "$FOUND")"
fi
REL="${CLIENT_DIR#"$MOD_DIR"}"; REL="${REL#/}"
PKG="./${REL}"; [ -n "$REL" ] || PKG="."
echo "Module Go : $MOD_DIR  (paquet $PKG)"

build_dnstt(){
  local goarch="$1" abi="$2"
  local out="$JNI/$abi/libdnstt.so"
  if [ -s "$out" ]; then echo "DNSTT $abi déjà présent"; return; fi
  # GOOS=android sans cgo échoue sur certaines architectures (ex. amd64) :
  # on retombe alors sur GOOS=linux, binaire statique qui s'exécute aussi sur Android.
  local goos
  for goos in android linux; do
    if (cd "$MOD_DIR" && GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 \
          go build -trimpath -ldflags='-s -w' -o "$out.tmp" "$PKG"); then
      echo "DNSTT $abi compilé (GOOS=$goos)"
      mv "$out.tmp" "$out"
      chmod 755 "$out"
      test -s "$out"
      return
    fi
    rm -f "$out.tmp"
  done
  echo "ERREUR: compilation DNSTT impossible pour $abi" >&2
  exit 1
}
build_dnstt arm64 arm64-v8a
build_dnstt amd64 x86_64

# Remove the old writable-assets engines. Native binaries must be extracted by Android;
# executing them from app-internal writable storage is rejected on many Android builds.
rm -rf "$ROOT/app/src/main/assets/engines"

printf '\nEngine bootstrap complete.\n'
printf 'Native engines:\n'
find "$JNI" -maxdepth 2 -type f -printf '  %p (%s bytes)\n' | sort
printf 'AAR: %s\n' "$LIBS/hev-socks5-tunnel.aar"
