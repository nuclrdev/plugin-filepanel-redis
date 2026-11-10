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
package dev.nuclr.plugin.core.panel.redis.support;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import dev.nuclr.plugin.core.panel.redis.client.KeyInfo;
import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession;
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
import dev.nuclr.plugin.core.panel.redis.client.Values;

/**
 * An in-memory Redis for tests: whole values per key, {@code SCAN} paged by position,
 * {@code MATCH} understood for the escaped-prefix patterns the plugin sends.
 */
public final class FakeSession implements RedisSession {

	private record Stored(RedisValue value, long ttl) {
	}

	private final Map<Integer, TreeMap<ByteBuffer, Stored>> dbs = new HashMap<>();
	public int databases = 16;
	public String mode = "standalone";
	public int scans;
	/** When set, SCAN ignores COUNT and answers everything in one page, which Redis is allowed to do. */
	public boolean ignoreCount;
	public boolean closed;

	private static ByteBuffer k(String key) {
		return ByteBuffer.wrap(key.getBytes(StandardCharsets.UTF_8));
	}

	private TreeMap<ByteBuffer, Stored> db(int db) {
		return dbs.computeIfAbsent(db, d -> new TreeMap<>(Comparator.comparing(ByteBuffer::array, Values::compare)));
	}

	public FakeSession string(int db, String key, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		return put(db, key, new StringValue(bytes, bytes.length), -1);
	}

	public FakeSession put(int db, String key, RedisValue value, long ttl) {
		db(db).put(k(key), new Stored(value, ttl));
		return this;
	}

	public FakeSession putBytes(int db, byte[] key, RedisValue value) {
		db(db).put(ByteBuffer.wrap(key), new Stored(value, -1));
		return this;
	}

	public RedisValue get(int db, String key) {
		Stored stored = db(db).get(k(key));
		return stored == null ? null : stored.value();
	}

	public long ttl(int db, String key) {
		Stored stored = db(db).get(k(key));
		return stored == null ? -2 : stored.ttl();
	}

	public boolean exists(int db, String key) {
		return db(db).containsKey(k(key));
	}

	public int size(int db) {
		return db(db).size();
	}

