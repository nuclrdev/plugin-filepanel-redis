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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Entry;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Field;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Scored;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.SetValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StreamValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Unreadable;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import redis.clients.jedis.ClientSetInfoConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

/**
 * {@link RedisSession} over a small Jedis pool: the listing, the quick view and an edit's
 * write-back each borrow a connection, so none waits for another. Everything goes
 * through the binary API, so keys and values that are not UTF-8 survive untouched.
 */
public final class JedisSession implements RedisSession {

	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int SOCKET_TIMEOUT_MILLIS = 30_000;
	/** Elements per command when writing a large value back. */
	private static final int WRITE_CHUNK = 1_000;
	private static final int SCAN_STEP = 500;
	private static final byte[] STREAM_NEWEST = { '+' };
	private static final byte[] STREAM_OLDEST = { '-' };

	private final JedisPool pool;

	private JedisSession(JedisPool pool) {
		this.pool = pool;
	}

	public static JedisSession open(RedisConnectionProfile profile, String password) {
		String username = profile.getUsername() == null || profile.getUsername().isBlank() ? null
				: profile.getUsername().trim();
		var config = DefaultJedisClientConfig.builder()
				.user(username)
				.password(password)
				.ssl(profile.isTls())
				.connectionTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
				.socketTimeoutMillis(SOCKET_TIMEOUT_MILLIS)
				.clientName("nuclr-commander")
				// CLIENT SETINFO is unknown before Redis 7.2 and often denied by ACLs.
				.clientSetInfoConfig(ClientSetInfoConfig.DISABLED)
				.build();
		var poolConfig = new GenericObjectPoolConfig<Jedis>();
		poolConfig.setMaxTotal(8);
		poolConfig.setMaxIdle(4);
		poolConfig.setMinIdle(0);
		poolConfig.setTestWhileIdle(true);
		poolConfig.setJmxEnabled(false);
		var address = new HostAndPort(profile.getHost().trim(), profile.getEffectivePort());
		return new JedisSession(new JedisPool(poolConfig, address, config));
	}

	@FunctionalInterface
	private interface Call<T> {
		T run(Jedis jedis);
	}

	/** Runs {@code call} on a pooled connection switched to {@code db}; the pool switches it back. */
	private <T> T on(int db, Call<T> call) {
		try (Jedis jedis = pool.getResource()) {
			if (jedis.getDB() != db) {
				jedis.select(db);
			}
			return call.run(jedis);
		}
	}

	@Override
	public ServerInfo ping() {
		return on(0, jedis -> {
			jedis.ping();
			try {
				return parseServerInfo(jedis.info("server"));
			} catch (JedisDataException e) {
				// INFO denied by an ACL: the server answered PING, which is what matters.
				return new ServerInfo("", "");
			}
		});
	}

	static ServerInfo parseServerInfo(String info) {
		String version = "";
		String mode = "";
		for (String line : (info == null ? "" : info).split("\r?\n")) {
			if (line.startsWith("redis_version:")) {
				version = line.substring("redis_version:".length()).trim();
			} else if (line.startsWith("redis_mode:")) {
				mode = line.substring("redis_mode:".length()).trim();
			}
		}
		return new ServerInfo(version, mode);
	}

	@Override
	public int databaseCount() {
		return on(0, jedis -> {
			try {
				String value = jedis.configGet("databases").get("databases");
				int count = value == null ? 0 : Integer.parseInt(value.trim());
				return count > 0 ? count : 16;
			} catch (JedisDataException | NumberFormatException e) {
				// CONFIG is often disabled on managed servers; 16 is the default.
				return 16;
			}
		});
	}

	@Override
	public long[] keyCounts(int databases) {
		return on(0, jedis -> {
			try {
				return parseKeyspace(jedis.info("keyspace"), databases);
			} catch (JedisDataException e) {
				return new long[databases];
			}
		});
	}

	/** {@code db3:keys=12,expires=0,avg_ttl=0} lines into key counts per database. */
	static long[] parseKeyspace(String info, int databases) {
		long[] counts = new long[Math.max(0, databases)];
		if (info == null) {
			return counts;
		}
		for (String line : info.split("\r?\n")) {
			int colon = line.indexOf(':');
			int keys = line.indexOf("keys=");
			if (!line.startsWith("db") || colon < 0 || keys < 0) {
				continue;
			}
			try {
				int db = Integer.parseInt(line.substring(2, colon));
				int end = line.indexOf(',', keys);
				long count = Long.parseLong(line.substring(keys + 5, end < 0 ? line.length() : end).trim());
				if (db >= 0 && db < counts.length) {
					counts[db] = count;
				}
			} catch (NumberFormatException e) {
				// not a keyspace line after all
			}
		}
		return counts;
	}

	@Override
	public ScanPage scan(int db, byte[] cursor, byte[] match, int count) {
		return on(db, jedis -> {
			ScanParams params = new ScanParams().count(count);
			if (match != null) {
				params.match(match);
			}
			ScanResult<byte[]> result = jedis.scan(cursor, params);
			return new ScanPage(result.getCursorAsBytes(), result.getResult());
		});
	}

