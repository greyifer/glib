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
   ADMIN_SESSION_TTL_MINUTES=30
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

## Moderator accounts

Moderator authorization is never stored in the mod, a source file, or a GitHub variable. The backend first verifies the player's live Minecraft session and derives the UUID from Mojang; the UUID is not typed into the login screen. A separate password then unlocks only the moderator account already provisioned for that verified UUID.

There is deliberately no HTTP endpoint for creating moderator accounts. Provision one from a private backend shell that has `DATABASE_URL`:

```text
pnpm build
ADMIN_TARGET_UUID=<verified Minecraft UUID> ADMIN_ROLE=MODERATOR pnpm admin
```

In the deployed Railway service shell, run `node dist/admin-cli.js` after setting the same temporary variables. On PowerShell, set `ADMIN_TARGET_UUID` and `ADMIN_ROLE` as temporary environment variables before running `pnpm admin`. The command prompts for the initial password without echoing it. If the deployment shell has no interactive TTY, provide `ADMIN_INITIAL_PASSWORD` as a temporary secret environment variable and remove it immediately after the command finishes. Never put a real UUID allowlist, password, password hash, database URL, or admin token in this repository.

The player must have opened **Friends & Chat** once before being provisioned. At first moderator login they must replace the initial password. To revoke access, run the same command with `ADMIN_ACTION=revoke`; this disables the account and deletes its active moderator sessions.

Moderator passwords use scrypt with a unique salt. Moderator sessions are stored as SHA-256 token hashes, expire after `ADMIN_SESSION_TTL_MINUTES`, and are bound to the same Mojang-verified player session. Five failed password attempts lock the account for 15 minutes. User, conversation, group, report, and report-status reads are recorded in `admin_audit_log`; audit metadata never contains message bodies or passwords.

## Message reports

Players can report a specific message they received. The server validates that the reporter belongs to that DM or group and rejects self-reports. It then snapshots the nearest message from the reported sender before the selected message, the nearest one after it, and the conversation messages bounded between them. This context remains attached to the report even if ordinary chat retention later removes the source messages. Unusually large excerpts are safely capped and marked as truncated.

The normal report view shows only that bounded snapshot. **Full chat** is a separate moderator action and every use is audited. Reports can be resolved or dismissed; no client-supplied sender, target, or context is trusted.

## API outline

- `POST /v1/auth/challenge`, `POST /v1/auth/complete`: one-use Minecraft account proof
- `GET /v1/social`: friends, incoming/outgoing requests, blocks, and presence
- `GET /v1/players/search?q=...`: registered-player autocomplete with relationship state
- `/v1/friend-requests`, `/v1/friends`, `/v1/blocks`: social actions
- `/v1/messages/:playerUuid`: authorized friend-only history and sending
- `POST /v1/reports`: server-generated report context for a message the reporter can access
- `/v1/admin/*`: UUID-bound, password-unlocked, audited moderator views
- `GET /v1/ws`: authenticated live events, chat, and presence
- `DELETE /v1/me`: permanent account-data deletion

All non-authentication routes require `Authorization: Bearer <session>`. Moderator data routes additionally require a short-lived moderator bearer token and reject accounts that have not replaced their initial password. Bodies are size-limited, SQL is parameterized, messages and UUIDs are validated, friend/block authorization is enforced server-side, and sensitive headers and request bodies are not logged.
