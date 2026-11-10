package dev.nuclr.plugin.core.panel.redis.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

/** The parsing around the client; talking to a server is covered by {@code RedisLiveTest}. */
class JedisSessionTest {

	@Test
	void keyspaceLinesBecomeCounts() {
		String info = "# Keyspace\r\ndb0:keys=12,expires=1,avg_ttl=0\r\ndb3:keys=7,expires=0,avg_ttl=0\r\ndb99:keys=1\r\n";
		assertArrayEquals(new long[] { 12, 0, 0, 7 }, JedisSession.parseKeyspace(info, 4));
		assertArrayEquals(new long[2], JedisSession.parseKeyspace(null, 2));
	}

	@Test
	void serverInfoGivesVersionAndMode() {
		var info = JedisSession.parseServerInfo("# Server\r\nredis_version:7.4.1\r\nredis_mode:standalone\r\n");
		assertEquals("7.4.1", info.version());
		assertEquals("standalone", info.mode());
	}

	@Test
	void errorsReadAsOneLine() {
		var refused = new JedisConnectionException("Failed to connect to any host resolved for DNS name.",
				new java.net.ConnectException("Connection refused"));
		assertEquals("Could not reach the server: ConnectException: Connection refused", ErrorText.describe(refused));
		var wrongPass = new JedisDataException("WRONGPASS invalid username-password pair or user is disabled.");
		assertTrue(ErrorText.isAuthenticationFailure(wrongPass));
		assertTrue(ErrorText.describe(wrongPass).startsWith("Authentication failed: WRONGPASS"));
	}

	@Test
	void scanPagesFinishAtCursorZero() {
		assertTrue(new RedisSession.ScanPage(new byte[] { '0' }, java.util.List.of()).finished());
		assertTrue(!new RedisSession.ScanPage(new byte[] { '1', '0' }, java.util.List.of()).finished());
	}
}