	@Override
	public List<KeyInfo> describe(int db, List<byte[]> keys) {
		if (keys.isEmpty()) {
			return List.of();
		}
		return on(db, jedis -> {
			var types = new ArrayList<Response<String>>(keys.size());
			var ttls = new ArrayList<Response<Long>>(keys.size());
			try (Pipeline pipeline = jedis.pipelined()) {
				for (byte[] key : keys) {
					types.add(pipeline.type(key));
					ttls.add(pipeline.pttl(key));
				}
				pipeline.sync();
			}
			var lengths = new ArrayList<Response<Long>>(keys.size());
			try (Pipeline pipeline = jedis.pipelined()) {
				for (int i = 0; i < keys.size(); i++) {
					lengths.add(length(pipeline, KeyType.of(types.get(i).get()), keys.get(i)));
				}
				pipeline.sync();
			}
			var infos = new ArrayList<KeyInfo>(keys.size());
			for (int i = 0; i < keys.size(); i++) {
				String typeName = types.get(i).get();
				Response<Long> length = lengths.get(i);
				infos.add(new KeyInfo(keys.get(i), typeName, KeyType.of(typeName), ttls.get(i).get(),
						length == null ? -1 : length.get()));
			}
			return infos;
		});
	}

	private static Response<Long> length(Pipeline pipeline, KeyType type, byte[] key) {
		return switch (type) {
			case STRING -> pipeline.strlen(key);
			case HASH -> pipeline.hlen(key);
			case LIST -> pipeline.llen(key);
			case SET -> pipeline.scard(key);
			case ZSET -> pipeline.zcard(key);
			case STREAM -> pipeline.xlen(key);
			default -> null;
		};
	}

	@Override
	public RedisValue read(int db, byte[] key, int itemCap, int byteCap) {
		return on(db, jedis -> read(jedis, key, itemCap, byteCap));
	}

	private static RedisValue read(Jedis jedis, byte[] key, int itemCap, int byteCap) {
		String typeName = jedis.type(key);
		KeyType type = KeyType.of(typeName);
		int items = Math.max(1, itemCap);
		return switch (type) {
			case STRING -> readString(jedis, key, Math.max(1, byteCap));
			case HASH -> readHash(jedis, key, items);
			case LIST -> new ListValue(jedis.lrange(key, 0, items - 1), jedis.llen(key));
			case SET -> readSet(jedis, key, items);
			case ZSET -> {
				var members = new ArrayList<Scored>();
				for (Tuple tuple : jedis.zrangeWithScores(key, 0, items - 1)) {
					members.add(new Scored(tuple.getBinaryElement(), tuple.getScore()));
				}
				yield new ZSetValue(members, Math.max(jedis.zcard(key), members.size()));
			}
			case STREAM -> readStream(jedis, key, items);
			default -> new Unreadable(type, typeName);
		};
	}

	private static StringValue readString(Jedis jedis, byte[] key, int cap) {
		long total = jedis.strlen(key);
		byte[] bytes = total <= cap ? jedis.get(key) : jedis.getrange(key, 0, cap - 1);
		bytes = bytes == null ? new byte[0] : bytes;
		return new StringValue(bytes, Math.max(total, bytes.length));
	}

	private static HashValue readHash(Jedis jedis, byte[] key, int cap) {
		long total = jedis.hlen(key);
		var fields = new LinkedHashMap<ByteBuffer, byte[]>();
		if (total <= cap) {
			for (Map.Entry<byte[], byte[]> e : jedis.hgetAll(key).entrySet()) {
				fields.put(ByteBuffer.wrap(e.getKey()), e.getValue());
			}
		} else {
			// HSCAN may return a field twice; the map keeps one.
			byte[] cursor = START;
			do {
				ScanResult<Map.Entry<byte[], byte[]>> page = jedis.hscan(key, cursor, new ScanParams().count(SCAN_STEP));
				for (Map.Entry<byte[], byte[]> e : page.getResult()) {
					if (fields.size() < cap) {
						fields.put(ByteBuffer.wrap(e.getKey()), e.getValue());
					}
				}
				cursor = page.getCursorAsBytes();
			} while (fields.size() < cap && !page(cursor).finished());
		}
		var list = new ArrayList<Field>(fields.size());
		fields.forEach((name, value) -> list.add(new Field(name.array(), value)));
		list.sort((a, b) -> Values.compare(a.name(), b.name()));
		return new HashValue(list, Math.max(total, list.size()));
	}

	private static SetValue readSet(Jedis jedis, byte[] key, int cap) {
		long total = jedis.scard(key);
		var members = new LinkedHashMap<ByteBuffer, byte[]>();
		if (total <= cap) {
			for (byte[] member : jedis.smembers(key)) {
				members.put(ByteBuffer.wrap(member), member);
			}
		} else {
			byte[] cursor = START;
			do {
				ScanResult<byte[]> page = jedis.sscan(key, cursor, new ScanParams().count(SCAN_STEP));
				for (byte[] member : page.getResult()) {
					if (members.size() < cap) {
						members.put(ByteBuffer.wrap(member), member);
					}
				}
				cursor = page.getCursorAsBytes();
			} while (members.size() < cap && !page(cursor).finished());
		}
		var list = new ArrayList<>(members.values());
		list.sort(Values::compare);
		return new SetValue(list, Math.max(total, list.size()));
	}

