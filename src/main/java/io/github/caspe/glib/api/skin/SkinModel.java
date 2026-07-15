package io.github.caspe.glib.api.skin;

import java.util.Locale;

public enum SkinModel {
	WIDE("wide"),
	SLIM("slim");

	private final String serializedName;

	SkinModel(String serializedName) {
		this.serializedName = serializedName;
	}

	public String serializedName() {
		return serializedName;
	}

	public static SkinModel fromName(String name) {
		if (name == null) {
			return WIDE;
		}
		return "slim".equals(name.toLowerCase(Locale.ROOT)) ? SLIM : WIDE;
	}
}
