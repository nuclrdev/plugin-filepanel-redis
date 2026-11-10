package dev.nuclr.plugin.core.panel.redis.edit;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.client.KeyInfo;
import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;
import dev.nuclr.plugin.core.panel.redis.support.FakeSession;
import dev.nuclr.plugin.core.panel.redis.value.ValueCodec;

class ValueEditServiceTest {

	private FakeSession session;
	private final List<String> errors = new ArrayList<>();
	private boolean overwrite;
	private int conflicts;
	private ValueEditService service;
	/** When set, the forced write after a confirmed conflict answers CHANGED anyway. */
	private boolean forcedWriteFails;
	private ValueEditService.Session open;
	private long clock = System.currentTimeMillis() + 5_000;

	@BeforeEach
	void setUp() {
		session = new FakeSession()
				.put(0, "user:1", FakeSession.hash("name", "Ada"), 30_000)
				.string(0, "greeting", "hello")
				.put(0, "log", FakeSession.stream("1-0 a b"), -1);
		service = new ValueEditService(
				(key, expected, value, force) -> force && forcedWriteFails ? RedisSession.WriteResult.CHANGED
						: session.replace(key.getDb(), key.getBytes(), expected, value, force),
				new ValueEditService.Prompts() {
					@Override
					public boolean confirmOverwrite(String keyName) {
						conflicts++;
						return overwrite;
					}

					@Override
					public void error(String title, String message) {
						errors.add(message);
					}
				});
	}

	@AfterEach
	void cleanUp() throws Exception {
		if (open != null) {
			Files.deleteIfExists(open.tempFile);
			Files.deleteIfExists(open.tempDir);
		}
	}

	private RedisResource key(String name, KeyType type) {
		var info = new KeyInfo(b(name), type.redisName(), type, -1, 1);
		return RedisResource.key("p", 0, false, new byte[0], info)
				.withReader((k, items, bytes) -> session.read(k.getDb(), k.getBytes(), items, bytes));
	}

	/** Writes {@code text} as the editor's save would, with a newer timestamp. */
	private void save(String text) throws Exception {
		Files.writeString(open.tempFile, text, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(open.tempFile, FileTime.fromMillis(clock += 2_000));
	}

	@Test
	void opensAHashAsJsonAndSavesItBack() throws Exception {
		open = service.prepare(key("user:1", KeyType.HASH));
		assertTrue(open.tempFile.getFileName().toString().endsWith(".json"));
		assertTrue(Files.readString(open.tempFile).contains("\"name\" : \"Ada\""));

		save("{\"name\": \"Ada Lovelace\", \"born\": 1815}");
		service.syncOnce(open);

		var hash = (RedisValue.HashValue) session.get(0, "user:1");
		assertEquals(2, hash.fields().size());
		assertEquals(30_000, session.ttl(0, "user:1"), "the TTL is kept");
		assertTrue(errors.isEmpty(), errors.toString());
	}

	@Test
	void nothingIsWrittenWhileTheFileIsUnchanged() throws Exception {
		open = service.prepare(key("greeting", KeyType.STRING));
		session.string(0, "greeting", "changed elsewhere");
		service.syncOnce(open);
		assertEquals(0, conflicts);
		assertEquals("changed elsewhere",
				new String(((RedisValue.StringValue) session.get(0, "greeting")).bytes(), StandardCharsets.UTF_8));
	}

	@Test
	void aChangeOnTheServerIsNotOverwrittenUnasked() throws Exception {
		open = service.prepare(key("greeting", KeyType.STRING));
		session.string(0, "greeting", "someone else");
		save("mine");
		service.syncOnce(open);
		assertEquals(1, conflicts);
		assertEquals("someone else",
				new String(((RedisValue.StringValue) session.get(0, "greeting")).bytes(), StandardCharsets.UTF_8));
		assertTrue(open.stopped);
		service.finish(open);
		assertTrue(Files.exists(open.tempFile), "the user's version is kept");
	}

	@Test
	void aChangeOnTheServerIsOverwrittenWhenConfirmed() throws Exception {
		overwrite = true;
		open = service.prepare(key("greeting", KeyType.STRING));
		session.string(0, "greeting", "someone else");
		save("mine");
		service.syncOnce(open);
		assertEquals("mine", new String(((RedisValue.StringValue) session.get(0, "greeting")).bytes(), StandardCharsets.UTF_8));
		assertFalse(open.stopped);
	}

	@Test
	void aForcedWriteThatDidNotHappenIsNeverReportedAsSaved() throws Exception {
		overwrite = true;
		forcedWriteFails = true;
		open = service.prepare(key("greeting", KeyType.STRING));
		session.string(0, "greeting", "someone else");
		save("mine");
		service.syncOnce(open);
		assertTrue(open.stopped);
		assertEquals(1, errors.size(), "the user is told it was not saved");
		service.finish(open); // what the watcher does once the session stops
		assertTrue(Files.exists(open.tempFile), "and their version is kept");
		assertEquals("someone else",
				new String(((RedisValue.StringValue) session.get(0, "greeting")).bytes(), StandardCharsets.UTF_8));
	}

	@Test
	void aSavedEditLeavesNoFileBehind() throws Exception {
		open = service.prepare(key("greeting", KeyType.STRING));
		save("mine");
		service.syncOnce(open);
		service.finish(open);
		assertFalse(Files.exists(open.tempFile));
	}

	@Test
	void anEditorClosedOnBrokenJsonKeepsTheFile() throws Exception {
		open = service.prepare(key("user:1", KeyType.HASH));
		save("{\"name\": ");
		service.syncOnce(open);
		service.finish(open);
		assertTrue(Files.exists(open.tempFile), "the unsaved text is the user's only copy");
	}

	@Test
	void brokenJsonIsReportedAndTheEditStaysOpen() throws Exception {
		open = service.prepare(key("user:1", KeyType.HASH));
		save("{\"name\": ");
		service.syncOnce(open);
		assertEquals(1, errors.size());
		assertFalse(open.stopped);

		save("{\"name\": \"Fixed\"}");
		service.syncOnce(open);
		var hash = (RedisValue.HashValue) session.get(0, "user:1");
		assertEquals("Fixed", new String(hash.fields().get(0).value(), StandardCharsets.UTF_8));
	}

	@Test
	void consecutiveSavesEachGoThrough() throws Exception {
		open = service.prepare(key("greeting", KeyType.STRING));
		save("one");
		service.syncOnce(open);
		save("two");
		service.syncOnce(open);
		assertEquals(0, conflicts);
		assertEquals("two", new String(((RedisValue.StringValue) session.get(0, "greeting")).bytes(), StandardCharsets.UTF_8));
	}

	@Test
	void aStreamIsNotOpened() {
		assertThrows(ValueCodec.NotEditable.class, () -> service.prepare(key("log", KeyType.STREAM)));
		assertTrue(service.edit(key("log", KeyType.STREAM), null) == null);
		assertEquals(1, errors.size());
	}

	@Test
	void fileNamesAreSafe() {
		assertEquals("a_b_c", ValueEditService.fileName("a/b:c"));
		assertEquals("value", ValueEditService.fileName(""));
		assertEquals("dots.value", ValueEditService.fileName("dots."));
		Path ignored = Path.of(ValueEditService.fileName("con<>|"));
		assertTrue(ignored.toString().length() > 0);
	}
}
