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

/**
 * What a listing shows for one key, gathered for a whole folder in a couple of
 * pipelined round trips.
 *
 * @param typeName the type as the server named it, kept for module types
 * @param ttlMillis {@code PTTL}: -1 for no expiry, -2 when the key is gone
 * @param length    bytes of a string, elements of anything else, -1 when unknown
 */
public record KeyInfo(byte[] key, String typeName, KeyType type, long ttlMillis, long length) {

	public static final long NO_EXPIRY = -1;
}
