# G'Lib

G'Lib is a shared Fabric library for Minecraft 1.21.1. It provides player skin editing and synchronization, reusable HUD components, editor UI helpers, common utilities, and G'Network friends and chat.

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.19.3 or newer
- Fabric API
- Java 21

## Skin API

```java
GameProfile profile = player.getGameProfile();
UUID playerId = player.getUuid();

SkinService.createMutableSkin(profile, "edited_skin").thenAccept(texture -> {
	SkinOverrideRegistry.set(playerId, texture, player.getSkinTextures());
	texture.setRegionPixelArgb(SkinRegions.FACE, 0, 0, 0xFFFF0000);
});
```

Mutable skin textures upload changed pixels on the next client tick. G'Lib also includes region fills, blending, brushes, undo and redo history, animations, face extraction, render hooks, and server-to-client skin synchronization.

Other APIs cover synchronized timers, animated HUD lists, configuration screens, editor layouts, keybind menus, fake players, broadcasts, and lightweight event handling.

## G'Network

Press `K` and open **Friends & Chat** to send requests to other G'Lib players, see presence, and chat across Minecraft servers. Players authenticate with their current Minecraft session; the G'Network backend never receives their Microsoft/Minecraft access token.

The deployable backend is in [`backend`](backend). See [`backend/README.md`](backend/README.md) for Railway and local setup. Before publishing the mod, replace the placeholder URL in [`src/main/resources/glib-network.properties`](src/main/resources/glib-network.properties) with the Railway HTTPS domain. This URL is public configuration, not a secret.

Security reports and secret-handling guidance are in [`SECURITY.md`](SECURITY.md).

## Building

Clone the repository and run:

```shell
./gradlew build
```

On Windows, use `gradlew.bat build`. Built jars are written to `build/libs`.

## License

G'Lib is licensed under the [GNU General Public License v3.0](LICENSE).
