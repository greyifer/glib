package io.github.caspe.glib.api.room;

import java.util.Objects;
import java.util.regex.Pattern;

final class RoomValidation {
	static final int MAX_AXIS = 4096;
	static final int MAX_BLOCKS = 2_000_000;
	static final int MAX_MARKERS = 16_384;
	static final int MAX_METADATA_ENTRIES = 4096;
	static final int MAX_BLOCK_PROPERTIES = 256;
	static final int MAX_STRING_BYTES = 65_536;
	static final int MAX_BLOCK_ENTITY_BYTES = 16 * 1024 * 1024;

	private static final Pattern ROOM_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Pattern RESOURCE_ID =
			Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
	private static final Pattern MARKER_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
	private static final Pattern PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");

	private RoomValidation() {
	}

	static String roomId(String value) {
		String validated = matches(value, ROOM_ID, "room id");
		String windowsStem = validated.split("\\.", 2)[0];
		if (windowsStem.equals("con")
				|| windowsStem.equals("prn")
				|| windowsStem.equals("aux")
				|| windowsStem.equals("nul")
				|| windowsStem.matches("(?:com|lpt)[1-9]")) {
			throw new IllegalArgumentException("Room id is reserved by Windows: " + value);
		}
		return validated;
	}

	static String resourceId(String value, String label) {
		return matches(value, RESOURCE_ID, label);
	}

	static String markerId(String value) {
		return matches(value, MARKER_ID, "marker id");
	}

	static String propertyName(String value) {
		return matches(value, PROPERTY_NAME, "block-state property name");
	}

	static String text(String value, String label, int maxCharacters) {
		Objects.requireNonNull(value, label);
		if (value.length() > maxCharacters) {
			throw new IllegalArgumentException(label + " is longer than " + maxCharacters + " characters");
		}
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '\0') {
				throw new IllegalArgumentException(label + " contains an unsupported character");
			}
			if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
					throw new IllegalArgumentException(label + " contains an unpaired surrogate");
				}
				index++;
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException(label + " contains an unpaired surrogate");
			}
		}
		return value;
	}

	static String metadataKey(String value) {
		text(value, "metadata key", 128);
		if (value.isBlank()) {
			throw new IllegalArgumentException("metadata key must not be blank");
		}
		return value;
	}

	private static String matches(String value, Pattern pattern, String label) {
		Objects.requireNonNull(value, label);
		if (!pattern.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
