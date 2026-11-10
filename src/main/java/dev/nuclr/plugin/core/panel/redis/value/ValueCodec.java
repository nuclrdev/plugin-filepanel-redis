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
package dev.nuclr.plugin.core.panel.redis.value;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Entry;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Field;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Scored;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.SetValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StreamValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;
import dev.nuclr.plugin.core.panel.redis.keys.KeyNames;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Values as text and back. A string is edited as itself; a hash as a JSON object of
 * field to value, a list or set as a JSON array, a sorted set as a JSON object of member
 * to score. Only values that are wholly UTF-8 and were read whole can be edited, since
 * anything else would not come back byte for byte.
 */
public final class ValueCodec {

	/** Why a value cannot be opened in the editor; the message is for the user. */
	public static final class NotEditable extends Exception {

		private static final long serialVersionUID = 1L;

		public NotEditable(String message) {
			super(message, null, false, false);
		}
	}

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String PLUS_INF = "+inf";
	private static final String MINUS_INF = "-inf";

	private ValueCodec() {
	}

	// ------------------------------------------------------------------
	// Editing
	// ------------------------------------------------------------------

	/** The text the editor opens with. */
	public static String toEditText(RedisValue value) throws NotEditable {
		if (!value.type().editable()) {
			throw new NotEditable(value.type() == KeyType.STREAM ? tr("edit.not.stream")
					: tr("edit.not.type", value.type().redisName()));
		}
		if (value.truncated()) {
			throw new NotEditable(tr("edit.too.big", String.valueOf(value.total())));
		}
		return switch (value) {
			case StringValue s -> requireUtf8(s.bytes());
			case HashValue h -> {
				ObjectNode object = MAPPER.createObjectNode();
				for (Field field : h.fields()) {
					object.put(requireUtf8(field.name()), requireUtf8(field.value()));
				}
				yield pretty(object);
			}
			case ListValue l -> pretty(array(l.items()));
			case SetValue s -> pretty(array(s.members()));
			case ZSetValue z -> {
				ObjectNode object = MAPPER.createObjectNode();
				for (Scored member : z.members()) {
					String name = requireUtf8(member.member());
					double score = member.score();
					if (Double.isInfinite(score)) {
						object.put(name, score > 0 ? PLUS_INF : MINUS_INF);
					} else {
						object.put(name, score);
					}
				}
				yield pretty(object);
			}
			default -> throw new NotEditable(tr("edit.not.type", value.type().redisName()));
		};
	}

	private static ArrayNode array(List<byte[]> items) throws NotEditable {
		ArrayNode array = MAPPER.createArrayNode();
		for (byte[] item : items) {
			array.add(requireUtf8(item));
		}
		return array;
	}

	private static String requireUtf8(byte[] bytes) throws NotEditable {
		String text = KeyNames.utf8OrNull(bytes);
		if (text == null) {
			throw new NotEditable(tr("edit.not.utf8"));
		}
		return text;
	}

	/**
	 * The value the edited text stands for.
	 *
	 * @throws IllegalArgumentException with a message for the user when the text is not
	 *                                  the shape the type needs
	 */
	public static RedisValue fromEditText(KeyType type, String text) {
		if (type == KeyType.STRING) {
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			return new StringValue(bytes, bytes.length);
		}
		JsonNode root = parse(text);
		return switch (type) {
			case HASH -> {
				requireShape(root.isObject(), "edit.shape.hash");
				var fields = new ArrayList<Field>();
				for (Map.Entry<String, JsonNode> e : root.properties()) {
					fields.add(new Field(KeyNames.bytes(e.getKey()), KeyNames.bytes(scalar(e.getValue()))));
				}
				requireNotEmpty(fields.isEmpty());
				yield new HashValue(fields, fields.size());
			}
			case LIST, SET -> {
				requireShape(root.isArray(), type == KeyType.LIST ? "edit.shape.list" : "edit.shape.set");
				var items = new ArrayList<byte[]>();
				for (JsonNode item : root.values()) {
					items.add(KeyNames.bytes(scalar(item)));
				}
				requireNotEmpty(items.isEmpty());
				yield type == KeyType.LIST ? new ListValue(items, items.size()) : new SetValue(items, items.size());
			}
			case ZSET -> {
				requireShape(root.isObject(), "edit.shape.zset");
				var members = new ArrayList<Scored>();
				for (Map.Entry<String, JsonNode> e : root.properties()) {
					members.add(new Scored(KeyNames.bytes(e.getKey()), score(e.getKey(), e.getValue())));
				}
				requireNotEmpty(members.isEmpty());
				yield new ZSetValue(members, members.size());
			}
			default -> throw new IllegalArgumentException(tr("edit.not.type", type.redisName()));
		};
	}

	private static JsonNode parse(String text) {
		try {
			JsonNode root = MAPPER.readTree(text);
			if (root == null || root.isMissingNode()) {
				throw new IllegalArgumentException(tr("edit.json.empty"));
			}
			return root;
		} catch (JacksonException e) {
			throw new IllegalArgumentException(tr("edit.json.invalid", e.getOriginalMessage()), e);
		}
	}

	private static void requireShape(boolean ok, String key) {
		if (!ok) {
			throw new IllegalArgumentException(tr(key));
		}
	}

	/** Redis keeps no empty hash, list or set: writing one would delete the key. */
	private static void requireNotEmpty(boolean empty) {
		if (empty) {
			throw new IllegalArgumentException(tr("edit.empty"));
		}
	}

