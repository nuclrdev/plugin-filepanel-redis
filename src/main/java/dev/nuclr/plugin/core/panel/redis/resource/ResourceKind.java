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
package dev.nuclr.plugin.core.panel.redis.resource;

/** What a {@link RedisResource} row stands for. */
public enum ResourceKind {
	/** The saved connections. */
	EXPLORER_ROOT,
	/** One server; lists its databases. */
	CONNECTION,
	/** {@code db0}, {@code db1}...; lists the top level of its keys. */
	DATABASE,
	/** The keys sharing a prefix up to a separator. */
	FOLDER,
	KEY,
	/** The note at the end of a listing cut short by the scan limit. */
	LIMIT_MARKER
}
