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
package dev.nuclr.plugin.core.panel.redis.keys;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Keys, members and values are byte strings. These turn them into text for display,
 * the way {@code redis-cli} does: UTF-8 when it is UTF-8, {@code \xNN} for every byte
 * that is not, and control characters escaped either way so a row stays one line.
 */
public final class KeyNames {

	private KeyNames() {
	}

	/** The bytes as UTF-8 text, or {@code null} when they are not valid UTF-8. */
	public static String utf8OrNull(byte[] bytes) {
		if (bytes == null) {
			return null;
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes))
					.toString();
		} catch (CharacterCodingException e) {
			return null;
		}
	}

	/** One-line text for a key, field or member. */
	public static String display(byte[] bytes) {
		if (bytes == null) {
			return "";
		}
		String text = utf8OrNull(bytes);
		var out = new StringBuilder(bytes.length);
		if (text != null) {
			text.codePoints().forEach(cp -> {
				if (Character.isISOControl(cp)) {
					out.append(escape(cp));
				} else {
					out.appendCodePoint(cp);
				}
			});
			return out.toString();
		}
		for (byte b : bytes) {
			int value = b & 0xff;
			if (value >= 0x20 && value < 0x7f) {
				out.append((char) value);
			} else {
				out.append(escape(value));
			}
		}
		return out.toString();
	}

	private static String escape(int value) {
		return switch (value) {
			case '\n' -> "\\n";
			case '\r' -> "\\r";
			case '\t' -> "\\t";
			default -> value <= 0xff ? String.format(Locale.ROOT, "\\x%02x", value)
					: String.format(Locale.ROOT, "\\u%04x", value);
		};
	}

	public static byte[] bytes(String text) {
		return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] concat(byte[] a, byte[] b) {
		byte[] joined = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, joined, a.length, b.length);
		return joined;
	}

	public static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

	/** Index of {@code needle} in {@code bytes} at or after {@code from}, or -1. */
	public static int indexOf(byte[] bytes, byte[] needle, int from) {
		if (needle.length == 0) {
			return -1;
		}
		outer:
		for (int i = Math.max(0, from); i <= bytes.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (bytes[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	/**
	 * A {@code SCAN MATCH} pattern for every key starting with {@code prefix}: the prefix
	 * with the glob characters {@code * ? [ ] \} escaped, then {@code *}.
	 */
	public static byte[] prefixPattern(byte[] prefix) {
		var out = new java.io.ByteArrayOutputStream(prefix.length + 8);
		for (byte b : prefix) {
			if (b == '*' || b == '?' || b == '[' || b == ']' || b == '\\') {
				out.write('\\');
			}
			out.write(b);
		}
		out.write('*');
		return out.toByteArray();
	}
}
