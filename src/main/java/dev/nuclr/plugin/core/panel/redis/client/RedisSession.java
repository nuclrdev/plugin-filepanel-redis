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
package dev.nuclr.plugin.core.panel.redis.client;

import java.util.List;

/**
 * Everything the panel, the quick view and the editor ask of a Redis server. One
 * implementation talks to a real server ({@link JedisSession}); tests answer from memory.
 * Implementations must be safe to call from several threads at once.
 */
public interface RedisSession extends AutoCloseable {

	/** {@code redis_version} and {@code redis_mode} (standalone, sentinel, cluster). */
	record ServerInfo(String version, String mode) {
	}

	/** One {@code SCAN} step: the keys it returned and the cursor to continue from. */
	record ScanPage(byte[] cursor, List<byte[]> keys) {

		public boolean finished() {
			return cursor == null || (cursor.length == 1 && cursor[0] == '0');
		}
	}

	/** What a successful write did, or why it did not happen. */
	enum WriteResult {
		WRITTEN,
		/** The value on the server is no longer the one the edit started from. */
		CHANGED
	}

	byte[] START = { '0' };

	ServerInfo ping();

	/** How many logical databases the server has ({@code CONFIG GET databases}). */
	int databaseCount();

	/** Keys per non-empty database, from {@code INFO keyspace}; index = database. */
	long[] keyCounts(int databases);

	ScanPage scan(int db, byte[] cursor, byte[] match, int count);

	/** Type, TTL and length of each key, in the order given. */
	List<KeyInfo> describe(int db, List<byte[]> keys);

	/**
	 * The value of {@code key}, reading at most {@code itemCap} elements (stream entries
	 * newest first) or {@code byteCap} bytes of a string.
	 */
	RedisValue read(int db, byte[] key, int itemCap, int byteCap);

	/** Deletes the keys; returns how many existed. */
	long delete(int db, List<byte[]> keys);

	/**
	 * Replaces the value of {@code key} with {@code value}, keeping its TTL. Unless
	 * {@code force}, the write happens only while the server still holds {@code expected}
	 * (checked under {@code WATCH}, so a change in between is not overwritten either).
	 * With {@code force} the write is unconditional and always answers {@code WRITTEN}.
	 */
	WriteResult replace(int db, byte[] key, RedisValue expected, RedisValue value, boolean force);

	@Override
	void close();
}
