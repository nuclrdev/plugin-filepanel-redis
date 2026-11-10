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
 * A key's value as read for Quick View or for an edit, bounded by the reader's caps.
 * {@link #total()} is the full size on the server, so {@link #truncated()} tells whether
 * what was read is all of it.
 */
public sealed interface RedisValue {

	KeyType type();

	/** Bytes of a string, elements of anything else. */
	long total();

	/** How many bytes or elements were read. */
	long read();

	default boolean truncated() {
		return read() < total();
	}

	record Field(byte[] name, byte[] value) {
	}

	record Scored(byte[] member, double score) {
	}

	/** One stream entry; {@code fields} in the order they were added. */
	record Entry(String id, List<Field> fields) {
	}

	record StringValue(byte[] bytes, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.STRING;
		}

		@Override
		public long read() {
			return bytes.length;
		}
	}

	record HashValue(List<Field> fields, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.HASH;
		}

		@Override
		public long read() {
			return fields.size();
		}
	}

	record ListValue(List<byte[]> items, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.LIST;
		}

		@Override
		public long read() {
			return items.size();
		}
	}

	record SetValue(List<byte[]> members, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.SET;
		}

		@Override
		public long read() {
			return members.size();
		}
	}

	/** Members in rank order, lowest score first. */
	record ZSetValue(List<Scored> members, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.ZSET;
		}

		@Override
		public long read() {
			return members.size();
		}
	}

	/** The newest entries, newest first. */
	record StreamValue(List<Entry> entries, long total) implements RedisValue {

		@Override
		public KeyType type() {
			return KeyType.STREAM;
		}

		@Override
		public long read() {
			return entries.size();
		}
	}

	/** The key does not exist (any more), or holds a type this plugin cannot read. */
	record Unreadable(KeyType type, String typeName) implements RedisValue {

		@Override
		public long total() {
			return 0;
		}

		@Override
		public long read() {
			return 0;
		}
	}
}
