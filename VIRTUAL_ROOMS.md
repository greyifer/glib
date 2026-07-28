# G'Lib Virtual Rooms

Virtual rooms are multiplayer spaces rendered from portable `.gsave` archives without placing their blocks in a Minecraft world and without creating a dimension.

G'Lib teleports each room session to a verified-empty host volume in an existing world. It sends the room as per-player vanilla block-section updates, supplies the same collision shapes to the server, and restores the real client view when a player leaves. Players in one session share the same anchor and therefore see one another normally. Separate sessions receive separate anchors.

## What a `.gsave` contains

The version 1 format stores:

- a path-safe room ID and display name;
- normalized positive dimensions;
- sparse non-air block states and every state property;
- visual block-entity NBT, such as signs and banners;
- room metadata;
- named spawn, hotspot, and custom markers.

The lowest selected world corner is always local block `(0, 0, 0)`. Air is implicit. Because a session can only use a host volume whose real blocks and two-block margin are verified as air, G'Lib only needs to send sparse non-air states and replay the affected chunks when Minecraft reloads them.

The file is a deterministic binary envelope with `GSAV` magic, a format version, raw-DEFLATE payload, declared uncompressed size, and SHA-256 checksum. Decoding is bounded and rejects malformed UTF-8, invalid identifiers, duplicate positions, corrupt checksums, trailing data, decompression mismatches, and unsupported versions.

Room files live in:

```text
<world>/glib/rooms/<room-id>.gsave
```

Saving is an atomic same-directory replacement. G'Lib checks the directory once per second, including a content digest so a same-size edit with a preserved timestamp is still detected. A structurally valid and runtime-compatible replacement updates the menu and every running instance of that room. A partial edit leaves the last valid copy loaded. A structurally valid archive that references unavailable blocks, invalid or incompatible block-entity data, unsafe spawns, excessive runtime dimensions, or excessive block-entity payloads is retained on disk but hidden from player-facing entry lists; `/glib room validate` reports the reason and an active session keeps its previous resolved revision. Missing files require three consecutive scans before removal, preventing a brief non-atomic editor gap from closing sessions.

## Authoring workflow

Build the room normally in an editing area, then run:

```text
/glib room edit pos1
/glib room edit pos2
/glib room edit marker add default spawn
/glib room edit marker add control-panel hotspot
/glib room edit save dining-car Dining Car
```

`pos1` and `pos2` use the executor's current block unless coordinates are supplied. Spawn markers must have a virtual floor, clear feet, and clear head space. Running `save` again with the same ID atomically replaces its `.gsave` and live-reloads active sessions. When repository sync is configured, `edit export` performs the same local save and then creates or updates the Railway copy.

Useful runtime commands:

```text
/glib room list [page]
/glib room enter dining-car
/glib room invite PlayerName
/glib room sessions
/glib room join <session-uuid>
/glib room leave
/glib room where
/glib room coords true
/glib room tp 4.5 1 7.5
/glib room tp marker default
/glib room reload
/glib room validate dining-car
/glib room repo status
/glib room repo pull
/glib room repo push dining-car
/glib room test status
/glib room test resend
```

Selection, saving/exporting, repository operations, validation, reload, session inspection, and test commands require permission level 2. Entering, inviting, joining, leaving, coordinates, and local teleport commands are available to players. The chat library is limited to 20 entries per page. Session UUIDs act as invite capabilities and are not suggested to unrelated players. The G'Lib menu also has a live, paginated **Virtual Rooms** library for searching and entering saved rooms; on servers without the protocol it shows an explicit unsupported state. A server sends at most 2,048 compatible summaries to one client and marks a larger library as truncated.

## Java API

```java
RoomSessionInfo session = GlibRooms.enter(player, RoomId.of("dining-car"));

Vec3d localFeet = GlibRooms.localPosition(player).orElseThrow();
RoomPosition localBlock = GlibRooms.localBlockPosition(player).orElseThrow();
Vec3d hostPosition = GlibRooms.toWorld(player, new Vec3d(4.5, 1.0, 7.5)).orElseThrow();
GlibRooms.teleportLocal(player, 4.5, 1.0, 7.5, 90.0F, 0.0F);
GlibRooms.teleportMarker(player, "default");
GlibRooms.coordinateHud(player, true);
GlibRooms.setVisualBlock(player, localBlock, Blocks.OAK_DOOR.getDefaultState());
GlibRooms.resetVisualBlock(player, localBlock);
```

Applications can add safe virtual interactions without invoking the host world's block logic:

```java
RoomInteractionCallback.EVENT.register(context -> {
	if (context.marker().map(RoomMarker::id).filter("control-panel"::equals).isPresent()) {
		// Open an application screen or change application state here.
		return ActionResult.SUCCESS;
	}
	return ActionResult.PASS;
});
```

