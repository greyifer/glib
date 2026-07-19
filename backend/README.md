# G'Network backend

This service supplies cross-server friends and chat to the G'Lib Fabric client. It uses PostgreSQL for durable state, Mojang's Minecraft session service for account proof, REST for social actions, and WebSockets for presence and live events.

## Railway deployment

1. Push this repository to GitHub. It may be public or private; no production secret belongs in the repository.
2. In Railway, create a project and add a PostgreSQL database.
3. Add a service from this GitHub repository and set its **Root Directory** to `/backend`. Railway will detect `Dockerfile` there.
4. In the service's **Config as Code** setting, set the config-file path to `/backend/railway.json`. Railway config files are resolved from the repository root, not from the service Root Directory.
5. In the backend service's Variables tab, add:

   ```text
   DATABASE_URL=${{Postgres.DATABASE_URL}}
   TRUST_PROXY=true
   SESSION_TTL_HOURS=12
   MESSAGE_RETENTION_DAYS=90
   ```

   The PostgreSQL service name in the reference may differ from `Postgres`; use Railway's autocomplete. `DATABASE_URL` is sensitive and must remain a Railway variable. The other values are configuration, not secrets.

6. Generate an HTTPS public domain for the backend. Check `https://YOUR-DOMAIN/health`; it should return `{"status":"ok"}`.
7. Put that public HTTPS URL in `src/main/resources/glib-network.properties`, then build and publish the mod. The URL is not secret. It can also be changed in-game under **Friends & Chat > Settings**.

Database migrations run automatically at startup. Keep the service at one replica initially because live presence and socket routing are held in memory. PostgreSQL remains durable; add Redis-backed pub/sub before scaling to multiple replicas.

## Local development

Requirements: Node.js 22+, pnpm 11.9, and Docker or another PostgreSQL 17 installation.

```shell
docker compose up -d postgres
cp .env.example .env
pnpm install --frozen-lockfile
```

Export the variables from `.env`, then run:

```shell
pnpm build
pnpm start
```

For the Fabric development client, set `-Dglib.network.apiUrl=http://127.0.0.1:8080`. Plain HTTP is accepted only for localhost; production connections require HTTPS.

## API outline

- `POST /v1/auth/challenge`, `POST /v1/auth/complete`: one-use Minecraft account proof
- `GET /v1/social`: friends, incoming/outgoing requests, blocks, and presence
- `GET /v1/players/search?q=...`: registered-player autocomplete with relationship state
- `/v1/friend-requests`, `/v1/friends`, `/v1/blocks`: social actions
- `/v1/messages/:playerUuid`: authorized friend-only history and sending
- `GET /v1/ws`: authenticated live events, chat, and presence
- `DELETE /v1/me`: permanent account-data deletion

All non-authentication routes require `Authorization: Bearer <session>`. Bodies are size-limited, SQL is parameterized, messages and UUIDs are validated, friend/block authorization is enforced server-side, and sensitive headers are not logged.
