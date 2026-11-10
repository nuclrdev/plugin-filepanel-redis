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

import java.util.Locale;

/** A key's data type, as {@code TYPE} names it. */
public enum KeyType {

	STRING("string"),
	HASH("hash"),
	LIST("list"),
	SET("set"),
	ZSET("zset"),
	STREAM("stream"),
	/** A module type ({@code ReJSON-RL}, {@code TSDB-TYPE}...): listed, not opened. */
	OTHER(""),
	/** The key is gone: it expired or was deleted since the listing. */
	NONE("none");

	private final String redisName;

	KeyType(String redisName) {
		this.redisName = redisName;
	}

	public String redisName() {
		return redisName;
	}

	public static KeyType of(String typeName) {
		if (typeName == null) {
			return NONE;
		}
		String name = typeName.toLowerCase(Locale.ROOT);
		for (KeyType type : values()) {
			if (type != OTHER && type.redisName.equals(name)) {
				return type;
			}
		}
		return OTHER;
	}

	/** Whether Quick View can show it. */
	public boolean readable() {
		return this != OTHER && this != NONE;
	}

	/** Whether F4 can turn it into text and back. Streams are append-only logs, so they are not. */
	public boolean editable() {
		return readable() && this != STREAM;
	}
}