	/** A string as itself; a number, boolean or nested object as its JSON. */
	private static String scalar(JsonNode node) {
		if (node.isString()) {
			return node.stringValue();
		}
		if (node.isNull()) {
			throw new IllegalArgumentException(tr("edit.null"));
		}
		return node.isValueNode() ? node.asString() : MAPPER.writeValueAsString(node);
	}

	private static double score(String member, JsonNode node) {
		if (node.isNumber()) {
			return node.doubleValue();
		}
		if (node.isString()) {
			String text = node.stringValue().trim().toLowerCase(Locale.ROOT);
			switch (text) {
				case PLUS_INF, "inf", "infinity", "+infinity" -> {
					return Double.POSITIVE_INFINITY;
				}
				case MINUS_INF, "-infinity" -> {
					return Double.NEGATIVE_INFINITY;
				}
				default -> {
					try {
						return Double.parseDouble(text);
					} catch (NumberFormatException e) {
						// reported below
					}
				}
			}
		}
		throw new IllegalArgumentException(tr("edit.score", member));
	}

	/** The extension that gets the editor to highlight the text the way it is written. */
	public static String fileExtension(KeyType type, String editText) {
		if (type != KeyType.STRING) {
			return ".json";
		}
		return prettyJsonOrNull(editText) != null ? ".json" : ".txt";
	}

	// ------------------------------------------------------------------
	// Display
	// ------------------------------------------------------------------

	/**
	 * The text of a JSON object or array laid out over lines, or {@code null} when
	 * {@code text} is not one. Scalars are left alone: {@code 42} is a number, not a document.
	 */
	public static String prettyJsonOrNull(String text) {
		if (text == null) {
			return null;
		}
		String trimmed = text.strip();
		if (!(trimmed.startsWith("{") && trimmed.endsWith("}")) && !(trimmed.startsWith("[") && trimmed.endsWith("]"))) {
			return null;
		}
		try {
			return pretty(MAPPER.readTree(trimmed));
		} catch (JacksonException e) {
			return null;
		}
	}

	/** Laid out with {@code \n} on every OS; line breaks inside values are escaped, so none are lost. */
	private static String pretty(JsonNode node) {
		return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n") + "\n";
	}

	/**
	 * The value as a document, for F5 and for viewers reading the resource as a file: a
	 * string's own bytes, anything else as JSON with non-UTF-8 bytes escaped.
	 */
	public static byte[] document(RedisValue value) {
		if (value instanceof StringValue s) {
			return s.bytes();
		}
		JsonNode node = switch (value) {
			case HashValue h -> {
				ObjectNode object = MAPPER.createObjectNode();
				h.fields().forEach(f -> object.put(KeyNames.display(f.name()), loose(f.value())));
				yield object;
			}
			case ListValue l -> looseArray(l.items());
			case SetValue s -> looseArray(s.members());
			case ZSetValue z -> {
				ObjectNode object = MAPPER.createObjectNode();
				for (Scored member : z.members()) {
					String name = KeyNames.display(member.member());
					if (Double.isInfinite(member.score())) {
						object.put(name, member.score() > 0 ? PLUS_INF : MINUS_INF);
					} else {
						object.put(name, member.score());
					}
				}
				yield object;
			}
			case StreamValue s -> {
				ArrayNode entries = MAPPER.createArrayNode();
				for (Entry entry : s.entries()) {
					ObjectNode fields = MAPPER.createObjectNode();
					entry.fields().forEach(f -> fields.put(KeyNames.display(f.name()), loose(f.value())));
					ObjectNode row = MAPPER.createObjectNode();
					row.put("id", entry.id());
					row.set("fields", fields);
					entries.add(row);
				}
				yield entries;
			}
			default -> MAPPER.createObjectNode();
		};
		return pretty(node).getBytes(StandardCharsets.UTF_8);
	}

	private static ArrayNode looseArray(List<byte[]> items) {
		ArrayNode array = MAPPER.createArrayNode();
		items.forEach(item -> array.add(loose(item)));
		return array;
	}

	/** UTF-8 as is, anything else escaped. Unlike {@link KeyNames#display} keeps newlines. */
	private static String loose(byte[] bytes) {
		String text = KeyNames.utf8OrNull(bytes);
		return text != null ? text : KeyNames.display(bytes);
	}

	/** A classic hex dump, 16 bytes a line, for a binary string. */
	public static String hexDump(byte[] bytes, int max) {
		int length = Math.min(bytes.length, max);
		var out = new StringBuilder(length * 4 + 64);
		HexFormat hex = HexFormat.of();
		for (int offset = 0; offset < length; offset += 16) {
			out.append(String.format(Locale.ROOT, "%08x  ", offset));
			int end = Math.min(length, offset + 16);
			for (int i = offset; i < offset + 16; i++) {
				out.append(i < end ? hex.toHexDigits(bytes[i]) + " " : "   ");
				if (i == offset + 7) {
					out.append(' ');
				}
			}
			out.append(" |");
			for (int i = offset; i < end; i++) {
				int b = bytes[i] & 0xff;
				out.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
			}
			out.append("|\n");
		}
		return out.toString();
	}

	/** Whether a string's bytes read as text: valid UTF-8 without NUL. */
	public static boolean isText(byte[] bytes) {
		String text = KeyNames.utf8OrNull(bytes);
		return text != null && text.indexOf('\0') < 0;
	}
}
