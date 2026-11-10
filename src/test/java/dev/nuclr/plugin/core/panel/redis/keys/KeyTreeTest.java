package dev.nuclr.plugin.core.panel.redis.keys;

import static dev.nuclr.plugin.core.panel.redis.support.FakeSession.b;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.redis.support.FakeSession;

class KeyTreeTest {

	private static List<String> names(List<byte[]> keys) {
		return keys.stream().map(k -> new String(k, StandardCharsets.UTF_8)).toList();
	}

	private static List<String> folders(KeyTree.Level level) {
		return level.folders().stream().map(f -> new String(f.segment(), StandardCharsets.UTF_8) + "=" + f.keys()).toList();
	}

	@Test
	void groupsTheTopLevelBySeparator() {
		var tree = new KeyTree(new byte[0], b(":"));
		for (String key : List.of("user:1:name", "user:1:mail", "user:2:name", "session:abc", "counter", "user")) {
			tree.add(b(key));
		}
		KeyTree.Level level = tree.level(false, false);
		assertEquals(List.of("session=1", "user=3"), folders(level));
		assertEquals(List.of("counter", "user"), names(level.keys()));
		assertArrayEquals(b("user:"), level.folders().get(1).prefix());
	}

	@Test
	void listsOneLevelBelowAPrefix() {
		var tree = new KeyTree(b("user:"), b(":"));
		for (String key : List.of("user:1:name", "user:1:mail", "user:2", "other:1")) {
			tree.add(b(key));
		}
		KeyTree.Level level = tree.level(false, false);
		assertEquals(List.of("1=2"), folders(level));
		assertEquals(List.of("user:2"), names(level.keys()));
		assertArrayEquals(b("user:1:"), level.folders().get(0).prefix());
	}

	@Test
	void emptySegmentsAreFoldersToo() {
		var tree = new KeyTree(new byte[0], b(":"));
		tree.add(b("a::b"));
		tree.add(b(":lead"));
		KeyTree.Level level = tree.level(false, false);
		assertEquals(List.of("=1", "a=1"), folders(level));
		assertArrayEquals(b(":"), level.folders().get(0).prefix());
	}

	@Test
	void multiCharacterSeparators() {
		var tree = new KeyTree(new byte[0], b("::"));
		tree.add(b("app::cache::x"));
		tree.add(b("a:b"));
		KeyTree.Level level = tree.level(false, false);
		assertEquals(List.of("app=1"), folders(level));
		assertEquals(List.of("a:b"), names(level.keys()));
	}

	@Test
	void aKeyScanTwiceCountsOnce() {
		var tree = new KeyTree(new byte[0], b(":"));
		tree.add(b("a:1"));
		tree.add(b("a:1"));
		assertEquals(List.of("a=1"), folders(tree.level(false, false)));
		assertEquals(1, tree.scanned());
	}

	@Test
	void anOversizedScanPageIsStillHandedOverInBatches() {
		var session = new FakeSession();
		session.ignoreCount = true;
		for (int i = 0; i < 2_500; i++) {
			session.string(0, "big:" + i, "v");
		}
		var batches = new java.util.ArrayList<Integer>();
		long deleted = KeyTree.forEachPage(session, 0, b("big:"), null, 1_000, batch -> {
			batches.add(batch.size());
			return session.delete(0, batch);
		});
		assertEquals(1, session.scans, "one SCAN answered everything");
		assertEquals(List.of(1_000, 1_000, 500), batches);
		assertEquals(2_500, deleted);
	}

	@Test
	void prefixPatternEscapesGlobCharacters() {
		assertEquals("*", new String(KeyNames.prefixPattern(new byte[0]), StandardCharsets.UTF_8));
		assertEquals("a\\*b\\?\\[c\\]\\\\:*",
				new String(KeyNames.prefixPattern(b("a*b?[c]\\:")), StandardCharsets.UTF_8));
	}

	@Test
	void scanFollowsTheCursorAndStopsAtTheCap() {
		var session = new FakeSession();
		for (int i = 0; i < 2_500; i++) {
			session.string(0, "k:" + i, "v");
		}
		KeyTree.Level all = KeyTree.scan(session, 0, b("k:"), b(":"), 10_000, null, null);
		assertEquals(2_500, all.keys().size());
		assertFalse(all.capped());

		KeyTree.Level capped = KeyTree.scan(session, 0, new byte[0], b(":"), 1_000, null, null);
		assertTrue(capped.capped());
		assertEquals(List.of("k=1000"), folders(capped));
	}

	@Test
	void scanStopsWhenCancelled() {
		var session = new FakeSession().string(0, "a", "1");
		KeyTree.Level level = KeyTree.scan(session, 0, new byte[0], b(":"), 100, new AtomicBoolean(true), null);
		assertTrue(level.cancelled());
		assertEquals(0, session.scans);
	}

	@Test
	void pagesOfKeysUnderAPrefixWithGlobCharacters() {
		var session = new FakeSession().string(0, "a*:1", "x").string(0, "a*:2", "x").string(0, "ab:1", "x");
		var seen = new java.util.ArrayList<String>();
		long total = KeyTree.forEachPage(session, 0, b("a*:"), null, 1_000, page -> {
			seen.addAll(names(page));
			return page.size();
		});
		assertEquals(List.of("a*:1", "a*:2"), seen);
		assertEquals(2, total);
	}

	@Test
	void aBigFolderIsHandedOverPageByPageNeverWhole() {
		var session = new FakeSession();
		for (int i = 0; i < 5_000; i++) {
			session.string(0, "big:" + i, "v");
		}
		var pageSizes = new java.util.ArrayList<Integer>();
		// Deleting each page as it comes, as F8 does, must still reach every key.
		long deleted = KeyTree.forEachPage(session, 0, b("big:"), null, 1_000, page -> {
			pageSizes.add(page.size());
			return session.delete(0, page);
		});
		assertEquals(5_000, deleted);
		assertEquals(0, session.size(0));
		assertTrue(pageSizes.size() > 1);
		assertTrue(pageSizes.stream().allMatch(size -> size <= KeyTree.SCAN_COUNT), pageSizes.toString());
	}

	@Test
	void namesShowBytesThatAreNotUtf8() {
		assertEquals("café", KeyNames.display("café".getBytes(StandardCharsets.UTF_8)));
		assertEquals("a\\xff\\x00b", KeyNames.display(new byte[] { 'a', (byte) 0xff, 0, 'b' }));
		assertEquals("line\\nbreak", KeyNames.display(b("line\nbreak")));
		assertNull(KeyNames.utf8OrNull(new byte[] { (byte) 0xc3 }));
	}
}
