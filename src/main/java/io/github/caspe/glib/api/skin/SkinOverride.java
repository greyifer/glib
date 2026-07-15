package io.github.caspe.glib.api.skin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

@Environment(EnvType.CLIENT)
public record SkinOverride(
	Identifier texture,
	@Nullable String textureUrl,
	@Nullable Identifier capeTexture,
	@Nullable Identifier elytraTexture,
	SkinTextures.Model model,
	boolean secure
) {
	public SkinOverride {
		if (texture == null) {
			throw new IllegalArgumentException("texture cannot be null");
		}
		if (model == null) {
			throw new IllegalArgumentException("model cannot be null");
		}
	}

	public static SkinOverride of(Identifier texture, SkinTextures.Model model) {
		return new SkinOverride(texture, null, null, null, model, false);
	}

	public static SkinOverride replacingTexture(SkinTextures baseTextures, Identifier texture) {
		return new SkinOverride(
			texture,
			null,
			baseTextures.capeTexture(),
			baseTextures.elytraTexture(),
			baseTextures.model(),
			false
		);
	}

	public SkinOverride withTexture(Identifier texture) {
		return new SkinOverride(texture, textureUrl, capeTexture, elytraTexture, model, secure);
	}

	public SkinTextures toSkinTextures() {
		return new SkinTextures(texture, textureUrl, capeTexture, elytraTexture, model, secure);
	}
}
