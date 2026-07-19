package io.github.caspe.glib.client.social;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class SocialScreenSupport {
	private SocialScreenSupport() {
	}

	static String message(Throwable error) {
		while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
			error = error.getCause();
		}
		String message = error == null ? null : error.getMessage();
		return message == null || message.isBlank() ? "Something went wrong." : message;
	}
}
