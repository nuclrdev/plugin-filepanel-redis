package dev.nuclr.plugin.core.panel.redis.client;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.client.RedisSession.WriteResult;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StreamValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.keys.KeyTree;
import dev.nuclr.plugin.core.panel.redis.value.ValueCodec;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;

/**
 * {@link JedisSession} against a real server. Skipped unless {@code -Dredis.live=host:port}
 * names a throwaway one: it flushes database 15.
 *
 * <pre>docker run -d --rm -p 6390:6379 --name nuclr-redis-test redis:7
 * mvn test -Dredis.live=localhost:6390</pre>
 */
class RedisLiveTest {

	private static final int DB = 15;

	private Jedis jedis;
	private JedisSession session;

	@BeforeEach
	void connect() {
		String target = System.getProperty("redis.live");
		assumeTrue(target != null && !target.isBlank(), "set -Dredis.live=host:port to run");
		String host = target.substring(0, target.lastIndexOf(':'));
		int port = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
		jedis = new Jedis(host, port);
		jedis.select(DB);
		jedis.flushDB();
		session = JedisSession.open(RedisConnectionProfile.builder().host(host).port(port).build(), null);
	}

	@AfterEach
	void close() {
		if (jedis != null) {
			jedis.flushDB();
			jedis.close();
		}
		if (session != null) {
			session.close();
		}
	}

	@Test
	void browsesReadsEditsAndDeletes() {
		jedis.set("user:1:name", "Ada");
		jedis.hset("user:1:profile", Map.of("city", "London", "lang", "en"));
		jedis.pexpire("user:1:profile", 120_000);
		jedis.rpush("queue:jobs", "a", "b", "c");
		jedis.sadd("tags", "x", "y");
		jedis.zadd("board", Map.of("ada", 3.0, "bob", 1.0));
		jedis.xadd("events", StreamEntryID.NEW_ENTRY, Map.of("kind", "signup"));
		jedis.xadd("events", StreamEntryID.NEW_ENTRY, Map.of("kind", "login"));
		jedis.set(new byte[] { 'b', ':', (byte) 0xff }, new byte[] { 0, 1, 2 });
		for (int i = 0; i < 3_000; i++) {
			jedis.set("bulk:" + i, "v");
		}

		RedisSession.ServerInfo info = session.ping();
		assertTrue(!info.version().isEmpty());
		assertTrue(session.databaseCount() >= 16);
		assertEquals(3_007, session.keyCounts(16)[DB]);

		KeyTree.Level top = KeyTree.scan(session, DB, new byte[0], b(":"), 100_000, null, null);
		var folders = new ArrayList<String>();
		top.folders().forEach(f -> folders.add(new String(f.segment(), StandardCharsets.UTF_8) + "=" + f.keys()));
		assertEquals(List.of("b=1", "bulk=3000", "queue=1", "user=2"), folders);
		assertEquals(3, top.keys().size());

		List<KeyInfo> infos = session.describe(DB, List.of(b("user:1:profile"), b("events"), b("tags"), b("gone")));
		assertEquals(KeyType.HASH, infos.get(0).type());
		assertEquals(2, infos.get(0).length());
		assertTrue(infos.get(0).ttlMillis() > 100_000);
		assertEquals(KeyType.STREAM, infos.get(1).type());
		assertEquals(2, infos.get(1).length());
		assertEquals(KeyType.NONE, infos.get(3).type());

		var stream = (StreamValue) session.read(DB, b("events"), 10, 10);
		assertEquals("login", new String(stream.entries().get(0).fields().get(0).value(), StandardCharsets.UTF_8));
		var board = (ZSetValue) session.read(DB, b("board"), 10, 10);
		assertEquals("bob", new String(board.members().get(0).member(), StandardCharsets.UTF_8));
		var partial = (StringValue) session.read(DB, b("user:1:name"), 10, 2);
		assertTrue(partial.truncated());

		// Edit a hash through the codec, keeping the TTL.
		var profile = (HashValue) session.read(DB, b("user:1:profile"), 1000, 1000);
		RedisValue edited = ValueCodec.fromEditText(KeyType.HASH, "{\"city\": \"Paris\"}");
		assertEquals(WriteResult.WRITTEN, session.replace(DB, b("user:1:profile"), profile, edited, false));
		assertEquals(Map.of("city", "Paris"), jedis.hgetAll("user:1:profile"));
		assertTrue(jedis.pttl("user:1:profile") > 100_000);

		// A change in between is detected, unless forced.
		jedis.hset("user:1:profile", "city", "Rome");
		RedisValue mine = ValueCodec.fromEditText(KeyType.HASH, "{\"city\": \"Oslo\"}");
		assertEquals(WriteResult.CHANGED, session.replace(DB, b("user:1:profile"), profile, mine, false));
		assertEquals("Rome", jedis.hget("user:1:profile", "city"));
		assertEquals(WriteResult.WRITTEN, session.replace(DB, b("user:1:profile"), profile, mine, true));
		assertEquals("Oslo", jedis.hget("user:1:profile", "city"));

		// Delete a folder's worth of keys and a binary key.
		assertEquals(3_000, KeyTree.forEachPage(session, DB, b("bulk:"), null, 1_000, page -> session.delete(DB, page)));
		assertEquals(1, session.delete(DB, List.of(new byte[] { 'b', ':', (byte) 0xff })));
		assertEquals(6, jedis.dbSize());
	}
}
