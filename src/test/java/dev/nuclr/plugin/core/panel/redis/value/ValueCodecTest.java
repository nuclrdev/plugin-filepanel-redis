package dev.nuclr.plugin.core.panel.redis.value;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Scored;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.SetValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;
import dev.nuclr.plugin.core.panel.redis.client.Values;
import dev.nuclr.plugin.core.panel.redis.support.FakeSession;

class ValueCodecTest {

	private static void roundTrips(RedisValue value) throws Exception {
		String text = ValueCodec.toEditText(value);
		RedisValue back = ValueCodec.fromEditText(value.type(), text);
		assertTrue(Values.sameContent(value, back), value.type() + " did not survive: " + text);
	}

	@Test
	void everyEditableTypeRoundTrips() throws Exception {
		byte[] text = b("{\"a\": 1}\nsecond line");
		roundTrips(new StringValue(text, text.length));
		roundTrips(FakeSession.hash("name", "Ada", "json", "{\"x\":1}", "quote", "say \"hi\""));
		roundTrips(FakeSession.list("b", "a", "b", ""));
		roundTrips(new SetValue(List.of(b("x"), b("y")), 2));
		roundTrips(new ZSetValue(List.of(new Scored(b("low"), Double.NEGATIVE_INFINITY), new Scored(b("mid"), 1.5),
				new Scored(b("top"), Double.POSITIVE_INFINITY)), 3));
	}

	@Test
	void aHashIsAJsonObject() throws Exception {
		assertEquals("{\n  \"name\" : \"Ada\"\n}\n", ValueCodec.toEditText(FakeSession.hash("name", "Ada")));
	}

	@Test
	void scalarsAndNestedJsonBecomeText() {
		var hash = (HashValue) ValueCodec.fromEditText(KeyType.HASH, "{\"n\": 42, \"ok\": true, \"o\": {\"a\": [1]}}");
		assertEquals("42", new String(hash.fields().get(0).value(), StandardCharsets.UTF_8));
		assertEquals("true", new String(hash.fields().get(1).value(), StandardCharsets.UTF_8));
		assertEquals("{\"a\":[1]}", new String(hash.fields().get(2).value(), StandardCharsets.UTF_8));
	}

	@Test
	void scoresMayBeQuoted() {
		var zset = (ZSetValue) ValueCodec.fromEditText(KeyType.ZSET, "{\"a\": \"2.5\", \"b\": \"-inf\"}");
		assertEquals(2.5, zset.members().get(0).score());
		assertEquals(Double.NEGATIVE_INFINITY, zset.members().get(1).score());
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.ZSET, "{\"a\": \"lots\"}"));
	}

	@Test
	void wrongShapesAndBrokenJsonAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.HASH, "[\"a\"]"));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.LIST, "{\"a\": 1}"));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.LIST, "[\"a\", "));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.SET, "[]"));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.LIST, "[null]"));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.fromEditText(KeyType.HASH, ""));
	}

	@Test
	void aStringIsTakenAsTyped() {
		var value = (StringValue) ValueCodec.fromEditText(KeyType.STRING, "not json {");
		assertEquals("not json {", new String(value.bytes(), StandardCharsets.UTF_8));
	}

	@Test
	void binaryTruncatedAndStreamValuesAreNotEditable() {
		assertThrows(ValueCodec.NotEditable.class,
				() -> ValueCodec.toEditText(new StringValue(new byte[] { (byte) 0xff }, 1)));
		assertThrows(ValueCodec.NotEditable.class,
				() -> ValueCodec.toEditText(new ListValue(List.of(b("a")), 2)));
		assertThrows(ValueCodec.NotEditable.class,
				() -> ValueCodec.toEditText(FakeSession.stream("1-0 a b")));
		assertThrows(ValueCodec.NotEditable.class,
				() -> ValueCodec.toEditText(new HashValue(List.of(new RedisValue.Field(b("k"), new byte[] { (byte) 0xc3 })), 1)));
	}

	@Test
	void prettyPrintsOnlyObjectsAndArrays() {
		assertEquals("{\n  \"a\" : 1\n}\n", ValueCodec.prettyJsonOrNull(" {\"a\":1} "));
		assertNull(ValueCodec.prettyJsonOrNull("42"));
		assertNull(ValueCodec.prettyJsonOrNull("{broken"));
		assertEquals(".json", ValueCodec.fileExtension(KeyType.STRING, "[1]"));
		assertEquals(".txt", ValueCodec.fileExtension(KeyType.STRING, "plain"));
		assertEquals(".json", ValueCodec.fileExtension(KeyType.HASH, "{}"));
	}

	@Test
	void aStreamAsADocument() {
		String json = new String(ValueCodec.document(FakeSession.stream("2-0 temp 21", "1-0 temp 20")), StandardCharsets.UTF_8);
		assertTrue(json.contains("\"id\" : \"2-0\""), json);
		assertTrue(json.indexOf("2-0") < json.indexOf("1-0"), json);
	}

	@Test
	void hexDumpLayout() {
		String dump = ValueCodec.hexDump(new byte[] { 'A', 0, (byte) 0xff }, 100);
		assertEquals("00000000  41 00 ff                                          |A..|\n", dump);
	}
}