	public static HashValue hash(String... pairs) {
		var fields = new ArrayList<Field>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			fields.add(new Field(b(pairs[i]), b(pairs[i + 1])));
		}
		return new HashValue(fields, fields.size());
	}

	public static ListValue list(String... items) {
		var list = new ArrayList<byte[]>();
		for (String item : items) {
			list.add(b(item));
		}
		return new ListValue(list, list.size());
	}

	public static StreamValue stream(String... idsAndPairs) {
		var entries = new ArrayList<Entry>();
		for (String entry : idsAndPairs) {
			String[] parts = entry.split(" ");
			var fields = new ArrayList<Field>();
			for (int i = 1; i + 1 < parts.length; i += 2) {
				fields.add(new Field(b(parts[i]), b(parts[i + 1])));
			}
			entries.add(new Entry(parts[0], fields));
		}
		return new StreamValue(entries, entries.size());
	}

	public static byte[] b(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	public ServerInfo ping() {
		return new ServerInfo("7.4.0", mode);
	}

	@Override
	public int databaseCount() {
		return databases;
	}

	@Override
	public long[] keyCounts(int count) {
		long[] counts = new long[count];
		dbs.forEach((db, keys) -> {
			if (db < count) {
				counts[db] = keys.size();
			}
		});
		return counts;
	}

	@Override
	public synchronized ScanPage scan(int db, byte[] cursor, byte[] match, int count) {
		scans++;
		// The cursor is the last key handed out, so like Redis's own, it returns every key
		// that exists for the whole scan even when keys are deleted between pages.
		String text = new String(cursor, StandardCharsets.US_ASCII);
		var keys = text.equals("0") ? db(db).navigableKeySet()
				: db(db).navigableKeySet().tailSet(ByteBuffer.wrap(HexFormat.of().parseHex(text.substring(1))), false);
		byte[] prefix = match == null ? null : unescapePrefix(match);
		var page = new ArrayList<byte[]>();
		byte[] last = null;
		int seen = 0;
		for (ByteBuffer buffer : keys) {
			if (seen++ == count && !ignoreCount) {
				break;
			}
			byte[] key = buffer.array();
			last = key;
			if (prefix == null || (key.length >= prefix.length
					&& java.util.Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length))) {
				page.add(key);
			}
		}
		boolean more = last != null && db(db).higherKey(ByteBuffer.wrap(last)) != null;
		String next = more ? "k" + HexFormat.of().formatHex(last) : "0";
		return new ScanPage(next.getBytes(StandardCharsets.US_ASCII), page);
	}

	/** {@code user\*:*} back to {@code user*:}: the plugin only sends an escaped prefix then {@code *}. */
	private static byte[] unescapePrefix(byte[] match) {
		if (match[match.length - 1] != '*') {
			throw new IllegalArgumentException("Only prefix patterns are supported");
		}
		var out = new ByteArrayOutputStream();
		for (int i = 0; i < match.length - 1; i++) {
			if (match[i] == '\\') {
				i++;
			} else if (match[i] == '*' || match[i] == '?' || match[i] == '[') {
				throw new IllegalArgumentException("Unescaped glob character in " + new String(match));
			}
			out.write(match[i]);
		}
		return out.toByteArray();
	}

	@Override
	public synchronized List<KeyInfo> describe(int db, List<byte[]> keys) {
		var infos = new ArrayList<KeyInfo>();
		for (byte[] key : keys) {
			Stored stored = db(db).get(ByteBuffer.wrap(key));
			if (stored == null) {
				infos.add(new KeyInfo(key, "none", KeyType.NONE, -2, -1));
			} else {
				RedisValue v = stored.value();
				infos.add(new KeyInfo(key, v.type().redisName(), v.type(), stored.ttl(), v.total()));
			}
		}
		return infos;
	}

	@Override
	public synchronized RedisValue read(int db, byte[] key, int itemCap, int byteCap) {
		Stored stored = db(db).get(ByteBuffer.wrap(key));
		if (stored == null) {
			return new RedisValue.Unreadable(KeyType.NONE, "none");
		}
		return switch (stored.value()) {
			case StringValue s -> new StringValue(java.util.Arrays.copyOf(s.bytes(), Math.min(byteCap, s.bytes().length)),
					s.total());
			case HashValue h -> new HashValue(h.fields().subList(0, Math.min(itemCap, h.fields().size())), h.total());
			case ListValue l -> new ListValue(l.items().subList(0, Math.min(itemCap, l.items().size())), l.total());
			case SetValue s -> new SetValue(s.members().subList(0, Math.min(itemCap, s.members().size())), s.total());
			case ZSetValue z -> new ZSetValue(z.members().subList(0, Math.min(itemCap, z.members().size())), z.total());
			case StreamValue s -> new StreamValue(s.entries().subList(0, Math.min(itemCap, s.entries().size())), s.total());
			default -> stored.value();
		};
	}

	@Override
	public synchronized long delete(int db, List<byte[]> keys) {
		long deleted = 0;
		for (byte[] key : keys) {
			if (db(db).remove(ByteBuffer.wrap(key)) != null) {
				deleted++;
			}
		}
		return deleted;
	}

	@Override
	public synchronized WriteResult replace(int db, byte[] key, RedisValue expected, RedisValue value, boolean force) {
		Stored stored = db(db).get(ByteBuffer.wrap(key));
		if (!force && expected != null) {
			int cap = (int) expected.read() + 1;
			if (stored == null || !Values.sameContent(read(db, key, cap, cap), expected)) {
				return WriteResult.CHANGED;
			}
		}
		db(db).put(ByteBuffer.wrap(key), new Stored(normalize(value), stored == null ? -1 : stored.ttl()));
		return WriteResult.WRITTEN;
	}

	/** What Redis would store: sets without duplicates, sorted sets ordered by score. */
	private static RedisValue normalize(RedisValue value) {
		return switch (value) {
			case SetValue s -> {
				var unique = new LinkedHashMap<ByteBuffer, byte[]>();
				s.members().forEach(m -> unique.putIfAbsent(ByteBuffer.wrap(m), m));
				var members = new ArrayList<>(unique.values());
				members.sort(Values::compare);
				yield new SetValue(members, members.size());
			}
			case ZSetValue z -> {
				var members = new ArrayList<>(z.members());
				members.sort(Comparator.comparingDouble(Scored::score));
				yield new ZSetValue(members, members.size());
			}
			default -> value;
		};
	}

	@Override
	public void close() {
		closed = true;
	}
}
