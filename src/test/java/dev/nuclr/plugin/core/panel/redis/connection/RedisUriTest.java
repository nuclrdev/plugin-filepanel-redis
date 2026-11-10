package dev.nuclr.plugin.core.panel.redis.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RedisUriTest {

	@Test
	void splitsAFullUrl() {
		assertEquals(new RedisUri("cache.example.com", 6380, "app", "p@ss:w/rd", true),
				RedisUri.parse("rediss://app:p%40ss%3Aw%2Frd@cache.example.com:6380/2?timeout=5"));
	}

	@Test
	void theOldFormIsAPasswordAlone() {
		assertEquals(new RedisUri("h", 6379, null, "secret", false), RedisUri.parse("redis://secret@h"));
		assertEquals(new RedisUri("h", 6379, null, "secret", false), RedisUri.parse("redis://:secret@h"));
	}

	@Test
	void ipv6AndDefaults() {
		assertEquals(new RedisUri("::1", 7000, null, null, false), RedisUri.parse("redis://[::1]:7000"));
		assertEquals(new RedisUri("localhost", 6379, null, null, false), RedisUri.parse("redis://"));
	}

	@Test
	void plainHostsAreNotUrls() {
		assertNull(RedisUri.parse("localhost"));
		assertNull(RedisUri.parse(null));
	}
}
