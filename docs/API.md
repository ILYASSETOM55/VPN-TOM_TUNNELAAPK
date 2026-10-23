# API de partage des configurations — TOM_TUNNEL

Base URL : `https://api.tomtunnel.com/v1/`  
Transport : **HTTPS uniquement**.

## Endpoints

### POST /configs
Créer une configuration et obtenir une clé.

```json
{
  "app": "TOM_TUNNEL",
  "version": "1.0.0",
  "config": "<base64 ou payload chiffré>",
  "title": "MTN Cameroun",
  "description": "Configuration WebSocket",
  "expires": 30,
  "password": false
}
```

Réponse :

```json
{
  "success": true,
  "key": "TOM_TUNNEL-8H2KF-91AXP",
  "created_at": "2026-10-23T18:00:00Z",
  "expires_at": "2026-11-22T18:00:00Z"
}
```

### GET /configs/{key}
Télécharger une configuration.

### GET /configs/{key}/status
`{ "exists": true, "expired": false, "downloads": 258 }`

### DELETE /configs/{key}
Révoquer / supprimer.

### GET /configs/me
Liste des configs de l’utilisateur (nécessite JWT si comptes activés).

### PUT /configs/{key}
Mettre à jour le payload.

## Format des clés

`TOM_TUNNEL-XXXXX-XXXXX`  
Génération : CSPRNG, alphabet `A-Z0-9` sans ambiguïtés optionnel, unicité en base.

## Sécurité serveur

- TLS
- Chiffrement au repos AES-256 des configs
- Rate limiting
- Expiration + révocation
- Logs d’accès
- Intégrité (HMAC ou signature)

## Squelette backend (Node.js + Express + PostgreSQL)

```bash
# Exemple minimal — à durcir avant production
npm init -y
npm i express pg crypto helmet cors rate-limit
```

```js
// server.js (esquisse)
const express = require('express');
const crypto = require('crypto');
const helmet = require('helmet');
const app = express();
app.use(helmet());
app.use(express.json({ limit: '512kb' }));

function genKey() {
  const part = () => crypto.randomBytes(4).toString('hex').toUpperCase().slice(0, 5);
  return `TOM_TUNNEL-${part()}-${part()}`;
}

// POST /v1/configs  → stocker config chiffrée, retourner key
// GET  /v1/configs/:key
// DELETE /v1/configs/:key
// etc.

app.listen(443);
```

Schéma SQL suggéré :

```sql
CREATE TABLE configs (
  key TEXT PRIMARY KEY,
  title TEXT,
  description TEXT,
  config_cipher BYTEA NOT NULL,
  app TEXT,
  version TEXT,
  downloads INT DEFAULT 0,
  created_at TIMESTAMPTZ DEFAULT now(),
  expires_at TIMESTAMPTZ,
  revoked BOOLEAN DEFAULT false
);
CREATE INDEX ON configs (expires_at);
```

Déploiement recommandé : Docker + Nginx (TLS) + PostgreSQL.  
Documentation OpenAPI / Swagger pour l’évolution (comptes, stats, favoris).

Le client Android appelle déjà ces routes depuis **Plus → Partager une clé**.
