package dev.nuclr.plugin.core.panel.redis.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.PluginIds;
import dev.nuclr.plugin.core.panel.redis.RedisFilePanelPlugin;
import dev.nuclr.plugin.core.panel.redis.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.support.FakeContext;

class RedisTransferTest {

	private final FakeContext context = new FakeContext();
	private final ConnectionProfileStore store = new ConnectionProfileStore(context.settings);
	private final RedisFilePanelPlugin plugin = new RedisFilePanelPlugin();

	RedisTransferTest() {
		plugin.preinit(context);
	}

	/** One action through the plugin's act, as Commander sends it. */
	private Map<String, Object> act(String action, Map<String, Object> input) {
		var data = new HashMap<String, Object>(input);
		data.put("transfer.protocol", 1);
		if (!action.equals(RedisTransfer.Describe)) {
			data.putIfAbsent("type", "connections");
		}
		plugin.act(null, action, List.of(), null, data, null);
		return data;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> rows() {
		return (List<Map<String, Object>>) act(RedisTransfer.Snapshot, Map.of()).get("rows");
	}

	@Test
	void describeDeclaresTheConnectionsCategoryWithEveryFieldTagged() {
		var data = act(RedisTransfer.Describe, Map.of());

		@SuppressWarnings("unchecked")
		var provider = ((List<Map<String, Object>>) data.get("transfer.providers")).getFirst();
		assertEquals("connections", provider.get("type"));
		assertEquals(1, provider.get("version"));
		assertEquals("Redis", provider.get("title"));
		var kinds = new HashMap<String, Object>();
		var suffixes = new HashMap<String, Object>();
		for (Object field : (List<?>) provider.get("fields")) {
			var map = (Map<?, ?>) field;
			String name = (String) map.get("name");
			assertTrue(name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"), name);
			kinds.put(name, map.get("kind"));
			suffixes.put(name, map.get("credentialSuffix"));
		}
		assertEquals("ROUTING", kinds.get("host"));
		assertEquals("ROUTING", kinds.get("port"));
		assertEquals("PLAIN", kinds.get("username"));
		assertEquals("PLAIN", kinds.get("tls"));
		assertEquals("SECRET", kinds.get("password"));
		assertEquals("", suffixes.get("password"));
		assertEquals(1, kinds.values().stream().filter("SECRET"::equals).count(), "one keychain key per profile");
	}

	@Test
	void aSnapshotNeverCarriesASecretEvenOneLeftInAHost() {
		store.add(RedisConnectionProfile.builder().id("a").name("cache").host("prod-cache").port(6380).auth(true)
				.username("app").tls(true).readOnly(true).build());
		store.add(RedisConnectionProfile.builder().id("b").name("legacy").host("redis://ops:hunter2@old-cache:6390")
				.build());
		context.credentials.secrets.put("a", "redis-password");

		var rows = rows();

		var cache = rows.get(0);
		assertEquals("a", cache.get("rowId"));
		assertEquals("cache", cache.get("label"));
		assertEquals(true, cache.get("hasSecrets"));
		assertEquals(Map.of("name", "cache", "host", "prod-cache", "port", "6380", "username", "app", "auth", "true",
				"tls", "true", "separator", ":", "readOnly", "true"), cache.get("fields"));

		var legacy = rows.get(1);
		assertEquals(true, legacy.get("hasSecrets"), "the password left in the host is still a secret of the row");
		var fields = (Map<?, ?>) legacy.get("fields");
		assertEquals("old-cache", fields.get("host"));
		assertEquals("6390", fields.get("port"));
		assertEquals("ops", fields.get("username"));
		assertEquals("true", fields.get("auth"));
		assertFalse(rows.toString().contains("hunter2"));
		assertFalse(rows.toString().contains("redis-password"));
	}

	@Test
	void readSecretsGivesTheStoredPasswordOrOneLeftInTheHost() {
		store.add(RedisConnectionProfile.builder().id("a").name("cache").host("h").auth(true).build());
		store.add(RedisConnectionProfile.builder().id("b").name("legacy").host("rediss://:s3cret@h").build());
		store.add(RedisConnectionProfile.builder().id("c").name("open").host("h").build());
		context.credentials.secrets.put("a", "redis-password");
		act(RedisTransfer.Snapshot, Map.of());

		assertEquals(Map.of("password", "redis-password"),
				act(RedisTransfer.ReadSecrets, Map.of("rowId", "a")).get("secrets"));
		assertEquals(Map.of("password", "s3cret"), act(RedisTransfer.ReadSecrets, Map.of("rowId", "b")).get("secrets"));
		assertEquals(Map.of(), act(RedisTransfer.ReadSecrets, Map.of("rowId", "c")).get("secrets"));
		assertEquals(Map.of(), act(RedisTransfer.ReadSecrets, Map.of("rowId", "unknown")).get("secrets"));
	}

	@Test
	void anEntryWithoutAnIdIsStillExportable() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "[{\"name\":\"legacy\",\"host\":\"h\"}]");

		var row = rows().getFirst();

		assertEquals("#1", row.get("rowId"));
		assertEquals("legacy", row.get("label"));
		assertEquals(false, row.get("hasSecrets"));
		assertEquals(Map.of(), act(RedisTransfer.ReadSecrets, Map.of("rowId", "#1")).get("secrets"));
	}

