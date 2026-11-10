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
package dev.nuclr.plugin.core.panel.redis.support;

import java.util.HashMap;
import java.util.Map;

import dev.nuclr.platform.NuclrSettings;

/** In-memory {@link NuclrSettings}, namespaced exactly like the host's own store. */
public final class FakeSettings implements NuclrSettings {

	private final Map<String, Object> values = new HashMap<>();

	@Override
	public void set(String namespace, String key, Object value) {
		values.put(namespace + "|" + key, value);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T get(String namespace, String key) {
		return (T) values.get(namespace + "|" + key);
	}

	@Override
	public <T> T getOrDefault(String namespace, String key, T defaultValue) {
		T value = get(namespace, key);
		return value == null ? defaultValue : value;
	}

	@Override
	public boolean isDeveloperModeOn() {
		return false;
	}

	/** Overwrite a stored value directly, to simulate a corrupted settings file. */
	public void putRaw(String namespace, String key, Object value) {
		set(namespace, key, value);
	}
}
