package dev.nuclr.plugin.core.panel.redis.quickview;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.support.FakeSession;

class ValuePanelTest {

	@Test
	void jsonStringsAreLaidOut() {
		var rendered = ValuePanel.render(new StringValue(b("{\"a\":1}"), 7));
		assertEquals("{\n  \"a\" : 1\n}\n", rendered.text());
		assertTrue(rendered.status().contains("JSON"));
	}

	@Test
	void binaryStringsAreAHexDump() {
		var rendered = ValuePanel.render(new StringValue(new byte[] { 0, 1, 'A' }, 3));
		assertTrue(rendered.text().startsWith("00000000  00 01 41"), rendered.text());
		assertTrue(rendered.status().contains("hex"));
	}

	@Test
	void aCutStringLosesItsPartialCharacter() {
		byte[] euro = "a€".getBytes(StandardCharsets.UTF_8);
		byte[] cut = java.util.Arrays.copyOf(euro, 3);
		assertArrayEquals(b("a"), ValuePanel.wholeCharacters(cut));
		var rendered = ValuePanel.render(new StringValue(cut, 100));
		assertEquals("a", rendered.text());
		assertTrue(rendered.status().startsWith("Showing the first"), rendered.status());
	}

	@Test
	void hashesAndStreamsAreTables() {
		var hash = ValuePanel.render(FakeSession.hash("city", "London"));
		assertEquals(List.of("Field", "Value"), hash.columns());
		assertEquals("London", hash.rows().get(0)[1]);

		var stream = ValuePanel.render(FakeSession.stream("1700000000000-0 kind login"));
		assertEquals(List.of("ID", "Time", "Fields"), stream.columns());
		assertEquals("kind=login", stream.rows().get(0)[2]);
		assertTrue(stream.rows().get(0)[1].toString().startsWith("2023-11-1"));
	}

	@Test
	void aTruncatedListSaysSo() {
		var list = new dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue(List.of(b("a")), 5_000);
		assertEquals("Showing the first 1 of 5,000", ValuePanel.render(list).status());
	}
}