	@Test
	void findEmbeddedSecretsNamesAHostThatStillCarriesOne() {
		assertEquals(List.of("host"), act(RedisTransfer.FindEmbeddedSecrets,
				Map.of("fields", Map.of("host", "redis://u:p@h:6379"))).get("fieldNames"));
		assertEquals(List.of("host"), act(RedisTransfer.FindEmbeddedSecrets,
				Map.of("fields", Map.of("host", "secret@h"))).get("fieldNames"));
		assertEquals(List.of(), act(RedisTransfer.FindEmbeddedSecrets,
				Map.of("fields", Map.of("host", "redis://h:6380"))).get("fieldNames"));
		assertEquals(List.of(), act(RedisTransfer.FindEmbeddedSecrets,
				Map.of("fields", Map.of("host", "cache.internal", "name", "x"))).get("fieldNames"));
	}

	@Test
	void findEmbeddedSecretsHasNoSideEffects() {
		var fields = new HashMap<String, String>(Map.of("host", "redis://u:p@h"));
		act(RedisTransfer.FindEmbeddedSecrets, Map.of("fields", fields));
		assertEquals(Map.of("host", "redis://u:p@h"), fields);
		assertTrue(store.loadAll().isEmpty());
	}

	@Test
	void applyAppendsUnderTheGivenIdsKeepsTheRestAndHandsBackWhatItStripped() {
		store.add(RedisConnectionProfile.builder().id("existing").name("local").host("localhost").build());

		var data = act(RedisTransfer.Apply, Map.of("items", List.of(
				item("new-1", Map.of("name", "prod", "host", "rediss://ops:crafted@cache.example:6380",
						"separator", "/", "readOnly", "true")),
				item("new-2", Map.of("host", "cache-2", "port", "not-a-port", "auth", "true", "username", "app")),
				item("new-3", Map.of("name", "bare", "host", "only-password@cache-3")))));

		var all = store.loadAll();
		assertEquals(List.of("existing", "new-1", "new-2", "new-3"),
				all.stream().map(RedisConnectionProfile::getId).toList());

		var prod = store.findById("new-1");
		assertEquals("cache.example", prod.getHost(), "a crafted file cannot put a password into the host");
		assertEquals(6380, prod.getPort());
		assertTrue(prod.isTls());
		assertTrue(prod.isAuth());
		assertEquals("ops", prod.getUsername());
		assertEquals("/", prod.getSeparator());
		assertTrue(prod.isReadOnly());

		var second = store.findById("new-2");
		assertEquals("cache-2", second.getName(), "a nameless entry is named after its host");
		assertEquals(RedisConnectionProfile.DEFAULT_PORT, second.getPort());
		assertTrue(second.isAuth());
		assertEquals("app", second.getUsername());
		assertFalse(second.isTls());
		assertEquals(":", second.getSeparator());

		var bare = store.findById("new-3");
		assertEquals("cache-3", bare.getHost());
		assertNull(bare.getUsername());
		assertTrue(bare.isAuth());

		assertEquals(Map.of("new-1", Map.of("password", "crafted"), "new-3", Map.of("password", "only-password")),
				data.get("recoveredSecrets"));
		String saved = context.settings.get(PluginIds.PLUGIN_ID, "profiles.json");
		assertFalse(saved.contains("crafted"));
		assertFalse(saved.contains("only-password"));
		assertTrue(context.credentials.secrets.isEmpty(), "apply never writes the keychain");
	}