	private static ScanPage page(byte[] cursor) {
		return new ScanPage(cursor, List.of());
	}

	private static StreamValue readStream(Jedis jedis, byte[] key, int cap) {
		long total = jedis.xlen(key);
		var entries = new ArrayList<Entry>();
		for (Object raw : jedis.xrevrange(key, STREAM_NEWEST, STREAM_OLDEST, cap)) {
			if (!(raw instanceof List<?> entry) || entry.size() < 2 || !(entry.get(0) instanceof byte[] id)) {
				continue;
			}
			var fields = new ArrayList<Field>();
			if (entry.get(1) instanceof List<?> flat) {
				for (int i = 0; i + 1 < flat.size(); i += 2) {
					if (flat.get(i) instanceof byte[] name && flat.get(i + 1) instanceof byte[] value) {
						fields.add(new Field(name, value));
					}
				}
			}
			entries.add(new Entry(new String(id, StandardCharsets.US_ASCII), fields));
		}
		return new StreamValue(entries, Math.max(total, entries.size()));
	}

	@Override
	public long delete(int db, List<byte[]> keys) {
		if (keys.isEmpty()) {
			return 0;
		}
		return on(db, jedis -> {
			byte[][] array = keys.toArray(byte[][]::new);
			try {
				// Frees the memory in the background: deleting a big hash does not stall the server.
				return jedis.unlink(array);
			} catch (JedisDataException e) {
				return jedis.del(array); // before Redis 4
			}
		});
	}

	@Override
	public WriteResult replace(int db, byte[] key, RedisValue expected, RedisValue value, boolean force) {
		if (!value.type().editable()) {
			throw new IllegalArgumentException("Cannot write a " + value.type().redisName() + " value");
		}
		return on(db, jedis -> {
			// A forced write is the user's answer to a conflict: watching again would let a
			// further change abort it, and the user would believe it saved. MULTI alone
			// still makes delete, rewrite and TTL one step.
			boolean guarded = !force && expected != null;
			if (guarded) {
				jedis.watch(key);
			}
			try {
				if (guarded) {
					// One past what was read: enough to see the value grew since.
					int cap = (int) Math.min(Integer.MAX_VALUE - 1L, expected.read()) + 1;
					if (!Values.sameContent(read(jedis, key, cap, cap), expected)) {
						jedis.unwatch();
						return WriteResult.CHANGED;
					}
				}
				long ttl = jedis.pttl(key);
				Transaction transaction = jedis.multi();
				transaction.del(key);
				write(transaction, key, value);
				if (ttl > 0) {
					transaction.pexpire(key, ttl);
				}
				// A write to the key since WATCH aborts the transaction: EXEC answers null.
				List<Object> results = transaction.exec();
				return results == null ? WriteResult.CHANGED : WriteResult.WRITTEN;
			} catch (RuntimeException e) {
				try {
					if (guarded) {
						jedis.unwatch();
					}
				} catch (RuntimeException ignored) {
					// the connection is already broken; the pool discards it
				}
				throw e;
			}
		});
	}

	/** The commands that rebuild {@code value} under {@code key}, which the transaction has just deleted. */
	private static void write(Transaction transaction, byte[] key, RedisValue value) {
		switch (value) {
			case StringValue s -> transaction.set(key, s.bytes());
			case HashValue h -> {
				var map = new HashMap<byte[], byte[]>();
				for (Field field : h.fields()) {
					map.put(field.name(), field.value());
					if (map.size() == WRITE_CHUNK) {
						transaction.hset(key, map);
						map = new HashMap<>();
					}
				}
				if (!map.isEmpty()) {
					transaction.hset(key, map);
				}
			}
			case ListValue l -> {
				for (List<byte[]> chunk : chunks(l.items())) {
					transaction.rpush(key, chunk.toArray(byte[][]::new));
				}
			}
			case SetValue s -> {
				for (List<byte[]> chunk : chunks(s.members())) {
					transaction.sadd(key, chunk.toArray(byte[][]::new));
				}
			}
			case ZSetValue z -> {
				var map = new HashMap<byte[], Double>();
				for (Scored member : z.members()) {
					map.put(member.member(), member.score());
					if (map.size() == WRITE_CHUNK) {
						transaction.zadd(key, map);
						map = new HashMap<>();
					}
				}
				if (!map.isEmpty()) {
					transaction.zadd(key, map);
				}
			}
			default -> throw new IllegalArgumentException("Cannot write " + value.type());
		}
	}

	private static List<List<byte[]>> chunks(List<byte[]> items) {
		var chunks = new ArrayList<List<byte[]>>();
		for (int i = 0; i < items.size(); i += WRITE_CHUNK) {
			chunks.add(items.subList(i, Math.min(items.size(), i + WRITE_CHUNK)));
		}
		return chunks;
	}

	@Override
	public void close() {
		pool.close();
	}
}
