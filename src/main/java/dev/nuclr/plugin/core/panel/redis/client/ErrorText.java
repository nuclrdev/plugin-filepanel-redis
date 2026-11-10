/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.redis.client;

import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

/** A client failure as one readable line or two, for a dialog. */
public final class ErrorText {

	private static final int MAX = 600;

	private ErrorText() {
	}

	public static String describe(Throwable e) {
		if (isAuthenticationFailure(e)) {
			return clip("Authentication failed: " + innermost(e));
		}
		if (e instanceof JedisConnectionException) {
			return clip("Could not reach the server: " + innermost(e));
		}
		String message = e.getMessage();
		return clip(message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
	}

	/** Whether the server refused the credentials, so a stored password may be wrong. */
	public static boolean isAuthenticationFailure(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof JedisAccessControlException) {
				return true;
			}
			String message = t.getMessage();
			if (t instanceof JedisDataException && message != null
					&& (message.startsWith("NOAUTH") || message.startsWith("WRONGPASS"))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The deepest message, with the exception's name when it is a plain Java one: the
	 * refused connection or unknown host rather than the pool's wrapper around it.
	 */
	static String innermost(Throwable e) {
		Throwable deepest = e;
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t.getMessage() != null && !t.getMessage().isBlank()) {
				deepest = t;
			}
		}
		String message = deepest.getMessage();
		if (message == null || message.isBlank()) {
			return deepest.getClass().getSimpleName();
		}
		boolean plainJava = deepest.getClass().getName().startsWith("java.");
		return plainJava ? deepest.getClass().getSimpleName() + ": " + message : message;
	}

	private static String clip(String text) {
		return text.length() <= MAX ? text : text.substring(0, MAX) + "...";
	}
}
