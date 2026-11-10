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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Field;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.HashValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ListValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.Scored;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.SetValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.StringValue;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue.ZSetValue;

/** Content comparison of {@link RedisValue}s, which as records compare arrays by reference. */
public final class Values {

	private Values() {
	}

	/**
	 * Whether two reads hold the same data. Hashes and sets compare as unordered, the way
	 * Redis stores them; lists and sorted sets in order.
	 */
	public static boolean sameContent(RedisValue a, RedisValue b) {
		if (a == null || b == null || a.type() != b.type() || a.total() != b.total() || a.read() != b.read()) {
			return false;
		}
		return switch (a) {
			case StringValue s -> Arrays.equals(s.bytes(), ((StringValue) b).bytes());
			case ListValue l -> sameItems(l.items(), ((ListValue) b).items());
			case SetValue s -> asSet(s.members()).equals(asSet(((SetValue) b).members()));
			case HashValue h -> asMap(h.fields()).equals(asMap(((HashValue) b).fields()));
			case ZSetValue z -> sameScored(z.members(), ((ZSetValue) b).members());
			default -> false;
		};
	}

	private static boolean sameItems(List<byte[]> a, List<byte[]> b) {
		for (int i = 0; i < a.size(); i++) {
			if (!Arrays.equals(a.get(i), b.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameScored(List<Scored> a, List<Scored> b) {
		for (int i = 0; i < a.size(); i++) {
			if (!Arrays.equals(a.get(i).member(), b.get(i).member())
					|| Double.compare(a.get(i).score(), b.get(i).score()) != 0) {
				return false;
			}
		}
		return true;
	}

	private static HashSet<ByteBuffer> asSet(List<byte[]> members) {
		var set = new HashSet<ByteBuffer>();
		for (byte[] member : members) {
			set.add(ByteBuffer.wrap(member));
		}
		return set;
	}

	private static Map<ByteBuffer, ByteBuffer> asMap(List<Field> fields) {
		var map = new HashMap<ByteBuffer, ByteBuffer>();
		for (Field field : fields) {
			map.put(ByteBuffer.wrap(field.name()), ByteBuffer.wrap(field.value()));
		}
		return map;
	}

	/** Unsigned byte order, the order Redis itself sorts keys and members in. */
	public static int compare(byte[] a, byte[] b) {
		return Arrays.compareUnsigned(a, b);
	}
}
