# TOM_TUNNEL Android 1.0.0

**TOM_TUNNEL** — By JOELTOM

Client VPN Android moderne (WebView UI + moteurs natifs), basé sur le moteur validé JephTunnel 1.3.0 et entièrement rebrandé.

## Protocoles supportés (fonctionnels)

- **Xray / V2Ray** : VLESS, VMess, Trojan, Shadowsocks (TCP, WS, gRPC, HTTP/2, TLS, Reality, SNI, fingerprint)
- **SSH SlowDNS / DNSTT** : tunnel SSH via DNS (DNSTT → TCP local → JSch → SOCKS5)
- **hev-socks5-tunnel** : TUN → SOCKS5 (IPv4 + IPv6)
- DNS personnalisé, Mode Boost, import/export, journal, reconnexion

## Identité

| Élément | Valeur |
|--------|--------|
| Nom | TOM_TUNNEL |
| Package | `com.tomtunnel.app` |
| Développeur | By JOELTOM |
| Version | 1.0.0 |
| Telegram | https://t.me/FREE_INTERNET237 |
| WhatsApp | +237 654 145 540 |

Aucune référence à « JephTunnel » / « Jephte Ouedraogo » ne reste dans l’UI ni le package.

## Langues

- Français (par défaut)
- English  
Changement depuis **Plus → Langue**.

## Partage de configurations par clé

Format de clé : `TOM_TUNNEL-XXXXX-XXXXX`

Flux client (déjà branché dans l’UI) :

1. **Créer une clé** : chiffre la config du serveur sélectionné et `POST /configs`
2. **Importer avec une clé** : `GET /configs/{key}` puis import automatique
3. Partage / copie de la clé

API de base : `https://api.tomtunnel.com/v1/` (HTTPS uniquement)

Voir `docs/API.md` pour le contrat complet et un squelette backend Node.js/Express + PostgreSQL.

## Structure du projet

```
TOM_TUNNEL/
├── app/
│   ├── build.gradle.kts          # applicationId com.tomtunnel.app
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html        # UI moderne (FR/EN, thème, partage clé)
│       │   └── img/              # logo, shield, avatar
│       ├── java/com/tomtunnel/app/
│       │   ├── MainActivity.kt
│       │   ├── TomVpnService.kt
│       │   ├── NetInfo.kt, VpnBus.kt, WebActivity.kt
│       │   └── engine/
│       │       ├── Engine.kt, EngineAssets.kt
│       │       ├── XrayEngine.kt
│       │       └── SlowDnsEngine.kt
│       ├── jniLibs/              # arm64-v8a, x86_64 (via bootstrap)
│       └── res/
├── scripts/bootstrap-engines.sh  # Xray + hev AAR + DNSTT
├── docs/API.md
└── README.md
```

## Bootstrap des moteurs (obligatoire une fois)

L’archive ne contient pas les binaires tiers. Depuis la racine du projet :

```bash
chmod +x scripts/bootstrap-engines.sh
./scripts/bootstrap-engines.sh
```

Télécharge Xray Android et l’AAR hev-socks5-tunnel, compile DNSTT pour ARM64 et x86_64.

## Build

Android Studio (AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 24) :

```bash
./gradlew :app:assembleDebug
```

Ou ouvrir le dossier `TOM_TUNNEL` dans Android Studio.

Sur Termux ARM64, utiliser `build-termux.sh` (voir commentaires dans le script).

## Sécurité

- Configurations stockées localement ; export chiffré (AES via Web Crypto, format JEPH1/2/3 conservé pour compatibilité d’import des anciens fichiers)
- Clés de partage API : AES-256 côté serveur recommandé, rate limiting, expiration / révocation
- `usesCleartextTraffic=false`, pas de backup Android des préférences sensibles
- VPN non marqué « connecté » tant que tun2socks + test TCP 1.1.1.1:443 ne sont pas OK

## Compatibilité

- Android 8+ (minSdk 24 → Android 7+, ciblé 8–16)
- ABI : arm64-v8a, x86_64 (bootstrap). armeabi-v7a / x86 possibles si vous ajoutez les libs natives.

## Notes de migration

- Package : `com.jephtunnel.app` → `com.tomtunnel.app`
- Service : `JephVpnService` → `TomVpnService`
- Préférences : `tomtunnel_state`
- Extension export : `.tom` (import toujours accepté pour `.jeph` et JSON)
- Les formats de lien/crypto `JEPH1.` / `Jeph:` restent importables pour ne pas casser les configs existantes
