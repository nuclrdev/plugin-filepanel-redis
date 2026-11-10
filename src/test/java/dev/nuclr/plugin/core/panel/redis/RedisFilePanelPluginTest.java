package dev.nuclr.plugin.core.panel.redis;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.EntrySink;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.redis.connection.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;
import dev.nuclr.plugin.core.panel.redis.resource.ResourceKind;
import dev.nuclr.plugin.core.panel.redis.support.FakeContext;
import dev.nuclr.plugin.core.panel.redis.support.FakeSession;
import redis.clients.jedis.exceptions.JedisDataException;

class RedisFilePanelPluginTest {

	private FakeSession session;
	private FakeContext context;
	private RedisFilePanelPlugin plugin;
	private final List<String> passwords = new ArrayList<>();

	@BeforeEach
	void setUp() {
		session = new FakeSession()
				.string(0, "user:1:name", "Ada")
				.put(0, "user:1:profile", FakeSession.hash("city", "London", "lang", "en"), 60_000)
				.string(0, "user:2:name", "Grace")
				.put(0, "queue:jobs", FakeSession.list("a", "b"), -1)
				.put(0, "events", FakeSession.stream("2-0 kind login", "1-0 kind signup"), -1)
				.string(0, "counter", "41")
				.string(3, "other", "x");
		context = new FakeContext();
		plugin = newPlugin();
		plugin.preinit(context);
		plugin.init();
	}

	/** When set, the fake server accepts only this password and refuses others as Redis does. */
	private String serverPassword;
	private final List<String> prompts = new ArrayList<>();
	private final List<String> typed = new ArrayList<>();
	/** Whether the typed password's Remember box is ticked. */
	private boolean rememberTyped = true;

	private RedisFilePanelPlugin newPlugin() {
		var created = new RedisFilePanelPlugin((profile, password) -> {
			passwords.add(password);
			if (serverPassword != null && !serverPassword.equals(password)) {
				throw new JedisDataException(password == null
						? "NOAUTH Authentication required." : "WRONGPASS invalid username-password pair");
			}
			return session;
		});
		created.secretPrompter = message -> {
			prompts.add(message);
			return typed.isEmpty() ? null : new RedisFilePanelPlugin.SecretPrompt(typed.remove(0), rememberTyped);
		};
		return created;
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
	}

	private RedisConnectionProfile saveProfile(boolean readOnly) {
		return new ConnectionProfileStore(context.settings).add(RedisConnectionProfile.builder()
				.name("Local").host("localhost").readOnly(readOnly).build());
	}

	private List<NuclrResource> open(NuclrResource resource) {
		return plugin.openResource(resource, new AtomicBoolean()).getEntries();
	}

