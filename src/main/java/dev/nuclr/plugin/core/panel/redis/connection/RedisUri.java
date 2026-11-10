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
package dev.nuclr.plugin.core.panel.redis.connection;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A {@code redis://user:password@host:port/db} or {@code rediss://...} URL, as cloud
 * consoles hand them out, split into the editor's fields so the password goes to the
 * keychain rather than into the saved host.
 */
public record RedisUri(String host, int port, String username, String password, boolean tls) {

	/** The parts of {@code text}, or {@code null} when it is not a Redis URL. */
	public static RedisUri parse(String text) {
		if (text == null) {
			return null;
		}
		String value = text.strip();
		String lower = value.toLowerCase(Locale.ROOT);
		boolean tls;
		if (lower.startsWith("rediss://")) {
			tls = true;
			value = value.substring("rediss://".length());
		} else if (lower.startsWith("redis://")) {
			tls = false;
			value = value.substring("redis://".length());
		} else {
			return null;
		}
		int path = indexOfAny(value, "/?#");
		if (path >= 0) {
			value = value.substring(0, path);
		}
		String username = null;
		String password = null;
		int at = value.lastIndexOf('@');
		if (at >= 0) {
			String userInfo = value.substring(0, at);
			value = value.substring(at + 1);
			int colon = userInfo.indexOf(':');
			if (colon >= 0) {
				username = decode(userInfo.substring(0, colon));
				password = decode(userInfo.substring(colon + 1));
			} else {
				// redis://secret@host is the old requirepass form: the part is the password.
				password = decode(userInfo);
			}
			if (username != null && username.isEmpty()) {
				username = null;
			}
			if (password != null && password.isEmpty()) {
				password = null;
			}
		}
		String host = value;
		int port = RedisConnectionProfile.DEFAULT_PORT;
		if (host.startsWith("[")) {
			int close = host.indexOf(']');
			if (close > 0) {
				String rest = host.substring(close + 1);
				host = host.substring(1, close);
				port = rest.startsWith(":") ? parsePort(rest.substring(1)) : port;
			}
		} else {
			int colon = host.lastIndexOf(':');
			if (colon >= 0) {
				port = parsePort(host.substring(colon + 1));
				host = host.substring(0, colon);
			}
		}
		return new RedisUri(host.isEmpty() ? "localhost" : host, port, username, password, tls);
	}

	private static int indexOfAny(String text, String chars) {
		for (int i = 0; i < text.length(); i++) {
			if (chars.indexOf(text.charAt(i)) >= 0) {
				return i;
			}
		}
		return -1;
	}

	private static int parsePort(String text) {
		try {
			int port = Integer.parseInt(text.trim());
			return port > 0 && port <= 65535 ? port : RedisConnectionProfile.DEFAULT_PORT;
		} catch (NumberFormatException e) {
			return RedisConnectionProfile.DEFAULT_PORT;
		}
	}

	private static String decode(String text) {
		try {
			return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return text;
		}
	}
}
