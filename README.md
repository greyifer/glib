# G'Lib

G'Lib is a shared Fabric library for Minecraft 1.21.1. It provides player skin editing and synchronization, reusable HUD components, editor UI helpers, and common utilities for networking, events, cooldowns, sounds, and player data.

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

## Building

Clone the repository and run:

```shell
./gradlew build
```

On Windows, use `gradlew.bat build`. Built jars are written to `build/libs`.

## License

G'Lib is licensed under the [GNU General Public License v3.0](LICENSE).
