package io.github.caspe.glib.api.room;

import java.io.IOException;

/**
 * Signals an invalid, corrupt, unsupported, or over-limit {@code .gsave} archive.
 */
public final class GsaveException extends IOException {
	public GsaveException(String message) {
		super(message);
	}

	public GsaveException(String message, Throwable cause) {
		super(message, cause);
	}
}
