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
package dev.nuclr.plugin.core.panel.redis.keys;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.nuclr.plugin.core.panel.redis.client.RedisSession;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession.ScanPage;
import dev.nuclr.plugin.core.panel.redis.client.Values;

/**
 * Redis has no folders, only flat keys. This turns one level of a key space into a
 * folder listing: {@code SCAN} every key under a prefix, then group by the next separator,
 * so {@code user:1:session} under {@code user:} becomes the folder {@code 1}.
 *
 * <p>Folders exist only because keys do: one appears when a key below it is found, and
 * a folder's count is of the keys anywhere beneath it.
 */
public final class KeyTree {

	/** Keys asked for per {@code SCAN} step. A hint the server may exceed. */
	static final int SCAN_COUNT = 1_000;

	/** A subfolder: {@code prefix} is the whole prefix of its keys, separator included. */
	public record Folder(byte[] segment, byte[] prefix, long keys) {
	}

	/**
	 * One level. {@code capped} when the scan stopped at the limit, so the folders and
	 * counts cover only the keys seen.
	 */
	public record Level(List<Folder> folders, List<byte[]> keys, long scanned, boolean capped, boolean cancelled) {
	}

	private final byte[] prefix;
	private final byte[] separator;
	private final Map<ByteBuffer, long[]> folderCounts = new LinkedHashMap<>();
	private final List<byte[]> keys = new ArrayList<>();
	/** SCAN can return a key more than once while the server resizes its table. */
	private final Set<ByteBuffer> seen = new HashSet<>();
	private long scanned;

	public KeyTree(byte[] prefix, byte[] separator) {
		this.prefix = prefix == null ? new byte[0] : prefix;
		this.separator = separator;
	}

	/** Files {@code key} under this level; keys outside the prefix are ignored. */
	public void add(byte[] key) {
		if (!KeyNames.startsWith(key, prefix) || !seen.add(ByteBuffer.wrap(key))) {
			return;
		}
		scanned++;
		int at = KeyNames.indexOf(key, separator, prefix.length);
		if (at < 0) {
			keys.add(key);
			return;
		}
		byte[] segment = java.util.Arrays.copyOfRange(key, prefix.length, at);
		folderCounts.computeIfAbsent(ByteBuffer.wrap(segment), s -> new long[1])[0]++;
	}

	public long scanned() {
		return scanned;
	}

	/** Folders then keys, each in byte order. */
	public Level level(boolean capped, boolean cancelled) {
		var folders = new ArrayList<Folder>(folderCounts.size());
		folderCounts.forEach((segment, count) -> {
			byte[] bytes = segment.array();
			folders.add(new Folder(bytes, KeyNames.concat(KeyNames.concat(prefix, bytes), separator), count[0]));
		});
		folders.sort((a, b) -> Values.compare(a.segment(), b.segment()));
		var sortedKeys = new ArrayList<>(keys);
		sortedKeys.sort(Values::compare);
		return new Level(folders, sortedKeys, scanned, capped, cancelled);
	}

	/**
	 * Scans the keys under {@code prefix} in {@code db}, at most {@code cap} of them,
	 * stopping between steps when {@code cancelled} is set.
	 *
	 * @param progress told the running count after each step; may be {@code null}
	 */
	public static Level scan(RedisSession session, int db, byte[] prefix, byte[] separator, int cap,
			AtomicBoolean cancelled, Consumer<Long> progress) {
		var tree = new KeyTree(prefix, separator);
		byte[] match = prefix == null || prefix.length == 0 ? null : KeyNames.prefixPattern(prefix);
		byte[] cursor = RedisSession.START;
		boolean capped = false;
		while (true) {
			if (cancelled != null && cancelled.get()) {
				return tree.level(false, true);
			}
			ScanPage page = session.scan(db, cursor, match, SCAN_COUNT);
			for (byte[] key : page.keys()) {
				tree.add(key);
			}
			if (progress != null) {
				progress.accept(tree.scanned());
			}
			if (page.finished()) {
				break;
			}
			if (tree.scanned() >= cap) {
				capped = true;
				break;
			}
			cursor = page.cursor();
		}
		return tree.level(capped, false);
	}

	/** What to do with one page of keys; returns a count to add up. */
	@FunctionalInterface
	public interface PageAction {
		long run(List<byte[]> keys);
	}

	/**
	 * Runs {@code action} on the keys under {@code prefix} one {@code SCAN} page at a time,
	 * so a folder of millions of keys is never held in memory at once. {@code COUNT} is only
	 * a hint and a page may be far bigger, so each is handed over in batches of at most
	 * {@code batchSize}. Deleting the keys of
	 * a page before asking for the next is safe: {@code SCAN} still returns every key that
	 * exists for the whole iteration, and a key it repeats is simply gone already.
	 *
	 * @return the sum of what {@code action} returned
	 */
	public static long forEachPage(RedisSession session, int db, byte[] prefix, AtomicBoolean cancelled,
			int batchSize, PageAction action) {
		int batch = Math.max(1, batchSize);
		byte[] match = KeyNames.prefixPattern(prefix);
		byte[] cursor = RedisSession.START;
		long total = 0;
		while (cancelled == null || !cancelled.get()) {
			ScanPage page = session.scan(db, cursor, match, SCAN_COUNT);
			var keys = new ArrayList<byte[]>(page.keys().size());
			for (byte[] key : page.keys()) {
				if (KeyNames.startsWith(key, prefix)) {
					keys.add(key);
				}
			}
			for (int from = 0; from < keys.size(); from += batch) {
				if (cancelled != null && cancelled.get()) {
					return total;
				}
				total += action.run(keys.subList(from, Math.min(keys.size(), from + batch)));
			}
			if (page.finished()) {
				break;
			}
			cursor = page.cursor();
		}
		return total;
	}
}
