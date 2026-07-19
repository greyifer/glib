package io.github.caspe.glib.client.social;

public final class GlibNetworkException extends RuntimeException {
	private final String code;

	public GlibNetworkException(String code, String message) {
		super(message);
		this.code = code;
	}

	public GlibNetworkException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