When no callback consumes a virtual block interaction, G'Lib simulates doors, trapdoors, fence gates, levers, and buttons as session-local state and broadcasts the visual/collision change to all members. Block attacks, free item use, entity use/attacks, sign edits, item-drop paths, and real item/experience pickup are consumed for active room participants. Any open real container screen is closed before entry. This prevents participants from breaking, placing, filling, activating, damaging, launching projectiles into, dropping items into, or remotely operating containers in the real host world.

## Railway repository

The G'Network backend includes an authenticated shared room repository:

- `GET /v1/rooms`
- `POST /v1/rooms`
- `GET /v1/rooms/:roomId`
- `PUT /v1/rooms/:roomId`
- `DELETE /v1/rooms/:roomId`

It stores validated version-1 `.gsave` envelopes in PostgreSQL with SHA-256 metadata, a globally unique filesystem-safe canonical archive slug, an 8 MiB upload limit, bounded inflation and embedded-ID verification, per-owner count and byte quotas, owner-only replacement/deletion, revision ETags, paginated listing, and optimistic update concurrency through `If-Match`. Successful writes publish `room.created`, `room.updated`, or `room.deleted` on the existing WebSocket so a repository synchronizer can atomically replace files in the server library and trigger the normal live-reload path.

For dedicated-server synchronization, configure the same high-entropy secret in two places:

```text
# Railway backend
GLIB_ROOM_SYNC_TOKEN=<32-256 printable ASCII characters>
GLIB_ROOM_SYNC_OWNER_UUID=<existing G'Network user UUID>

# Minecraft dedicated server
GLIB_ROOM_REPOSITORY_URL=https://your-railway-domain
GLIB_ROOM_REPOSITORY_TOKEN=<the same secret>
```

The Java system-property equivalents are `glib.rooms.repositoryUrl` and `glib.rooms.repositoryToken`. The secret is sent only in `X-Glib-Room-Sync-Token`, redirects are disabled, and plain HTTP is accepted only for loopback development. Service-authenticated listing is scoped to `GLIB_ROOM_SYNC_OWNER_UUID`, so another authenticated user's shared upload cannot enter a dedicated server's automatic library. The server performs an initial pull shortly after startup and polls every 30 seconds. Downloads are isolated, fully decoded, runtime-validated, and then installed as their already-verified archive bytes; one malformed remote entry is quarantined without blocking valid rooms.

A persistent checksum index provides three-way synchronization. An unchanged mirror follows the remote revision, while a locally edited copy is preserved and reported as a conflict instead of being overwritten. Final install and delete operations claim the exact archive being verified and publish with no-clobber filesystem operations, so an atomic editor save that races synchronization wins safely. Remote absence must be observed in three complete pulls before an unchanged mirror is deleted, and a concurrent push prevents that deletion. Repeated exports retain the newest snapshot instead of silently coalescing it into an older in-flight upload. Remote changes flow through the same live-library listener and update active sessions. `repo pull` forces reconciliation, and `repo push` or `edit export` performs an ETag-protected upsert.

See [backend/README.md](backend/README.md) for deployment and endpoint details.

## Current boundaries

- Runtime axes are limited to 256 blocks and runtime volume to 262,144 cells, even though the archive codec accepts larger offline assets.
- A runtime room may contain at most 256 visual block entities, at most 256 KiB encoded NBT per block entity, and 4 MiB in aggregate. Their update packets are paced across server ticks and are not multiplied by normal overlay retry passes.
- Room blocks and visual block entities are captured. Ordinary entities, mobs, paintings, item frames, scheduled ticks, redstone networks, inventories, and arbitrary vanilla block-entity menus are not simulated.
- Water and lava can be rendered as saved block states, but real fluid flow, swimming, buoyancy, breathing, and fluid damage are not simulated.
- Virtual doors, levers, and buttons are session state, not real redstone.
- Host lighting, sky, weather, ambient sound, and entities outside an enclosed room still belong to the existing world.
- The host volume and a two-block safety margin must remain air in already loaded chunks. G'Lib incrementally revalidates it and closes the session if another system dirties it.
- Client chunk replay requests are admitted before server-thread scheduling, rate-limited per player, bounded per participant, and subject to a five-second per-chunk cooldown.
- Every client needs G'Lib and the same block registry/resource assets as the server. Missing blocks fail validation instead of silently becoming air.
- Repository mirroring requires same-directory atomic moves and hard-link support (standard on NTFS and typical Linux server volumes); it fails closed instead of risking an overwrite when the filesystem cannot provide them.
- Repository display names are limited to 80 visible characters, even though local `.gsave` display names may be longer.
- Players are returned before normal leave, disconnect, or server shutdown. Before entry, G'Lib also atomically journals the original world position in `<world>/glib/pending-room-returns.properties`; an interrupted process recovers that point on the player's next login before clearing it. The journal is capped at 4,096 entries and 1 MiB; a new entry fails safely without moving the player when either bound would be exceeded.