	private static RedisResource named(List<NuclrResource> entries, String name) {
		return (RedisResource) entries.stream().filter(e -> e.getName().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " not in " + entries.stream().map(NuclrResource::getName).toList()));
	}

	private static List<String> names(List<NuclrResource> entries) {
		return entries.stream().map(NuclrResource::getName).toList();
	}

	private static String text(NuclrResource resource) throws Exception {
		try (InputStream in = resource.openInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private RedisResource db0(RedisConnectionProfile profile) {
		List<NuclrResource> dbs = open(named(open(RedisResource.explorerRoot()), profile.getName()));
		return named(dbs, "db0");
	}

	@Test
	void walksFromTheRootToAKey() throws Exception {
		RedisConnectionProfile profile = saveProfile(false);
		List<NuclrResource> root = open(RedisResource.explorerRoot());
		assertEquals(List.of("Local"), names(root));
		assertEquals("localhost:6379", root.get(0).getMetadata().get("Address"));

		List<NuclrResource> dbs = open(root.get(0));
		assertEquals("..", dbs.get(0).getName());
		assertEquals(17, dbs.size());
		assertEquals("6", named(dbs, "db0").getMetadata().get("Keys"));
		assertEquals("1", named(dbs, "db3").getMetadata().get("Keys"));
		assertEquals("", named(dbs, "db1").getMetadata().get("Keys"));

		List<NuclrResource> top = open(named(dbs, "db0"));
		assertEquals(List.of("..", "queue", "user", "counter", "events"), names(top));
		RedisResource user = named(top, "user");
		assertTrue(user.isFolder());
		assertEquals("3 keys", user.getMetadata().get("Size"));
		assertEquals("Local / db0", plugin.getCurrentLocationDisplayText());

		List<NuclrResource> users = open(user);
		assertEquals(List.of("..", "1", "2"), names(users));
		assertEquals("Local / db0 / user:", plugin.getCurrentLocationDisplayText());
		List<NuclrResource> one = open(named(users, "1"));
		assertEquals(List.of("..", "name", "profile"), names(one));

		RedisResource profileKey = named(one, "profile");
		assertEquals("hash", profileKey.getMetadata().get("Type"));
		assertEquals("2 fields", profileKey.getMetadata().get("Size"));
		assertEquals("1m 0s", profileKey.getMetadata().get("TTL"));
		assertTrue(text(profileKey).contains("\"city\" : \"London\""));
		assertEquals("Ada", text(named(one, "name")));
		for (String column : List.of("Name", "Type", "Size", "TTL")) {
			assertTrue(profileKey.getMetadata().containsKey(column), column);
		}
	}

	@Test
	void parentEntriesClimbOneLevel() {
		RedisConnectionProfile profile = saveProfile(false);
		RedisResource db0 = db0(profile);
		List<NuclrResource> one = open(named(open(named(open(db0), "user")), "1"));
		RedisResource up = named(one, "..");
		assertEquals(ResourceKind.FOLDER, up.getKind());
		assertEquals("user:", new String(up.getBytes(), StandardCharsets.UTF_8));

		RedisResource upAgain = named(open(up), "..");
		assertEquals(ResourceKind.DATABASE, upAgain.getKind());
		assertEquals(ResourceKind.CONNECTION, named(open(upAgain), "..").getKind());
	}

	@Test
	void theSeparatorIsTheConnections() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Dots").host("h").separator(".").build());
		session.string(0, "app.cache.x", "1");
		List<NuclrResource> top = open(db0(profile));
		assertTrue(names(top).contains("app"));
		assertTrue(names(top).contains("user:1:name"));
	}

	@Test
	void streamsTheListingThroughTheSink() {
		RedisConnectionProfile profile = saveProfile(false);
		var columns = new ArrayList<String>();
		var streamed = new ArrayList<String>();
		var data = plugin.openResource(db0(profile), new AtomicBoolean(), new EntrySink() {
			@Override
			public void columns(List<String> names) {
				assertTrue(streamed.isEmpty(), "columns come first");
				columns.addAll(names);
			}

			@Override
			public void add(NuclrResource entry) {
				streamed.add(entry.getName());
			}
		});
		assertEquals(List.of("Name", "Type", "Size", "TTL"), columns);
		assertEquals(names(data.getEntries()), streamed);
	}

	@Test
	void aCancelledListingKeepsTheLocation() {
		RedisConnectionProfile profile = saveProfile(false);
		RedisResource db0 = db0(profile);
		var before = plugin.getCurrentResource();
		var data = plugin.openResource(db0, new AtomicBoolean(true));
		assertTrue(data.getEntries().isEmpty());
		assertEquals(before, plugin.getCurrentResource());
	}

	@Test
	void foreignResourcesAreNotOpened() {
		var foreign = new NuclrResource(null) {
			private static final long serialVersionUID = 1L;
		};
		assertNull(plugin.openResource(foreign, new AtomicBoolean()));
		assertFalse(plugin.supports(foreign));
	}

	@Test
	void eachInstanceHasItsOwnUuid() {
		RedisFilePanelPlugin other = newPlugin();
		assertNotEquals(plugin.uuid(), other.uuid());
	}

	@Test
	void theBarOffersEditCopyAndDeleteOnAKey() {
		RedisConnectionProfile profile = saveProfile(false);
		List<NuclrResource> top = open(db0(profile));
		List<String> onCounter = plugin.menuItems(named(top, "counter")).stream().map(NuclrMenuResource::getFunctionKey).toList();
		assertTrue(onCounter.containsAll(List.of("F4", "F5", "F8")), onCounter.toString());

		List<String> onStream = plugin.menuItems(named(top, "events")).stream().map(NuclrMenuResource::getFunctionKey).toList();
		assertFalse(onStream.contains("F4"), "streams are not edited");
		List<String> onFolder = plugin.menuItems(named(top, "user")).stream().map(NuclrMenuResource::getFunctionKey).toList();
		assertTrue(onFolder.contains("F8"));
		assertFalse(onFolder.contains("F4"));
	}

	@Test
	void aReadOnlyConnectionOffersNoChanges() {
		RedisConnectionProfile profile = saveProfile(true);
		List<NuclrResource> top = open(db0(profile));
		List<String> keys = plugin.menuItems(named(top, "counter")).stream().map(NuclrMenuResource::getFunctionKey).toList();
		assertFalse(keys.contains("F4"));
		assertFalse(keys.contains("F8"));
		var targets = RedisFilePanelPlugin.deleteTargets(List.of(), named(top, "counter"));
		assertTrue(targets.isEmpty());
	}

	@Test
	void deletesKeysAndWholeFolders() throws Exception {
		RedisConnectionProfile profile = saveProfile(false);
		List<NuclrResource> top = open(db0(profile));
		var targets = RedisFilePanelPlugin.deleteTargets(
				List.of(named(top, "user"), named(top, "counter"), named(top, "..")), null);
		assertEquals(1, targets.keys().size());
		assertEquals(1, targets.folders().size());
		assertEquals(3, targets.folderKeys());

		assertEquals(4, plugin.deleteKeys(targets, null));
		assertFalse(session.exists(0, "user:1:name"));
		assertFalse(session.exists(0, "counter"));
		assertTrue(session.exists(0, "queue:jobs"));
		assertTrue(session.exists(3, "other"));
	}

	@Test
	void theFocusedRowIsDeletedWhenNothingIsSelected() {
		RedisConnectionProfile profile = saveProfile(false);
		List<NuclrResource> top = open(db0(profile));
		var targets = RedisFilePanelPlugin.deleteTargets(List.of(), named(top, "events"));
		assertEquals(List.of("events"), targets.keys().stream().map(k -> new String(k, StandardCharsets.UTF_8)).toList());
	}

	@Test
	void theReaderOfAKeyRowReadsTheServer() throws Exception {
		RedisConnectionProfile profile = saveProfile(false);
		RedisResource counter = named(open(db0(profile)), "counter");
		session.string(0, "counter", "42");
		var value = (RedisValue.StringValue) counter.readValue(10, 10);
		assertEquals("42", new String(value.bytes(), StandardCharsets.UTF_8));
		assertTrue(new RedisQuickViewPlugin().supports(counter));
	}

	@Test
	void connectsOnceAndAsksForNoPasswordWithoutAuth() {
		RedisConnectionProfile profile = saveProfile(false);
		open(db0(profile));
		open(db0(profile));
		assertEquals(1, passwords.size());
		assertNull(passwords.get(0));
	}

	@Test
	void aStoredPasswordIsUsed() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Auth").host("h").auth(true).build());
		context.credentials.secrets.put(profile.getId(), "s3cret");
		open(db0(profile));
		assertEquals(List.of("s3cret"), passwords);
	}

	@Test
	void aRefusedStoredPasswordIsAskedForAgainAtOnce() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Auth").host("h").auth(true).build());
		context.credentials.secrets.put(profile.getId(), "old");
		serverPassword = "new";
		typed.add("new");

		assertEquals(17, open(RedisResource.connection(profile)).size());
		assertEquals(List.of("old", "new"), passwords);
		assertEquals(1, prompts.size());
		assertTrue(prompts.get(0).contains("refused"), prompts.get(0));
		assertEquals("new", context.credentials.secrets.get(profile.getId()), "the new password replaces the refused one");
	}

	@Test
	void aRefusedStoredPasswordIsNotSentAgainAfterTheUserCancels() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Auth").host("h").auth(true).build());
		context.credentials.secrets.put(profile.getId(), "old");
		serverPassword = "new";

		assertTrue(open(RedisResource.connection(profile)).isEmpty()); // prompt dismissed
		assertTrue(open(RedisResource.connection(profile)).isEmpty()); // asked again, dismissed again
		assertEquals(List.of("old"), passwords, "the refused password went to the server once");
		assertEquals(2, prompts.size());

		typed.add("new");
		assertEquals(17, open(RedisResource.connection(profile)).size());
	}

	@Test
	void aRefusedPasswordIsForgottenEvenWhenTheNewOneIsNotRemembered() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Auth").host("h").auth(true).build());
		context.credentials.secrets.put(profile.getId(), "old");
		serverPassword = "new";
		rememberTyped = false;
		typed.add("new");
		assertEquals(17, open(RedisResource.connection(profile)).size());
		assertFalse(context.credentials.secrets.containsKey(profile.getId()), "the refused password is gone");

		// After a restart: nothing stored, so the user is asked, and "old" is never sent again.
		plugin.unload();
		plugin = newPlugin();
		plugin.preinit(context);
		typed.add("new");
		passwords.clear();
		assertEquals(17, open(RedisResource.connection(profile)).size());
		assertEquals(List.of("new"), passwords);
	}

	@Test
	void aServerThatWantsAPasswordAsksForOne() {
		RedisConnectionProfile profile = saveProfile(false);
		serverPassword = "pw";
		typed.add("pw");
		assertEquals(17, open(RedisResource.connection(profile)).size());
		assertTrue(new ConnectionProfileStore(context.settings).findById(profile.getId()).isAuth());
	}

	@Test
	void aWrongTypedPasswordIsReportedNotRetried() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Auth").host("h").auth(true).build());
		serverPassword = "right";
		typed.add("wrong");
		assertTrue(open(RedisResource.connection(profile)).isEmpty());
		assertEquals(List.of("wrong"), passwords);
		assertEquals(1, prompts.size());
	}

	@Test
	void foldersOfACutShortListingSayTheirCountIsAFloor() {
		RedisConnectionProfile profile = saveProfile(false);
		for (int i = 0; i < 3_000; i++) {
			session.string(0, "user:bulk:" + i, "v");
		}
		plugin.scanCap = 1_000;
		List<NuclrResource> top = open(db0(profile));
		RedisResource user = named(top, "user");
		assertTrue(user.isCountPartial());
		assertTrue(user.getMetadata().get("Size").toString().endsWith("+ keys"), user.getMetadata().get("Size").toString());
		assertTrue(names(top).get(names(top).size() - 1).startsWith("Stopped after scanning"));

		var targets = RedisFilePanelPlugin.deleteTargets(List.of(user), null);
		assertTrue(targets.folderKeysPartial());
		String question = RedisFilePanelPlugin.deleteQuestion(targets, user);
		assertTrue(question.contains("at least") && question.contains("many more"), question);
	}

	@Test
	void aCompleteListingGivesAnExactCount() {
		RedisConnectionProfile profile = saveProfile(false);
		RedisResource user = named(open(db0(profile)), "user");
		assertFalse(user.isCountPartial());
		String question = RedisFilePanelPlugin.deleteQuestion(RedisFilePanelPlugin.deleteTargets(List.of(user), null), user);
		assertFalse(question.contains("at least"), question);
	}

	@Test
	void deletingAConnectionForgetsItsPassword() {
		RedisConnectionProfile profile = saveProfile(false);
		context.credentials.secrets.put(profile.getId(), "pw");
		open(db0(profile));
		plugin.deleteConnections(List.of(profile.getId()));
		assertTrue(open(RedisResource.explorerRoot()).isEmpty());
		assertFalse(context.credentials.secrets.containsKey(profile.getId()));
		assertTrue(session.closed);
	}

	@Test
	void aClusterNodeIsRefused() {
		session.mode = "cluster";
		var registry = new ConnectionRegistry((p, s) -> session);
		RedisConnectionProfile profile = RedisConnectionProfile.builder().id("c").name("c").host("h").build();
		assertThrows(UnsupportedOperationException.class, () -> registry.connect(profile, null));
	}

	@Test
	void anErrorComesBackAsAnEmptyListing() {
		var store = new ConnectionProfileStore(context.settings);
		RedisConnectionProfile profile = store.add(RedisConnectionProfile.builder().name("Down").host("h").build());
		var failing = new RedisFilePanelPlugin((p, s) -> {
			throw new IllegalStateException("refused");
		});
		failing.preinit(context);
		var data = failing.openResource(RedisResource.connection(profile), new AtomicBoolean());
		assertTrue(data.getEntries().isEmpty());
		failing.unload();
	}

	@Test
	void selectionSummaryCountsKeys() {
		RedisConnectionProfile profile = saveProfile(false);
		List<NuclrResource> top = open(db0(profile));
		assertEquals("2 keys selected", plugin.getSelectionSummaryText(List.of(named(top, "counter"), named(top, "events"))));
		assertEquals("2 items selected", plugin.getSelectionSummaryText(List.of(named(top, "counter"), named(top, "user"))));
		assertEquals("", plugin.getSelectionSummaryText(List.of()));
	}

	@Test
	void newConnectionActionWithoutADisplayChangesNothing() {
		var data = new HashMap<String, Object>();
		plugin.act(null, "redis.disconnect", List.of(), null, data, null);
		assertTrue(data.isEmpty());
	}

	@Test
	void keyRowsOfNonUtf8KeysKeepTheirBytes() {
		RedisConnectionProfile profile = saveProfile(false);
		byte[] key = { 'b', 'i', 'n', ':', (byte) 0xff };
		session.putBytes(0, key, FakeSession.list("x"));
		RedisResource row = named(open(named(open(db0(profile)), "bin")), "\\xff");
		assertTrue(java.util.Arrays.equals(key, row.getBytes()));
		assertEquals(b("bin:").length, 4);
	}
}