	@Test
	void applyWithNothingToStripAnswersNoRecoveredSecrets() {
		var data = act(RedisTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x", "host", "h")))));
		assertFalse(data.containsKey("recoveredSecrets"));
		assertEquals("h", store.findById("n").getHost());
	}

	@Test
	void applyWithoutAnIdThrowsAndChangesNothing() {
		store.add(RedisConnectionProfile.builder().id("existing").name("local").host("h").build());
		String before = context.settings.get(PluginIds.PLUGIN_ID, "profiles.json");

		assertThrows(IllegalArgumentException.class, () -> act(RedisTransfer.Apply,
				Map.of("items", List.of(item("ok", Map.of("name", "x")), Map.of("fields", Map.of("name", "y"))))));
		assertEquals(before, context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
	}

	@Test
	void applyOverAnUnreadableStoreThrowsAndChangesNothing() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "{not json");

		assertThrows(IllegalStateException.class,
				() -> act(RedisTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"))))));
		assertEquals("{not json", context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
	}

	@Test
	void aFailedWriteThrowsInsteadOfBeingSwallowed() {
		store.add(RedisConnectionProfile.builder().id("existing").name("local").host("h").build());
		context.settings.failWrites = true;

		assertThrows(IllegalStateException.class,
				() -> act(RedisTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"))))));
		assertThrows(IllegalStateException.class,
				() -> act(RedisTransfer.Remove, Map.of("ids", List.of("existing"))));
		context.settings.failWrites = false;
		assertEquals(List.of("existing"), store.loadAll().stream().map(RedisConnectionProfile::getId).toList());
	}

	@Test
	void removeDeletesOnlyTheGivenIds() {
		store.add(RedisConnectionProfile.builder().id("keep").name("a").host("h").build());
		store.add(RedisConnectionProfile.builder().id("drop").name("b").host("h").build());

		act(RedisTransfer.Remove, Map.of("ids", List.of("drop", "unknown")));

		assertEquals(List.of("keep"), store.loadAll().stream().map(RedisConnectionProfile::getId).toList());
	}

	@Test
	void removeOverAnUnreadableStoreThrowsAndChangesNothing() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "{not json");

		assertThrows(IllegalStateException.class, () -> act(RedisTransfer.Remove, Map.of("ids", List.of("x"))));
		assertEquals("{not json", context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
	}

	@Test
	void anotherProtocolVersionOrCategoryIsNotAnswered() {
		var data = new HashMap<String, Object>();
		data.put("transfer.protocol", 2);
		plugin.act(null, RedisTransfer.Describe, List.of(), null, data, null);
		assertEquals(Map.of("transfer.protocol", 2), data);

		var other = act(RedisTransfer.Snapshot, Map.of("type", "profiles"));
		assertFalse(other.containsKey("rows"));

		var noType = new HashMap<String, Object>();
		noType.put("transfer.protocol", 1);
		plugin.act(null, RedisTransfer.Apply, List.of(), null, noType, null);
		assertEquals(Map.of("transfer.protocol", 1), noType);
	}

	@Test
	void everyExportedFieldIsDeclared() {
		var exported = new ArrayList<>(RedisTransfer.fields(RedisConnectionProfile.builder().id("i").name("n")
				.host("h").port(1).username("u").auth(true).tls(true).separator("/").readOnly(true).build()).keySet());
		assertTrue(RedisTransfer.fieldNames().containsAll(exported), exported.toString());
		assertFalse(exported.contains("password"));
		assertFalse(exported.contains("id"));
	}

	private static Map<String, Object> item(String id, Map<String, String> fields) {
		return Map.of("newId", id, "fields", fields, "keyFiles", Map.of());
	}
}
